package com.citigroup.copilotchat.agent

import com.citigroup.copilotchat.conversation.ConversationManager
import com.citigroup.copilotchat.lsp.LspClient
import com.citigroup.copilotchat.tools.ToolRouter
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

/**
 * Core service for the Agent tab. Manages the lead agent conversation
 * and routes tool calls. Subagent lifecycle is delegated to [SubagentManager].
 *
 * Data flow:
 * 1. User sends message -> lead agent conversation created/continued
 * 2. Lead model calls delegate_task -> SubagentManager spawns subagent in background
 * 3. Lead turn ends -> service waits for all subagents to complete
 * 4. Results collected -> follow-up turn sent to lead with all results
 * 5. Lead model synthesizes final answer
 */
@Service(Service.Level.PROJECT)
class AgentService(private val project: Project) : AgentEventBus, Disposable {

    private val log = Logger.getInstance(AgentService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 512)
    override val events: SharedFlow<AgentEvent> = _events

    override suspend fun emit(event: AgentEvent) { _events.emit(event) }
    override fun tryEmit(event: AgentEvent): Boolean = _events.tryEmit(event)

    private val lspClient: LspClient get() = LspClient.getInstance(project)
    private val conversationManager: ConversationManager get() = ConversationManager.getInstance(project)

    private val subagentManager by lazy {
        SubagentManager(project, scope, this, lspClient, conversationManager)
    }

    @Volatile
    private var leadConversationId: String? = null
    /** True between conversation/create call and its response — tool calls that arrive
     *  during this window carry the conversationId we need but don't have yet. */
    @Volatile
    private var pendingLeadCreate: Boolean = false
    var isStreaming: Boolean = false
        private set

    private var agents: List<AgentDefinition> = emptyList()

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
                    ?: leadAgent?.model?.resolveModelId("gpt-4.1")
                    ?: "gpt-4.1"
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
                            if (subagentManager.hasPending()) {
                                // Lead turn ended but subagents are still running.
                                // Wait for all, collect results, and send a follow-up turn.
                                log.info("AgentService: lead turn ended with pending subagents — collecting results")
                                lspClient.removeProgressListener(workDoneToken)

                                val resultContext = subagentManager.awaitAll()

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
                _events.emit(LeadEvent.Done(fullReply))

            } catch (e: CancellationException) {
                isStreaming = false
                throw e
            } catch (e: Exception) {
                log.error("Error in AgentService.sendMessage", e)
                isStreaming = false
                _events.emit(LeadEvent.Error(e.message ?: "Unknown error"))
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
                subagentManager.spawnSubagent(id, delegateInput, agents)
                return
            }
        }

        when (name) {
            "delegate_task" -> subagentManager.spawnSubagent(id, input, agents)
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

        log.info("AgentService: sending follow-up turn with results to lead conversation")
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
            _events.tryEmit(LeadEvent.Delta(reply))
        }

        val delta = value["delta"]?.jsonPrimitive?.contentOrNull
        if (delta != null) {
            replyParts.add(delta)
            _events.tryEmit(LeadEvent.Delta(delta))
        }

        val message = value["message"]?.jsonPrimitive?.contentOrNull
        if (message != null && kind != "begin") {
            replyParts.add(message)
            _events.tryEmit(LeadEvent.Delta(message))
        }

        // Agent rounds
        val rounds = value["editAgentRounds"]?.jsonArray
        rounds?.forEach { roundEl ->
            val round = roundEl.jsonObject
            val roundReply = round["reply"]?.jsonPrimitive?.contentOrNull ?: ""
            if (roundReply.isNotEmpty()) {
                replyParts.add(roundReply)
                _events.tryEmit(LeadEvent.Delta(roundReply))
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
                    _events.tryEmit(LeadEvent.ToolCall(name, tcInput))
                    if (status == "completed" || status == "error") {
                        val resultData = tc["result"]?.jsonArray
                        val resultText = resultData?.firstOrNull()?.jsonObject
                            ?.get("content")?.jsonPrimitive?.contentOrNull
                            ?: status
                        _events.tryEmit(LeadEvent.ToolResult(name, resultText.take(200)))
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

    /** Delegate to [SubagentManager.isToolAllowed]. */
    fun isToolAllowedForConversation(conversationId: String?, toolName: String): Boolean =
        subagentManager.isToolAllowed(conversationId, toolName)

    /** Delegate to [SubagentManager.approveWorktreeChanges]. */
    fun approveWorktreeChanges(agentId: String) = subagentManager.approveWorktreeChanges(agentId)

    /** Delegate to [SubagentManager.rejectWorktreeChanges]. */
    fun rejectWorktreeChanges(agentId: String) = subagentManager.rejectWorktreeChanges(agentId)

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
        subagentManager.cancelAll()

        isStreaming = false

        scope.launch { _events.emit(LeadEvent.Done()) }
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
}
