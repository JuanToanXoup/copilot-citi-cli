package com.citigroup.copilotchat.agent

import com.citigroup.copilotchat.conversation.ConversationManager
import com.citigroup.copilotchat.lsp.CachedAuth
import com.citigroup.copilotchat.lsp.LspClient
import com.citigroup.copilotchat.lsp.LspClientFactory
import com.citigroup.copilotchat.lsp.LspClientPool
import com.citigroup.copilotchat.lsp.ManagedClient
import com.citigroup.copilotchat.lsp.ToolSetKey
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
 * and cancellation.
 *
 * Extracted from [AgentService] to separate subagent concerns from lead
 * conversation orchestration.
 */
class SubagentManager(
    private val project: Project,
    private val scope: CoroutineScope,
    private val eventBus: AgentEventBus,
    private val pool: LspClientPool,
    private val leadClient: LspClient,
    private val conversationManager: ConversationManager,
    private val cachedAuth: CachedAuth,
    private val clientFactory: LspClientFactory = LspClientFactory.getInstance(project),
) {

    private val log = Logger.getInstance(SubagentManager::class.java)

    private val activeSubagents = ConcurrentHashMap<String, WorkerSession>()

    /** Background subagent jobs launched by delegate_task. Collected after lead turn ends. */
    private val pendingSubagents = ConcurrentHashMap<String, PendingSubagent>()

    /** Worktree metadata for subagents with forkContext=true. Keyed by agentId. */
    private val subagentWorktrees = ConcurrentHashMap<String, WorktreeInfo>()

    /** Start times for duration tracking. Keyed by agentId. */
    private val subagentStartTimes = ConcurrentHashMap<String, Long>()

    /** Managed client handles for proper release. Keyed by agentId. */
    private val managedClients = ConcurrentHashMap<String, ManagedClient>()

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
        val waitForResult = input["wait_for_result"]?.jsonPrimitive?.booleanOrNull ?: false
        val timeoutSeconds = input["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 300

        val agentDef = AgentRegistry.findByType(subagentType, agents)
        if (agentDef == null) {
            val available = agents.map { it.agentType }
            log.warn("SubagentManager: unknown subagent type '$subagentType', falling back to general-purpose. Available: $available")
        }
        val effectiveDef = agentDef ?: agents.find { it.agentType == "general-purpose" } ?: agents.last()

        val agentId = "subagent-${UUID.randomUUID().toString().take(8)}"
        val resolvedModel = modelOverride ?: effectiveDef.model.resolveModelId("gpt-4.1")

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

        val mode = if (waitForResult) "sequential" else "parallel"
        log.info("SubagentManager: spawning subagent [$agentId] type=$subagentType model=$resolvedModel worktree=$useWorktree mode=$mode timeout=${timeoutSeconds}s")
        subagentStartTimes[agentId] = System.currentTimeMillis()
        // Use emit (suspending, guaranteed delivery) for structural events — tryEmit could
        // drop the Spawned event, leaving the UI with an orphan Completed and no panel.
        eventBus.emit(SubagentEvent.Spawned(agentId, effectiveDef.agentType, description, prompt))

        // Compute final allowed tools: empty set = unrestricted (per AgentDefinition contract).
        // For worktree agents, remove PSI tools since IntelliJ won't index the worktree.
        // Guard: if subtraction would empty the set, keep the original tools — the agent
        // intended to have restrictions, not become unrestricted.
        val allowedTools = if (effectiveDef.hasUnrestrictedTools) {
            emptySet()
        } else {
            val subtracted = effectiveDef.tools.toSet() - extraDisallowed
            if (subtracted.isEmpty()) effectiveDef.tools.toSet() else subtracted
        }

        // Build an effective definition with worktree-adjusted tools for the factory
        val effectiveForFactory = if (allowedTools != effectiveDef.tools.toSet()) {
            effectiveDef.copy(tools = allowedTools.toList())
        } else {
            effectiveDef
        }

        // Acquire client via factory — handles pool vs standalone (for MCP agents)
        val managed = try {
            clientFactory.acquireForAgent(effectiveForFactory)
        } catch (e: Exception) {
            log.warn("SubagentManager: client acquisition failed for $agentId, falling back to default client", e)
            ManagedClient(pool.default, isStandalone = false, clientId = null, tools = emptySet())
        }
        managedClients[agentId] = managed
        val subagentClient = managed.client

        val session = WorkerSession(
            workerId = agentId,
            role = effectiveDef.agentType,
            systemPrompt = effectiveDef.systemPromptTemplate,
            model = resolvedModel,
            agentMode = true,
            toolsEnabled = allowedTools.toList(),
            projectName = project.name,
            workspaceRoot = effectiveWorkspaceRoot,
            lspClient = subagentClient,
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

        // Register tool filter and workspace override when conversationId is captured
        // Register workspace override when conversationId is captured (for worktree agents)
        if (worktreeInfo != null) {
            session.onConversationId = { convId ->
                conversationManager.registerWorkspaceOverride(convId, worktreeInfo.worktreePath)
                log.info("SubagentManager: registered workspace override for $agentId (convId=$convId, worktree=${worktreeInfo.worktreePath})")
            }
        }

        activeSubagents[agentId] = session

        // Launch subagent in background
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

        if (waitForResult) {
            // Sequential mode: await result and return it as the tool response
            val timeoutMs = timeoutSeconds * 1000L
            try {
                val result = withTimeout(timeoutMs) { deferred.await() }
                val durationMs = System.currentTimeMillis() - (subagentStartTimes.remove(agentId) ?: 0)
                eventBus.emit(SubagentEvent.Completed(agentId, result, "success", durationMs))
                log.info("SubagentManager: subagent $agentId completed synchronously (${result.length} chars, ${durationMs}ms)")

                val toolResult = buildJsonArray {
                    addJsonObject {
                        putJsonArray("content") {
                            addJsonObject { put("value", result) }
                        }
                        put("status", "success")
                    }
                    add(JsonNull)
                }
                leadClient.sendResponse(id, toolResult)
            } catch (e: TimeoutCancellationException) {
                deferred.cancel()
                val durationMs = System.currentTimeMillis() - (subagentStartTimes.remove(agentId) ?: 0)
                val errorMsg = "Subagent $agentId timed out after ${timeoutSeconds}s"
                eventBus.tryEmit(SubagentEvent.Completed(agentId, errorMsg, "error", durationMs))
                log.warn("SubagentManager: $errorMsg")

                val toolResult = buildJsonArray {
                    addJsonObject {
                        putJsonArray("content") {
                            addJsonObject { put("value", errorMsg) }
                        }
                        put("status", "error")
                    }
                    add(JsonNull)
                }
                leadClient.sendResponse(id, toolResult)
            } catch (e: Exception) {
                val durationMs = System.currentTimeMillis() - (subagentStartTimes.remove(agentId) ?: 0)
                val errorMsg = "Subagent $agentId failed: ${e.message}"
                eventBus.tryEmit(SubagentEvent.Completed(agentId, errorMsg, "error", durationMs))
                log.error("SubagentManager: $errorMsg", e)

                val toolResult = buildJsonArray {
                    addJsonObject {
                        putJsonArray("content") {
                            addJsonObject { put("value", errorMsg) }
                        }
                        put("status", "error")
                    }
                    add(JsonNull)
                }
                leadClient.sendResponse(id, toolResult)
            } finally {
                // Release client for sequential subagent
                managedClients.remove(agentId)?.let { clientFactory.release(it) }
            }
        } else {
            // Parallel mode: respond immediately, collect results later
            pendingSubagents[agentId] = PendingSubagent(agentId, effectiveDef.agentType, description, deferred, effectiveDef, allowedTools)

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
            leadClient.sendResponse(id, toolResult)
            log.info("SubagentManager: delegate_task responded immediately for $agentId, subagent running in background")
        }
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
                val durationMs = System.currentTimeMillis() - (subagentStartTimes.remove(agentId) ?: 0)
                if (result.isBlank()) {
                    val emptyMsg = "Error: subagent produced no output"
                    results.add(Triple(agentId, pending.agentType, emptyMsg))
                    eventBus.emit(SubagentEvent.Completed(agentId, emptyMsg, "error", durationMs))
                    log.error("SubagentManager: subagent $agentId completed with EMPTY result (${durationMs}ms)")
                } else {
                    results.add(Triple(agentId, pending.agentType, result))
                    eventBus.emit(SubagentEvent.Completed(agentId, result, "success", durationMs))
                    log.info("SubagentManager: subagent $agentId completed (${result.length} chars, ${durationMs}ms)")
                }
            } catch (e: Exception) {
                val durationMs = System.currentTimeMillis() - (subagentStartTimes.remove(agentId) ?: 0)
                val errorMsg = "Error: ${e.message}"
                results.add(Triple(agentId, pending.agentType, errorMsg))
                eventBus.emit(SubagentEvent.Completed(agentId, e.message ?: "Error", "error", durationMs))
                log.error("SubagentManager: subagent $agentId failed: ${e.message} (${durationMs}ms)", e)
            } finally {
                // Release client via factory (handles pool vs standalone)
                managedClients.remove(agentId)?.let { clientFactory.release(it) }
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

    /** Cancel all active and pending subagents, release clients, and clean up worktrees. */
    fun cancelAll() {
        activeSubagents.values.forEach { it.cancel() }
        activeSubagents.clear()
        // Release clients before clearing
        for ((_, pending) in pendingSubagents) {
            pending.deferred.cancel()
        }
        pendingSubagents.clear()
        // Release all managed clients via factory
        for ((_, managed) in managedClients) {
            clientFactory.release(managed)
        }
        managedClients.clear()
        subagentStartTimes.clear()

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
        val definition: AgentDefinition,
        val allowedTools: Set<String> = emptySet(),
    )
}
