package com.citigroup.copilotchat.agent

import com.citigroup.copilotchat.conversation.ConversationManager
import com.citigroup.copilotchat.lsp.LspClient
import com.citigroup.copilotchat.tools.ToolRouter
import com.citigroup.copilotchat.orchestrator.WorkerSession
import com.citigroup.copilotchat.orchestrator.WorkerEvent
import com.citigroup.copilotchat.workingset.WorkingSetService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.*
import java.util.*
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Core service for the Agent tab. Manages the lead agent conversation,
 * delegates tasks to subagents via WorkerSession, and routes tool calls.
 *
 * Data flow:
 * 1. User sends message -> lead agent conversation created/continued
 * 2. Lead model calls delegate_task -> subagent spawned in background
 * 3. Lead turn ends -> service waits for all subagents to complete
 * 4. Results collected -> follow-up turn sent to lead with all results
 * 5. Lead model synthesizes final answer
 */
@Service(Service.Level.PROJECT)
class AgentService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(AgentService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<AgentEvent> = _events

    private val lspClient: LspClient get() = LspClient.getInstance(project)
    private val conversationManager: ConversationManager get() = ConversationManager.getInstance(project)

    @Volatile
    private var leadConversationId: String? = null
    /** True between conversation/create call and its response — tool calls that arrive
     *  during this window carry the conversationId we need but don't have yet. */
    @Volatile
    private var pendingLeadCreate: Boolean = false
    var isStreaming: Boolean = false
        private set

    private var agents: List<AgentDefinition> = emptyList()
    private val activeSubagents = ConcurrentHashMap<String, WorkerSession>()

    /** Background subagent jobs launched by delegate_task. Collected after lead turn ends. */
    private val pendingSubagents = ConcurrentHashMap<String, PendingSubagent>()

    /** Hard-enforced tool filters keyed by subagent conversationId. */
    private val subagentToolFilters = ConcurrentHashMap<String, SubagentToolFilter>()

    /** Worktree metadata for subagents with forkContext=true. Keyed by agentId. */
    private val subagentWorktrees = ConcurrentHashMap<String, WorktreeInfo>()

    private var currentJob: Job? = null
    private var currentWorkDoneToken: String? = null
    private lateinit var toolRouter: ToolRouter

    companion object {
        fun getInstance(project: Project): AgentService =
            project.getService(AgentService::class.java)
    }

    /**
     * Send a message to the lead agent. Creates a new conversation on first call,
     * continues on subsequent calls.
     *
     * The wait loop supports multi-turn delegation: when the lead turn ends with
     * pending subagents, it waits for them, collects results, and sends a follow-up
     * turn so the lead can synthesize the final answer.
     */
    fun sendMessage(text: String, model: String? = null, leadAgentType: String? = null) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                conversationManager.ensureInitialized()
                toolRouter = ToolRouter(project)

                // Load agent definitions
                agents = AgentRegistry.loadAll(project.basePath)

                // Resolve lead agent definition
                val leadAgent = if (leadAgentType != null) {
                    AgentRegistry.findByType(leadAgentType, agents)
                } else null

                isStreaming = true
                val useModel = model
                    ?: leadAgent?.model?.resolveModelId("claude-sonnet-4")
                    ?: "claude-sonnet-4"
                var workDoneToken = "agent-lead-${UUID.randomUUID().toString().take(8)}"
                currentWorkDoneToken = workDoneToken
                val replyParts = Collections.synchronizedList(mutableListOf<String>())

                lspClient.registerProgressListener(workDoneToken) { value ->
                    handleLeadProgress(value, replyParts)
                }

                try {
                    val rootUri = project.basePath?.let { "file://$it" } ?: "file:///tmp"
                    val isFirstTurn = leadConversationId == null
                    val prompt = if (isFirstTurn) buildLeadPrompt(text, leadAgent) else text

                    if (isFirstTurn) {
                        val params = buildJsonObject {
                            put("workDoneToken", workDoneToken)
                            putJsonArray("turns") {
                                addJsonObject { put("request", prompt) }
                            }
                            putJsonObject("capabilities") {
                                put("allSkills", true)
                            }
                            put("source", "panel")
                            put("chatMode", "Agent")
                            put("needToolCallConfirmation", true)
                            if (useModel.isNotBlank()) put("model", useModel)
                            put("workspaceFolder", rootUri)
                            putJsonArray("workspaceFolders") {
                                addJsonObject {
                                    put("uri", rootUri)
                                    put("name", project.name)
                                }
                            }
                        }

                        log.info("AgentService: creating lead conversation")
                        pendingLeadCreate = true
                        val resp = lspClient.sendRequest("conversation/create", params, timeoutMs = 300_000)
                        pendingLeadCreate = false
                        val result = resp["result"]
                        // Only set from response if not already captured from an early tool call
                        if (leadConversationId == null) {
                            leadConversationId = when (result) {
                                is JsonArray -> result.firstOrNull()?.jsonObject?.get("conversationId")?.jsonPrimitive?.contentOrNull
                                is JsonObject -> result["conversationId"]?.jsonPrimitive?.contentOrNull
                                else -> null
                            }
                        }
                        log.info("AgentService: lead conversationId=$leadConversationId")
                    } else {
                        val params = buildJsonObject {
                            put("workDoneToken", workDoneToken)
                            put("conversationId", leadConversationId!!)
                            put("message", text)
                            put("source", "panel")
                            put("chatMode", "Agent")
                            put("needToolCallConfirmation", true)
                            if (useModel.isNotBlank()) put("model", useModel)
                            put("workspaceFolder", rootUri)
                            putJsonArray("workspaceFolders") {
                                addJsonObject {
                                    put("uri", rootUri)
                                    put("name", project.name)
                                }
                            }
                        }

                        log.info("AgentService: continuing lead conversation $leadConversationId")
                        lspClient.sendRequest("conversation/turn", params, timeoutMs = 300_000)
                    }

                    // Wait for streaming to complete, with subagent collection loop
                    val startTime = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startTime < 300_000) {
                        delay(100)
                        if (!isStreaming) {
                            if (pendingSubagents.isNotEmpty()) {
                                // Lead turn ended but subagents are still running.
                                // Wait for all, collect results, and send a follow-up turn.
                                log.info("AgentService: lead turn ended with ${pendingSubagents.size} pending subagents — collecting results")
                                lspClient.removeProgressListener(workDoneToken)

                                val resultContext = awaitPendingSubagents()

                                // Register new progress listener for the follow-up turn
                                workDoneToken = "agent-lead-${UUID.randomUUID().toString().take(8)}"
                                currentWorkDoneToken = workDoneToken
                                lspClient.registerProgressListener(workDoneToken) { value ->
                                    handleLeadProgress(value, replyParts)
                                }

                                isStreaming = true
                                sendFollowUpTurn(workDoneToken, resultContext, useModel, rootUri)
                            } else {
                                break // Truly done
                            }
                        }
                    }
                } finally {
                    lspClient.removeProgressListener(workDoneToken)
                }

                currentWorkDoneToken = null
                isStreaming = false
                val fullReply = synchronized(replyParts) { replyParts.joinToString("") }
                _events.emit(AgentEvent.LeadDone(fullReply))

            } catch (e: CancellationException) {
                isStreaming = false
                throw e
            } catch (e: Exception) {
                log.error("Error in AgentService.sendMessage", e)
                isStreaming = false
                _events.emit(AgentEvent.LeadError(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Build the first-turn prompt with system instructions that tell the lead agent
     * to use delegate_task for subtasks and complete the full task without stopping
     * for confirmation.
     *
     * If a [leadAgent] is provided, uses its systemPromptTemplate and scoped subagents.
     * Otherwise falls back to the default-lead behavior.
     */
    private fun buildLeadPrompt(userMessage: String, leadAgent: AgentDefinition? = null): String {
        // Determine which subagents are visible to this lead
        val visibleAgents = if (leadAgent?.subagents != null && leadAgent.subagents.isNotEmpty()) {
            // Scoped: only agents in the supervisor's subagents list
            agents.filter { a -> leadAgent.subagents.any { it.equals(a.agentType, ignoreCase = true) } }
        } else {
            // All workers (agents without subagents field, i.e. not supervisors)
            agents.filter { it.subagents == null }
        }

        val agentList = visibleAgents.joinToString("\n") { "- ${it.agentType}: ${it.whenToUse}" }

        // Use the lead agent's template, or the default
        val template = if (leadAgent != null && leadAgent.systemPromptTemplate.isNotBlank()) {
            leadAgent.systemPromptTemplate
        } else {
            AgentRegistry.DEFAULT_LEAD_TEMPLATE
        }

        // Replace {{AGENT_LIST}} placeholder
        val resolvedPrompt = template.replace("{{AGENT_LIST}}", agentList)

        return "<system_instructions>\n$resolvedPrompt\n</system_instructions>\n\n$userMessage"
    }

    /**
     * Handle a tool call from the LSP server for a conversation owned by this service.
     */
    suspend fun handleToolCall(id: Int, name: String, input: JsonObject, conversationId: String?) {
        log.info("AgentService: received tool call '$name' for conversation=$conversationId")

        // Intercept run_in_terminal calls that are actually delegation commands.
        // The Copilot language server only forwards tool names on its internal allowlist,
        // so we use run_in_terminal as a carrier for delegation.
        if (name == "run_in_terminal") {
            val command = input["command"]?.jsonPrimitive?.contentOrNull ?: ""
            if (command.trimStart().startsWith("delegate ")) {
                log.info("AgentService: intercepted delegation via run_in_terminal: $command")
                val delegateInput = parseDelegateCommand(command)
                handleDelegateTask(id, delegateInput)
                return
            }
        }

        when (name) {
            "delegate_task" -> handleDelegateTask(id, input)
            "create_team", "send_message", "delete_team" -> {
                val teamService = TeamService.getInstance(project)
                val result = teamService.handleToolCall(name, input)
                lspClient.sendResponse(id, result)
            }
            else -> {
                // Standard tool — execute and respond immediately.
                // Do NOT emit LeadToolCall/LeadToolResult here; those events are
                // already emitted by handleLeadProgress() from editAgentRounds.
                // Emitting here too would double the event count and fill the
                // SharedFlow buffer, causing emit() to suspend and deadlock the
                // tool response path.
                val result = toolRouter.executeTool(name, input)
                lspClient.sendResponse(id, result)
            }
        }
    }

    /**
     * Parse a "delegate --type <type> --prompt "<prompt>"" command string
     * into a JsonObject matching the delegate_task input schema.
     */
    private fun parseDelegateCommand(command: String): JsonObject {
        val typeRegex = Regex("""--type\s+(\S+)""")
        val promptRegex = Regex("""--prompt\s+"(.*?)"\s*$""", RegexOption.DOT_MATCHES_ALL)
        // Also support unquoted prompt (everything after --prompt)
        val promptFallbackRegex = Regex("""--prompt\s+(.+)$""", RegexOption.DOT_MATCHES_ALL)

        val subagentType = typeRegex.find(command)?.groupValues?.get(1) ?: "general-purpose"
        val prompt = promptRegex.find(command)?.groupValues?.get(1)
            ?: promptFallbackRegex.find(command)?.groupValues?.get(1)?.trim()
            ?: command.substringAfter("delegate ").trim()

        return buildJsonObject {
            put("description", prompt.take(50))
            put("prompt", prompt)
            put("subagent_type", subagentType)
        }
    }

    /**
     * Handle the delegate_task tool call — spawns a subagent in the background
     * and responds immediately so the server can dispatch the next tool call.
     * Results are collected later in the sendMessage wait loop.
     */
    private suspend fun handleDelegateTask(id: Int, input: JsonObject) {
        val description = input["description"]?.jsonPrimitive?.contentOrNull ?: "subtask"
        val prompt = input["prompt"]?.jsonPrimitive?.contentOrNull ?: ""
        val subagentType = input["subagent_type"]?.jsonPrimitive?.contentOrNull ?: "general-purpose"
        val modelOverride = input["model"]?.jsonPrimitive?.contentOrNull

        val agentDef = AgentRegistry.findByType(subagentType, agents)
        if (agentDef == null) {
            log.warn("AgentService: unknown subagent type '$subagentType', falling back to general-purpose")
        }
        val effectiveDef = agentDef ?: agents.find { it.agentType == "general-purpose" } ?: agents.last()

        val agentId = "subagent-${UUID.randomUUID().toString().take(8)}"
        val resolvedModel = if (modelOverride != null) modelOverride
            else effectiveDef.model.resolveModelId("gpt-4.1")

        val effectiveTools = effectiveDef.tools

        // --- Worktree isolation for forkContext agents ---
        val useWorktree = effectiveDef.forkContext
        var worktreeInfo: WorktreeInfo? = null
        var effectiveWorkspaceRoot = project.basePath ?: "/tmp"

        if (useWorktree) {
            try {
                val info = WorktreeManager.createWorktree(project.basePath ?: "/tmp", agentId)
                subagentWorktrees[agentId] = info
                worktreeInfo = info
                effectiveWorkspaceRoot = info.worktreePath
                log.info("AgentService: created worktree for $agentId at ${info.worktreePath}")
            } catch (e: Exception) {
                log.warn("AgentService: worktree creation failed for $agentId, falling back to shared workspace", e)
            }
        }

        // Auto-disable PSI tools for worktree agents (IntelliJ won't index the worktree directory)
        val extraDisallowed = if (worktreeInfo != null) {
            setOf("ide", "ide_search_text", "ide_find_usages", "ide_find_symbol",
                "ide_find_class", "ide_find_file", "ide_diagnostics", "ide_quick_doc",
                "ide_rename_symbol", "ide_safe_delete")
        } else emptySet()

        log.info("AgentService: spawning subagent [$agentId] type=$subagentType model=$resolvedModel worktree=$useWorktree (parallel)")
        _events.tryEmit(AgentEvent.SubagentSpawned(agentId, effectiveDef.agentType, description, prompt))

        val session = WorkerSession(
            workerId = agentId,
            role = effectiveDef.agentType,
            systemPrompt = effectiveDef.systemPromptTemplate,
            model = resolvedModel,
            agentMode = true,
            toolsEnabled = effectiveTools,
            projectName = project.name,
            workspaceRoot = effectiveWorkspaceRoot,
            lspClient = lspClient,
        )

        session.onEvent = { event ->
            when (event) {
                is WorkerEvent.Delta -> _events.tryEmit(AgentEvent.SubagentDelta(agentId, event.text))
                is WorkerEvent.ToolCall -> _events.tryEmit(AgentEvent.SubagentToolCall(agentId, event.toolName))
                is WorkerEvent.Done -> {} // handled in awaitPendingSubagents
                is WorkerEvent.Error -> _events.tryEmit(AgentEvent.SubagentCompleted(agentId, event.message, "error"))
            }
        }

        session.onConversationId = { convId ->
            val disallowed = effectiveDef.disallowedTools.toSet() + extraDisallowed
            subagentToolFilters[convId] = SubagentToolFilter(
                allowedTools = effectiveDef.tools?.toSet(),
                disallowedTools = disallowed,
            )
            // Register workspace override so ConversationManager routes tool calls to the worktree
            if (worktreeInfo != null) {
                conversationManager.registerWorkspaceOverride(convId, worktreeInfo.worktreePath)
            }
            log.info("AgentService: registered tool filter for subagent $agentId (convId=$convId, allowed=${effectiveDef.tools}, disallowed=$disallowed, worktree=${worktreeInfo != null})")
        }

        activeSubagents[agentId] = session

        // Launch subagent in background — don't block the tool call response
        val deferred = scope.async {
            try {
                val result = session.executeTask(prompt)
                if (result.isBlank()) {
                    log.info("AgentService: subagent $agentId returned empty — sending follow-up turn")
                    session.executeTask("Please provide a text summary of your findings and results.")
                } else {
                    result
                }
            } catch (e: Exception) {
                log.error("AgentService: subagent $agentId failed", e)
                throw e
            } finally {
                activeSubagents.remove(agentId)
            }
        }

        pendingSubagents[agentId] = PendingSubagent(agentId, effectiveDef.agentType, description, deferred)

        // Respond immediately so the server can dispatch the next tool call
        val toolResult = buildJsonArray {
            addJsonObject {
                putJsonArray("content") {
                    addJsonObject {
                        put("value", "Subagent $agentId (${effectiveDef.agentType}) spawned for: $description. Running in parallel — results will be collected automatically.")
                    }
                }
                put("status", "success")
            }
            add(JsonNull)
        }
        lspClient.sendResponse(id, toolResult)
        log.info("AgentService: delegate_task responded immediately for $agentId, subagent running in background")
    }

    /**
     * Wait for all pending subagents to complete and collect their results.
     * Emits SubagentCompleted events for each.
     * Returns a formatted string with all results for the follow-up turn.
     */
    private suspend fun awaitPendingSubagents(): String {
        val results = mutableListOf<Triple<String, String, String>>() // agentId, agentType, result

        for ((agentId, pending) in pendingSubagents) {
            try {
                log.info("AgentService: awaiting subagent $agentId (${pending.agentType})")
                val result = pending.deferred.await()
                if (result.isBlank()) {
                    val emptyMsg = "Error: subagent produced no output"
                    results.add(Triple(agentId, pending.agentType, emptyMsg))
                    _events.emit(AgentEvent.SubagentCompleted(agentId, emptyMsg, "error"))
                    log.warn("AgentService: subagent $agentId completed with EMPTY result")
                } else {
                    results.add(Triple(agentId, pending.agentType, result))
                    _events.emit(AgentEvent.SubagentCompleted(agentId, result, "success"))
                    log.info("AgentService: subagent $agentId completed (${result.length} chars)")
                }
            } catch (e: Exception) {
                val errorMsg = "Error: ${e.message}"
                results.add(Triple(agentId, pending.agentType, errorMsg))
                _events.emit(AgentEvent.SubagentCompleted(agentId, e.message ?: "Error", "error"))
                log.warn("AgentService: subagent $agentId failed: ${e.message}")
            }
        }
        pendingSubagents.clear()

        // Generate diffs for worktree-isolated subagents and emit review events
        val mainWorkspace = project.basePath ?: "/tmp"
        for ((agentId, worktreeInfo) in subagentWorktrees) {
            try {
                val changes = WorktreeManager.generateDiff(worktreeInfo, mainWorkspace)
                if (changes.isNotEmpty()) {
                    _events.emit(AgentEvent.WorktreeChangesReady(agentId, changes))
                    log.info("AgentService: worktree $agentId has ${changes.size} changed file(s) pending review")
                } else {
                    // No changes — clean up immediately
                    WorktreeManager.removeWorktree(worktreeInfo, mainWorkspace)
                    subagentWorktrees.remove(agentId)
                    log.info("AgentService: worktree $agentId had no changes, cleaned up")
                }
            } catch (e: Exception) {
                log.warn("AgentService: failed to generate diff for worktree $agentId: ${e.message}")
            }
        }

        // Build formatted results for the follow-up turn
        return results.joinToString("\n\n") { (agentId, agentType, result) ->
            "<subagent_result agent_type=\"$agentType\" agent_id=\"$agentId\">\n$result\n</subagent_result>"
        }
    }

    /**
     * Apply worktree changes to the main workspace and clean up the worktree.
     * Called when the user approves changes from a worktree-isolated subagent.
     */
    fun approveWorktreeChanges(agentId: String) {
        val info = subagentWorktrees.remove(agentId) ?: return
        val mainWorkspace = project.basePath ?: return

        val changes = WorktreeManager.generateDiff(info, mainWorkspace)
        val ws = WorkingSetService.getInstance(project)

        // Track changes in WorkingSetService so they appear in the Working Set panel
        for (change in changes) {
            val absPath = java.io.File(mainWorkspace, change.relativePath).absolutePath
            ws.captureBeforeState("worktree-apply", absPath)
        }

        WorktreeManager.applyChanges(changes, mainWorkspace)

        for (change in changes) {
            val absPath = java.io.File(mainWorkspace, change.relativePath).absolutePath
            ws.captureAfterState(absPath)
        }

        // Refresh VFS so IntelliJ sees the new files
        val lfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        for (change in changes) {
            val absPath = java.io.File(mainWorkspace, change.relativePath).absolutePath
            lfs.refreshAndFindFileByPath(absPath)
        }

        WorktreeManager.removeWorktree(info, mainWorkspace)
        log.info("AgentService: approved and applied ${changes.size} worktree change(s) for $agentId")
    }

    /**
     * Discard worktree changes and clean up.
     * Called when the user rejects changes from a worktree-isolated subagent.
     */
    fun rejectWorktreeChanges(agentId: String) {
        val info = subagentWorktrees.remove(agentId) ?: return
        val mainWorkspace = project.basePath ?: "/tmp"
        WorktreeManager.removeWorktree(info, mainWorkspace)
        log.info("AgentService: rejected and discarded worktree changes for $agentId")
    }

    /**
     * Send a follow-up turn to the lead conversation with collected subagent results.
     */
    private suspend fun sendFollowUpTurn(
        workDoneToken: String,
        resultContext: String,
        model: String,
        rootUri: String,
    ) {
        val message = "Subagent results from the previous round:\n\n" +
            "$resultContext\n\n" +
            "Review these results. If the task requires additional work — follow-up research, " +
            "dependent subtasks, or verification — delegate those now using delegate_task. " +
            "If all work is complete, synthesize the results into a final answer for the user."

        val params = buildJsonObject {
            put("workDoneToken", workDoneToken)
            put("conversationId", leadConversationId!!)
            put("message", message)
            put("source", "panel")
            put("chatMode", "Agent")
            put("needToolCallConfirmation", true)
            if (model.isNotBlank()) put("model", model)
            put("workspaceFolder", rootUri)
            putJsonArray("workspaceFolders") {
                addJsonObject {
                    put("uri", rootUri)
                    put("name", project.name)
                }
            }
        }

        log.info("AgentService: sending follow-up turn with ${pendingSubagents.size} results to lead conversation")
        lspClient.sendRequest("conversation/turn", params, timeoutMs = 300_000)
    }

    /**
     * Handle progress events from the lead agent's conversation.
     *
     * IMPORTANT: uses tryEmit() (non-suspending) instead of emit() to avoid
     * blocking the Dispatchers.Default thread pool. If coroutines here block
     * on emit(), they starve threads needed by the tool call handler in
     * ConversationManager, deadlocking the conversation. Dropped UI events
     * are acceptable since LeadDone carries the full accumulated text.
     */
    private fun handleLeadProgress(value: JsonObject, replyParts: MutableList<String>) {
        val kind = value["kind"]?.jsonPrimitive?.contentOrNull

        if (kind == "end") {
            isStreaming = false
            return
        }

        val reply = value["reply"]?.jsonPrimitive?.contentOrNull
        if (reply != null) {
            replyParts.add(reply)
            _events.tryEmit(AgentEvent.LeadDelta(reply))
        }

        val delta = value["delta"]?.jsonPrimitive?.contentOrNull
        if (delta != null) {
            replyParts.add(delta)
            _events.tryEmit(AgentEvent.LeadDelta(delta))
        }

        val message = value["message"]?.jsonPrimitive?.contentOrNull
        if (message != null && kind != "begin") {
            replyParts.add(message)
            _events.tryEmit(AgentEvent.LeadDelta(message))
        }

        // Agent rounds
        val rounds = value["editAgentRounds"]?.jsonArray
        rounds?.forEach { roundEl ->
            val round = roundEl.jsonObject
            val roundReply = round["reply"]?.jsonPrimitive?.contentOrNull ?: ""
            if (roundReply.isNotEmpty()) {
                replyParts.add(roundReply)
                _events.tryEmit(AgentEvent.LeadDelta(roundReply))
            }

            val toolCalls = round["toolCalls"]?.jsonArray
            toolCalls?.forEach { toolCallEl ->
                val tc = toolCallEl.jsonObject
                val name = tc["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val tcInput = tc["input"]?.jsonObject ?: JsonObject(emptyMap())
                val status = tc["status"]?.jsonPrimitive?.contentOrNull ?: ""

                // Skip delegation tool calls in the lead progress — shown as subagent events instead.
                // This includes both direct delegate_task calls and run_in_terminal calls
                // that carry delegation commands (command starts with "delegate ").
                val isDelegation = name == "delegate_task" || (name == "run_in_terminal" &&
                    tcInput["command"]?.jsonPrimitive?.contentOrNull?.trimStart()?.startsWith("delegate ") == true)

                if (!isDelegation) {
                    _events.tryEmit(AgentEvent.LeadToolCall(name, tcInput))
                    if (status == "completed" || status == "error") {
                        val resultData = tc["result"]?.jsonArray
                        val resultText = resultData?.firstOrNull()?.jsonObject
                            ?.get("content")?.jsonPrimitive?.contentOrNull
                            ?: status
                        _events.tryEmit(AgentEvent.LeadToolResult(name, resultText.take(200)))
                    }
                }
            }
        }
    }

    /** Whether the agent service has an active lead conversation. */
    fun isActive(): Boolean = leadConversationId != null || pendingLeadCreate

    /**
     * Check if this service owns the given conversationId.
     * Only the lead conversation is owned — subagent tool calls fall through
     * to standard ToolRouter routing (following the Orchestrator pattern).
     *
     * Race condition fix: The server sends tool calls BEFORE conversation/create
     * returns the conversationId. When pendingLeadCreate is true, we capture the
     * conversationId from the first arriving tool call.
     */
    fun ownsConversation(conversationId: String?): Boolean {
        if (conversationId == null) return false
        if (conversationId == leadConversationId) return true
        // Tool calls arrive before conversation/create returns — claim this id
        if (pendingLeadCreate && leadConversationId == null) {
            leadConversationId = conversationId
            log.info("AgentService: captured lead conversationId=$conversationId from early tool call")
            return true
        }
        return false
    }

    /**
     * Check if a tool is allowed for the given conversation based on registered filters.
     * Returns true if no filter is registered (e.g. lead conversation or unknown conversation).
     */
    fun isToolAllowedForConversation(conversationId: String?, toolName: String): Boolean {
        if (conversationId == null) return true
        val filter = subagentToolFilters[conversationId] ?: return true

        // Blocklist check
        if (toolName in filter.disallowedTools) {
            log.warn("Tool blocked for conversation $conversationId: '$toolName' is in disallowedTools")
            return false
        }

        // Allowlist check (null = all allowed)
        val allowed = filter.allowedTools
        if (allowed != null && toolName !in allowed) {
            log.warn("Tool blocked for conversation $conversationId: '$toolName' is not in allowedTools $allowed")
            return false
        }

        return true
    }

    /** Cancel the current streaming response and all subagents. */
    fun cancel() {
        val token = currentWorkDoneToken
        if (token != null && lspClient.isRunning) {
            lspClient.sendNotification(
                "window/workDoneProgress/cancel",
                buildJsonObject { put("token", token) }
            )
            lspClient.removeProgressListener(token)
            currentWorkDoneToken = null
        }

        currentJob?.cancel()
        currentJob = null
        activeSubagents.values.forEach { it.cancel() }
        activeSubagents.clear()
        pendingSubagents.values.forEach { it.deferred.cancel() }
        pendingSubagents.clear()
        subagentToolFilters.clear()

        // Clean up any active worktrees
        val mainWorkspace = project.basePath ?: "/tmp"
        for ((_, info) in subagentWorktrees) {
            try {
                WorktreeManager.removeWorktree(info, mainWorkspace)
            } catch (e: Exception) {
                log.warn("AgentService: failed to clean up worktree ${info.agentId}: ${e.message}")
            }
        }
        subagentWorktrees.clear()

        isStreaming = false

        scope.launch { _events.emit(AgentEvent.LeadDone()) }
    }

    /** Start a fresh conversation. */
    fun newConversation() {
        cancel()
        leadConversationId = null
        pendingLeadCreate = false
        agents = emptyList()
    }

    override fun dispose() {
        cancel()
        scope.cancel()
    }

    /** Tracks a background subagent launched by delegate_task. */
    private data class PendingSubagent(
        val agentId: String,
        val agentType: String,
        val description: String,
        val deferred: Deferred<String>,
    )

    /** Hard-enforced tool filter for a subagent conversation. */
    private data class SubagentToolFilter(
        val allowedTools: Set<String>?,   // null = all allowed
        val disallowedTools: Set<String>,
    )
}
