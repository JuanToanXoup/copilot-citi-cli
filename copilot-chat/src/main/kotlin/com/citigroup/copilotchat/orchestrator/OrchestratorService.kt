package com.citigroup.copilotchat.orchestrator

import com.citigroup.copilotchat.config.CopilotChatSettings
import com.citigroup.copilotchat.conversation.ConversationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.*
import java.util.*

/**
 * Project-level orchestrator service.
 * Decomposes goals into tasks via LLM planning, executes them in parallel
 * across WorkerSessions using DAG-based dependency ordering, then synthesizes results.
 *
 * Port of Python Orchestrator class (queue transport mode).
 */
@Service(Service.Level.PROJECT)
class OrchestratorService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(OrchestratorService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<OrchestratorEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<OrchestratorEvent> = _events

    @Volatile
    var isRunning = false
        private set

    private var orchestrationJob: Job? = null
    private val sessions = Collections.synchronizedMap(mutableMapOf<String, WorkerSession>())

    companion object {
        fun getInstance(project: Project): OrchestratorService =
            project.getService(OrchestratorService::class.java)

        private const val PLANNING_SYSTEM_PROMPT = """You are an orchestrator agent. Your job is to break down complex tasks into discrete subtasks and assign them to specialised worker agents.

Available workers:
{workers_description}

When given a task, respond with a JSON array of subtask assignments. Each element must have:
- "worker_role": one of the available worker roles
- "task": a clear, self-contained description of what the worker should do
- "depends_on": list of task indices (0-based) that must complete first, or []

Example response:
```json
[
  {"worker_role": "bug_fixer", "task": "Find and fix the null pointer in auth.py line 42", "depends_on": []},
  {"worker_role": "test_writer", "task": "Write unit tests for the auth.py fix", "depends_on": [0]}
]
```

IMPORTANT: Respond ONLY with the JSON array. No other text."""
    }

    /**
     * Launch an orchestration run for the given goal.
     * Non-blocking — emits OrchestratorEvents on the events flow.
     */
    fun run(goal: String) {
        if (isRunning) {
            log.warn("Orchestration already in progress")
            return
        }

        val workers = CopilotChatSettings.getInstance().workers.filter { it.enabled }
        if (workers.isEmpty()) {
            scope.launch { _events.emit(OrchestratorEvent.Error("No workers configured")) }
            return
        }

        isRunning = true
        orchestrationJob = scope.launch {
            try {
                _events.emit(OrchestratorEvent.PlanStarted(goal))

                // Ensure LSP is initialized
                ConversationManager.getInstance(project).ensureInitialized()

                // Step 1: Plan
                val tasks = planTasks(goal, workers)
                _events.emit(OrchestratorEvent.PlanCompleted(tasks))

                // Step 2: Execute DAG
                val results = executeDag(tasks, workers)

                // Step 3: Synthesize
                _events.emit(OrchestratorEvent.SummarizeStarted)
                val summary = summarize(goal, results)
                _events.emit(OrchestratorEvent.SummarizeCompleted(summary))

                _events.emit(OrchestratorEvent.Finished(tasks, results, summary))

            } catch (e: CancellationException) {
                _events.emit(OrchestratorEvent.Error("Orchestration cancelled"))
                throw e
            } catch (e: Exception) {
                log.error("Orchestration failed", e)
                _events.emit(OrchestratorEvent.Error(e.message ?: "Unknown error"))
            } finally {
                cleanupSessions()
                isRunning = false
            }
        }
    }

    /** Cancel the running orchestration and all workers. */
    fun cancel() {
        orchestrationJob?.cancel()
        orchestrationJob = null
        cleanupSessions()
        isRunning = false
    }

    /**
     * Use a chat-only WorkerSession to decompose the goal into a task plan.
     * Port of Python Orchestrator._plan_tasks().
     */
    private suspend fun planTasks(
        goal: String,
        workers: List<CopilotChatSettings.WorkerEntry>,
    ): List<PlannedTask> {
        val workersDesc = workers.joinToString("\n") { w ->
            "- ${w.role}: ${w.description.ifBlank { w.systemPrompt.take(120) }}"
        }
        val planningPrompt = PLANNING_SYSTEM_PROMPT.replace("{workers_description}", workersDesc) +
            "\n\nGoal: $goal"

        // Create a chat-only session for planning (no agent mode, no tools)
        val plannerSession = WorkerSession(
            workerId = "planner-${UUID.randomUUID().toString().take(6)}",
            role = "planner",
            systemPrompt = "",
            model = CopilotChatSettings.getInstance().defaultModel,
            agentMode = false,
            toolsEnabled = null,
            projectName = project.name,
            workspaceRoot = project.basePath ?: "/tmp",
        )

        val reply = plannerSession.executeTask(planningPrompt)
        log.info("Planning reply: ${reply.take(500)}")

        // Parse JSON from reply (handle markdown fences)
        var jsonStr = reply.trim()
        if ("```json" in jsonStr) {
            jsonStr = jsonStr.substringAfter("```json").substringBefore("```")
        } else if ("```" in jsonStr) {
            jsonStr = jsonStr.substringAfter("```").substringBefore("```")
        }
        jsonStr = jsonStr.trim()

        val validRoles = workers.map { it.role }.toSet()
        val fallbackRole = workers.first().role

        val tasks = try {
            val arr = Json.parseToJsonElement(jsonStr).jsonArray
            arr.mapIndexed { index, el ->
                val obj = el.jsonObject
                var role = obj["worker_role"]?.jsonPrimitive?.contentOrNull ?: fallbackRole
                if (role !in validRoles) role = fallbackRole
                val task = obj["task"]?.jsonPrimitive?.contentOrNull ?: ""
                val depsArray = obj["depends_on"]?.jsonArray
                val deps = depsArray?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()
                PlannedTask(index, role, task, deps)
            }
        } catch (e: Exception) {
            log.warn("Failed to parse plan JSON, falling back to single task: ${e.message}")
            listOf(PlannedTask(0, fallbackRole, goal, emptyList()))
        }

        log.info("Planned ${tasks.size} task(s): ${tasks.map { "${it.index}:[${it.workerRole}]" }}")
        return tasks
    }

    /**
     * Execute tasks respecting DAG dependencies.
     * Tasks with satisfied dependencies launch in parallel via async/awaitAll().
     * Port of Python Orchestrator._execute_mcp() / _execute_queue().
     */
    private suspend fun executeDag(
        tasks: List<PlannedTask>,
        workers: List<CopilotChatSettings.WorkerEntry>,
    ): List<TaskResult> {
        val workerConfigs = workers.associateBy { it.role }
        val completed = Collections.synchronizedMap(mutableMapOf<Int, TaskResult>())
        val pending = Collections.synchronizedSet(tasks.indices.toMutableSet())

        while (pending.isNotEmpty()) {
            // Find tasks whose dependencies are all satisfied
            val ready = pending.filter { idx ->
                tasks[idx].dependsOn.all { depIdx -> depIdx in completed }
            }

            if (ready.isEmpty()) {
                log.warn("DAG deadlock: pending=$pending, completed=${completed.keys}")
                break
            }

            // Launch ready tasks in parallel
            val deferreds = ready.map { idx ->
                pending.remove(idx)
                val task = tasks[idx]

                _events.emit(OrchestratorEvent.TaskAssigned(task))

                scope.async {
                    // Build dependency context
                    val depContext = mutableMapOf<String, String>()
                    for (depIdx in task.dependsOn) {
                        val dep = completed[depIdx] ?: continue
                        depContext["result_from_${dep.workerRole}_task_$depIdx"] = dep.result
                    }

                    val result = executeOneTask(task, workerConfigs, depContext)
                    completed[idx] = result
                    _events.emit(OrchestratorEvent.TaskCompleted(result))
                    result
                }
            }

            // Wait for this batch to complete (SupervisorJob: one failure doesn't cancel siblings)
            deferreds.forEach { deferred ->
                try {
                    deferred.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Task failed in DAG batch: ${e.message}")
                }
            }
        }

        // Build ordered results list
        return tasks.map { task ->
            completed[task.index] ?: TaskResult(
                index = task.index,
                workerRole = task.workerRole,
                task = task.task,
                status = "skipped",
                result = "Not executed (dependency failure)",
            )
        }
    }

    /**
     * Execute a single planned task using the appropriate WorkerSession.
     * Sessions are reused per role — subsequent tasks become conversation/turn.
     */
    private suspend fun executeOneTask(
        task: PlannedTask,
        workerConfigs: Map<String, CopilotChatSettings.WorkerEntry>,
        depContext: Map<String, String>,
    ): TaskResult {
        val config = workerConfigs[task.workerRole]
        if (config == null) {
            return TaskResult(task.index, task.workerRole, task.task, "error", "No worker config for role: ${task.workerRole}")
        }

        val session = getOrCreateSession(task.workerRole, config)

        return try {
            val reply = session.executeTask(task.task, depContext)
            TaskResult(task.index, task.workerRole, task.task, "success", reply)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Task ${task.index} [${task.workerRole}] failed: ${e.message}")
            TaskResult(task.index, task.workerRole, task.task, "error", e.message ?: "Unknown error")
        }
    }

    /**
     * Get or create a WorkerSession for a given role.
     * Each role gets one session per orchestration run.
     */
    private fun getOrCreateSession(role: String, config: CopilotChatSettings.WorkerEntry): WorkerSession {
        return sessions.getOrPut(role) {
            WorkerSession(
                workerId = "$role-${UUID.randomUUID().toString().take(6)}",
                role = role,
                systemPrompt = config.systemPrompt,
                model = config.model.ifBlank { CopilotChatSettings.getInstance().defaultModel },
                agentMode = config.agentMode,
                toolsEnabled = config.toolsEnabled,
                projectName = project.name,
                workspaceRoot = project.basePath ?: "/tmp",
            ).also { session ->
                session.onEvent = { event ->
                    scope.launch {
                        when (event) {
                            is WorkerEvent.Delta -> _events.emit(OrchestratorEvent.TaskProgress(role, event.text))
                            is WorkerEvent.ToolCall -> _events.emit(OrchestratorEvent.TaskProgress(role, "\n[tool] ${event.toolName}\n"))
                            is WorkerEvent.Error -> _events.emit(OrchestratorEvent.Error("Worker $role: ${event.message}"))
                            is WorkerEvent.Done -> {} // handled by executeTask return
                        }
                    }
                }
            }
        }
    }

    /**
     * Use the planner session to synthesize all results into a final summary.
     * Port of Python Orchestrator._summarize().
     */
    private suspend fun summarize(goal: String, results: List<TaskResult>): String {
        val resultsText = results.joinToString("\n") { r ->
            "Task ${r.index} [${r.workerRole}] (${r.status}): ${r.result.take(500)}"
        }
        val summaryPrompt = """The original goal was: $goal

Here are the results from the worker agents:
$resultsText

Please provide a concise summary of what was accomplished, any issues encountered, and next steps if applicable."""

        val summarySession = WorkerSession(
            workerId = "summarizer-${UUID.randomUUID().toString().take(6)}",
            role = "summarizer",
            systemPrompt = "",
            model = CopilotChatSettings.getInstance().defaultModel,
            agentMode = false,
            toolsEnabled = null,
            projectName = project.name,
            workspaceRoot = project.basePath ?: "/tmp",
        )

        return try {
            summarySession.executeTask(summaryPrompt)
        } catch (e: Exception) {
            log.warn("Summary generation failed: ${e.message}")
            "Summary generation failed: ${e.message}"
        }
    }

    private fun cleanupSessions() {
        sessions.values.forEach { it.cancel() }
        sessions.clear()
    }

    override fun dispose() {
        cancel()
        scope.cancel()
    }
}

/** A planned task from the LLM decomposition. */
data class PlannedTask(
    val index: Int,
    val workerRole: String,
    val task: String,
    val dependsOn: List<Int>,
)

/** Result of executing a single task. */
data class TaskResult(
    val index: Int,
    val workerRole: String,
    val task: String,
    val status: String,
    val result: String,
)

/** Events emitted by the orchestrator for UI integration. */
sealed class OrchestratorEvent {
    data class PlanStarted(val goal: String) : OrchestratorEvent()
    data class PlanCompleted(val tasks: List<PlannedTask>) : OrchestratorEvent()
    data class TaskAssigned(val task: PlannedTask) : OrchestratorEvent()
    data class TaskProgress(val workerRole: String, val text: String) : OrchestratorEvent()
    data class TaskCompleted(val result: TaskResult) : OrchestratorEvent()
    data object SummarizeStarted : OrchestratorEvent()
    data class SummarizeCompleted(val summary: String) : OrchestratorEvent()
    data class Finished(val tasks: List<PlannedTask>, val results: List<TaskResult>, val summary: String) : OrchestratorEvent()
    data class Error(val message: String) : OrchestratorEvent()
}
