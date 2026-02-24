package com.citigroup.copilotchat.agent

import com.citigroup.copilotchat.conversation.ConversationManager
import com.citigroup.copilotchat.lsp.LspClient
import com.citigroup.copilotchat.orchestrator.WorkerSession
import com.citigroup.copilotchat.orchestrator.WorkerEvent
import com.citigroup.copilotchat.workingset.WorkingSetService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages subagent lifecycle: spawning, awaiting results, worktree isolation,
 * tool filtering, and cancellation.
 *
 * Extracted from [AgentService] to separate subagent concerns from lead
 * conversation orchestration.
 */
class SubagentManager(
    private val project: Project,
    private val scope: CoroutineScope,
    private val eventBus: AgentEventBus,
    private val lspClient: LspClient,
    private val conversationManager: ConversationManager,
) {

    private val log = Logger.getInstance(SubagentManager::class.java)

    private val activeSubagents = ConcurrentHashMap<String, WorkerSession>()

    /** Background subagent jobs launched by delegate_task. Collected after lead turn ends. */
    private val pendingSubagents = ConcurrentHashMap<String, PendingSubagent>()

    /** Hard-enforced tool filters keyed by subagent conversationId. */
    private val subagentToolFilters = ConcurrentHashMap<String, SubagentToolFilter>()

    /** Worktree metadata for subagents with forkContext=true. Keyed by agentId. */
    private val subagentWorktrees = ConcurrentHashMap<String, WorktreeInfo>()

    /** Whether there are subagents still running. */
    fun hasPending(): Boolean = pendingSubagents.isNotEmpty()

    /**
     * Spawn a subagent in the background and respond immediately so the
     * server can dispatch the next tool call. Results are collected later
     * via [awaitAll].
     */
    suspend fun spawnSubagent(
        id: Int,
        input: JsonObject,
        agents: List<AgentDefinition>,
    ) {
        val description = input["description"]?.jsonPrimitive?.contentOrNull ?: "subtask"
        val prompt = input["prompt"]?.jsonPrimitive?.contentOrNull ?: ""
        val subagentType = input["subagent_type"]?.jsonPrimitive?.contentOrNull ?: "general-purpose"
        val modelOverride = input["model"]?.jsonPrimitive?.contentOrNull

        val agentDef = AgentRegistry.findByType(subagentType, agents)
        if (agentDef == null) {
            log.warn("SubagentManager: unknown subagent type '$subagentType', falling back to general-purpose")
        }
        val effectiveDef = agentDef ?: agents.find { it.agentType == "general-purpose" } ?: agents.last()

        val agentId = "subagent-${UUID.randomUUID().toString().take(8)}"
        val resolvedModel = modelOverride ?: effectiveDef.model.resolveModelId("gpt-4.1")

        val effectiveTools = effectiveDef.tools

        // --- Worktree isolation for forkContext agents ---
        val useWorktree = effectiveDef.forkContext
        var worktreeInfo: WorktreeInfo? = null
        var effectiveWorkspaceRoot = project.basePath ?: "/tmp"

        if (useWorktree) {
            try {
                val info = withContext(Dispatchers.IO) {
                    WorktreeManager.createWorktree(project.basePath ?: "/tmp", agentId)
                }
                subagentWorktrees[agentId] = info
                worktreeInfo = info
                effectiveWorkspaceRoot = info.worktreePath
                log.info("SubagentManager: created worktree for $agentId at ${info.worktreePath}")
            } catch (e: Exception) {
                log.warn("SubagentManager: worktree creation failed for $agentId, falling back to shared workspace", e)
            }
        }

        // Auto-disable PSI tools for worktree agents (IntelliJ won't index the worktree directory)
        val extraDisallowed = if (worktreeInfo != null) {
            setOf("ide", "ide_search_text", "ide_find_usages", "ide_find_symbol",
                "ide_find_class", "ide_find_file", "ide_diagnostics", "ide_quick_doc",
                "ide_rename_symbol", "ide_safe_delete")
        } else emptySet()

        log.info("SubagentManager: spawning subagent [$agentId] type=$subagentType model=$resolvedModel worktree=$useWorktree (parallel)")
        eventBus.tryEmit(SubagentEvent.Spawned(agentId, effectiveDef.agentType, description, prompt))

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

        // Non-suspend callback — must use tryEmit
        session.onEvent = { event ->
            when (event) {
                is WorkerEvent.Delta -> eventBus.tryEmit(SubagentEvent.Delta(agentId, event.text))
                is WorkerEvent.ToolCall -> eventBus.tryEmit(SubagentEvent.ToolCall(agentId, event.toolName))
                is WorkerEvent.Done -> {} // handled in awaitAll
                is WorkerEvent.Error -> eventBus.tryEmit(SubagentEvent.Completed(agentId, event.message, "error"))
            }
        }

        session.onConversationId = { convId ->
            val allowedTools = effectiveDef.tools.toSet() - extraDisallowed
            subagentToolFilters[convId] = SubagentToolFilter(
                allowedTools = allowedTools,
            )
            // Register workspace override so ConversationManager routes tool calls to the worktree
            if (worktreeInfo != null) {
                conversationManager.registerWorkspaceOverride(convId, worktreeInfo.worktreePath)
            }
            log.info("SubagentManager: registered tool filter for subagent $agentId (convId=$convId, allowed=$allowedTools, worktree=${worktreeInfo != null})")
        }

        activeSubagents[agentId] = session

        // Launch subagent in background — don't block the tool call response
        val deferred = scope.async {
            try {
                val result = session.executeTask(prompt)
                if (result.isBlank()) {
                    log.info("SubagentManager: subagent $agentId returned empty — sending follow-up turn")
                    session.executeTask("Please provide a text summary of your findings and results.")
                } else {
                    result
                }
            } catch (e: Exception) {
                log.error("SubagentManager: subagent $agentId failed", e)
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
        log.info("SubagentManager: delegate_task responded immediately for $agentId, subagent running in background")
    }

    /**
     * Wait for all pending subagents to complete and collect their results.
     * Emits SubagentCompleted events for each.
     * Returns a formatted string with all results for the follow-up turn.
     */
    suspend fun awaitAll(): String {
        val results = mutableListOf<Triple<String, String, String>>() // agentId, agentType, result

        for ((agentId, pending) in pendingSubagents) {
            try {
                log.info("SubagentManager: awaiting subagent $agentId (${pending.agentType})")
                val result = pending.deferred.await()
                if (result.isBlank()) {
                    val emptyMsg = "Error: subagent produced no output"
                    results.add(Triple(agentId, pending.agentType, emptyMsg))
                    eventBus.emit(SubagentEvent.Completed(agentId, emptyMsg, "error"))
                    log.error("SubagentManager: subagent $agentId completed with EMPTY result")
                } else {
                    results.add(Triple(agentId, pending.agentType, result))
                    eventBus.emit(SubagentEvent.Completed(agentId, result, "success"))
                    log.info("SubagentManager: subagent $agentId completed (${result.length} chars)")
                }
            } catch (e: Exception) {
                val errorMsg = "Error: ${e.message}"
                results.add(Triple(agentId, pending.agentType, errorMsg))
                eventBus.emit(SubagentEvent.Completed(agentId, e.message ?: "Error", "error"))
                log.error("SubagentManager: subagent $agentId failed: ${e.message}", e)
            }
        }
        pendingSubagents.clear()

        // Generate diffs for worktree-isolated subagents and emit review events
        val mainWorkspace = project.basePath ?: "/tmp"
        for ((agentId, worktreeInfo) in subagentWorktrees) {
            try {
                val changes = withContext(Dispatchers.IO) {
                    WorktreeManager.generateDiff(worktreeInfo, mainWorkspace)
                }
                if (changes.isNotEmpty()) {
                    eventBus.emit(SubagentEvent.WorktreeChangesReady(agentId, changes))
                    log.info("SubagentManager: worktree $agentId has ${changes.size} changed file(s) pending review")
                } else {
                    // No changes — clean up immediately
                    withContext(Dispatchers.IO) {
                        WorktreeManager.removeWorktree(worktreeInfo, mainWorkspace)
                    }
                    subagentWorktrees.remove(agentId)
                    log.info("SubagentManager: worktree $agentId had no changes, cleaned up")
                }
            } catch (e: Exception) {
                log.warn("SubagentManager: failed to generate diff for worktree $agentId: ${e.message}")
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
        log.info("SubagentManager: approved and applied ${changes.size} worktree change(s) for $agentId")
    }

    /**
     * Discard worktree changes and clean up.
     * Called when the user rejects changes from a worktree-isolated subagent.
     */
    fun rejectWorktreeChanges(agentId: String) {
        val info = subagentWorktrees.remove(agentId) ?: return
        val mainWorkspace = project.basePath ?: "/tmp"
        WorktreeManager.removeWorktree(info, mainWorkspace)
        log.info("SubagentManager: rejected and discarded worktree changes for $agentId")
    }

    /**
     * Check if a tool is allowed for the given conversation based on registered filters.
     * Returns true if no filter is registered (e.g. lead conversation or unknown conversation).
     */
    fun isToolAllowed(conversationId: String?, toolName: String): Boolean {
        if (conversationId == null) return true
        val filter = subagentToolFilters[conversationId] ?: return true

        if (toolName !in filter.allowedTools) {
            log.warn("Tool blocked for conversation $conversationId: '$toolName' not in allowedTools ${filter.allowedTools}")
            return false
        }

        return true
    }

    /** Cancel all active and pending subagents, clean up worktrees. */
    fun cancelAll() {
        activeSubagents.values.forEach { it.cancel() }
        activeSubagents.clear()
        pendingSubagents.values.forEach { it.deferred.cancel() }
        pendingSubagents.clear()
        subagentToolFilters.clear()

        // Clean up any active worktrees on IO thread
        val mainWorkspace = project.basePath ?: "/tmp"
        val worktreesToClean = subagentWorktrees.values.toList()
        subagentWorktrees.clear()
        if (worktreesToClean.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                for (info in worktreesToClean) {
                    try {
                        WorktreeManager.removeWorktree(info, mainWorkspace)
                    } catch (e: Exception) {
                        log.warn("SubagentManager: failed to clean up worktree ${info.agentId}: ${e.message}")
                    }
                }
            }
        }
    }

    /** Tracks a background subagent launched by delegate_task. */
    private data class PendingSubagent(
        val agentId: String,
        val agentType: String,
        val description: String,
        val deferred: Deferred<String>,
    )

    /** Hard-enforced tool filter for a subagent conversation (from agent.md tools field). */
    private data class SubagentToolFilter(
        val allowedTools: Set<String>,
    )
}
