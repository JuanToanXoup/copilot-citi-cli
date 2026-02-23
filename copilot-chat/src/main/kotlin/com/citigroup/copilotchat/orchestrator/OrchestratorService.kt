package com.citigroup.copilotchat.orchestrator

import com.citigroup.copilotchat.config.CopilotChatSettings
import com.citigroup.copilotchat.conversation.ConversationManager
import com.citigroup.copilotchat.lsp.LspClient
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.*
import java.util.*
import java.util.concurrent.Semaphore

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

    // Buffer size: 64. If events are emitted faster than consumed, events may be dropped.
    // Increase if event loss is unacceptable.
    private val _events = MutableSharedFlow<OrchestratorEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<OrchestratorEvent> = _events

    @Volatile
    var isRunning = false
        private set

    @Volatile
    private var disposed = false

    private var orchestrationJob: Job? = null
    private val sessions = Collections.synchronizedMap(mutableMapOf<String, WorkerSession>())
    private var pendingUserResponse: CompletableDeferred<String>? = null
    private val maxSupervisorIterations = 10

    companion object {
        fun getInstance(project: Project): OrchestratorService =
            project.getService(OrchestratorService::class.java)

        // Move prompt to top-level constant for maintainability
        private val planningSystemPrompt: String =
            """You are an orchestrator agent. Your job is to:
1. Select which worker agents to deploy from the available catalog
2. Break down the goal into discrete subtasks and assign them to those workers

Available worker presets:
{preset_catalog}

{user_workers_section}

Respond with a JSON object containing two fields:
- "workers": array of worker role names to deploy (from the presets above, or user-configured workers)
- "tasks": array of subtask assignments, each with:
  - "worker_role": one of the selected worker roles
  - "task": a clear, self-contained description of what the worker should do
  - "depends_on": list of task indices (0-based) that must complete first, or []

Only select workers that are actually needed for the goal. Do not deploy unnecessary workers.

Example response:
```json
{
  "workers": ["researcher", "coder"],
  "tasks": [
    {"worker_role": "researcher", "task": "Read and analyze the README file structure", "depends_on": []},
    {"worker_role": "coder", "task": "Implement the suggested improvements", "depends_on": [0]}
  ]
}
```

IMPORTANT: Respond ONLY with the JSON object. No other text."""

        private val supervisorSystemPrompt: String =
            """You are a supervisor agent evaluating whether an orchestrated goal has been achieved.
You will receive the original goal and the results from worker agents.

Evaluate whether the goal was fully achieved, partially achieved, or needs more work.

Respond with a JSON object:
{
  "decision": "COMPLETE" | "MORE_WORK" | "NEED_INFO",
  "reasoning": "Brief explanation of your evaluation",
  "summary": "Final summary for the user (only when COMPLETE)",
  "question": "Question to ask the user (only when NEED_INFO)",
  "follow_up_tasks": [
    {"worker_role": "role", "task": "description", "depends_on": []}
  ]
}

Rules:
- COMPLETE: The goal is fully achieved. Provide a summary.
- MORE_WORK: Workers produced partial results. Provide follow_up_tasks to finish the job.
- NEED_INFO: You need clarification from the user before proceeding. Provide a question.
- When in doubt, lean toward COMPLETE rather than creating unnecessary follow-up work.
- Do not create follow-up tasks that repeat work already done successfully.
- follow_up_tasks indices are 0-based relative to this batch only.

IMPORTANT: Respond ONLY with the JSON object. No other text."""
    }

    /**
     * Launch an orchestration run for the given goal.
     * Non-blocking — emits OrchestratorEvents on the events flow.
     */
    fun run(goal: String) {
        if (disposed) {
            log.warn("OrchestratorService is disposed; cannot start orchestration.")
            return
        }
        if (isRunning) {
            log.warn("Orchestration already in progress")
            return
        }

        isRunning = true
        orchestrationJob = scope.launch {
            try {
                _events.emit(OrchestratorEvent.PlanStarted(goal))

                // Ensure LSP is initialized
                ConversationManager.getInstance(project).ensureInitialized()

                // Step 1: Plan (auto-selects workers from presets + user config)
                val plan = planTasksAutoWorkers(goal)
                _events.emit(OrchestratorEvent.WorkersGenerated(plan.workers))
                _events.emit(OrchestratorEvent.PlanCompleted(plan.tasks))

                // Step 2: Execute DAG
                val results = executeDag(plan.tasks, plan.workers)

                // Step 3: Supervisor loop
                val supervisorSession = createSupervisorSession()
                val allResults = results.toMutableList()
                var allTasks = plan.tasks.toMutableList()
                var currentWorkers = plan.workers

                for (iteration in 1..maxSupervisorIterations) {
                    _events.emit(OrchestratorEvent.SupervisorEvaluating(iteration))

                    val verdict = evaluateWithSupervisor(supervisorSession, goal, allResults, iteration)
                    _events.emit(OrchestratorEvent.SupervisorVerdictEvent(verdict.decision.name, verdict.reasoning))

                    when (verdict.decision) {
                        SupervisorDecision.COMPLETE -> {
                            val summary = verdict.summary ?: summarize(goal, allResults)
                            _events.emit(OrchestratorEvent.SummarizeCompleted(summary))
                            _events.emit(OrchestratorEvent.Finished(allTasks, allResults, summary))
                            return@launch
                        }

                        SupervisorDecision.NEED_INFO -> {
                            val question = verdict.question ?: "Could you provide more details about the goal?"
                            _events.emit(OrchestratorEvent.WaitingForUser(question))

                            // Suspend until user responds
                            val deferred = CompletableDeferred<String>()
                            pendingUserResponse = deferred
                            val userResponse = deferred.await()
                            pendingUserResponse = null
                            _events.emit(OrchestratorEvent.UserResponded(userResponse))

                            // Re-plan with user input
                            val followUpPlan = replanWithUserInput(
                                supervisorSession, goal, allResults, userResponse, currentWorkers
                            )
                            currentWorkers = followUpPlan.workers
                            _events.emit(OrchestratorEvent.WorkersGenerated(followUpPlan.workers))
                            _events.emit(OrchestratorEvent.FollowUpPlanned(followUpPlan.tasks))

                            val followUpResults = executeDag(followUpPlan.tasks, followUpPlan.workers)
                            allTasks.addAll(followUpPlan.tasks)
                            allResults.addAll(followUpResults)
                        }

                        SupervisorDecision.MORE_WORK -> {
                            val specs = verdict.followUpTasks
                            if (specs.isNullOrEmpty()) {
                                // No tasks specified — treat as complete to prevent infinite loop
                                log.warn("Supervisor said MORE_WORK but gave no tasks; treating as COMPLETE")
                                val summary = summarize(goal, allResults)
                                _events.emit(OrchestratorEvent.SummarizeCompleted(summary))
                                _events.emit(OrchestratorEvent.Finished(allTasks, allResults, summary))
                                return@launch
                            }

                            // Materialize follow-up tasks
                            val userWorkers = CopilotChatSettings.getInstance().workers.filter { it.enabled }
                            val existingRoles = currentWorkers.associateBy { it.role }
                            val newWorkerEntries = mutableListOf<CopilotChatSettings.WorkerEntry>()

                            val followUpTasks = specs.mapIndexed { index, spec ->
                                // Ensure worker entry exists for the role
                                if (spec.workerRole !in existingRoles) {
                                    val entry = materializeWorkerEntry(spec.workerRole, userWorkers)
                                    newWorkerEntries.add(entry)
                                }
                                PlannedTask(index, spec.workerRole, spec.task, spec.dependsOn)
                            }

                            val combinedWorkers = currentWorkers + newWorkerEntries
                            currentWorkers = combinedWorkers
                            if (newWorkerEntries.isNotEmpty()) {
                                _events.emit(OrchestratorEvent.WorkersGenerated(combinedWorkers))
                            }
                            _events.emit(OrchestratorEvent.FollowUpPlanned(followUpTasks))

                            val followUpResults = executeDag(followUpTasks, combinedWorkers)
                            allTasks.addAll(followUpTasks)
                            allResults.addAll(followUpResults)
                        }
                    }
                }

                // Max iterations reached — fall back to summarize
                log.warn("Supervisor loop hit max iterations ($maxSupervisorIterations), falling back to summarize")
                _events.emit(OrchestratorEvent.SummarizeStarted)
                val fallbackSummary = summarize(goal, allResults)
                _events.emit(OrchestratorEvent.SummarizeCompleted(fallbackSummary))
                _events.emit(OrchestratorEvent.Finished(allTasks, allResults, fallbackSummary))

            } catch (e: CancellationException) {
                _events.emit(OrchestratorEvent.Error("Orchestration cancelled"))
                throw e
            } catch (e: Exception) {
                log.error("Orchestration failed", e)
                _events.emit(OrchestratorEvent.Error(e.message ?: "Unknown error"))
            } finally {
                pendingUserResponse = null
                cleanupSessions()
                isRunning = false
            }
        }
    }

    /** Cancel the running orchestration and all workers. */
    fun cancel() {
        pendingUserResponse?.cancel()
        pendingUserResponse = null
        orchestrationJob?.cancel()
        orchestrationJob = null
        cleanupSessions()
        isRunning = false
    }

    /** Called by UI when user answers a supervisor question. Resumes the suspended coroutine. */
    fun respond(text: String) {
        pendingUserResponse?.complete(text)
    }

    /**
     * Plan tasks with automatic worker selection.
     * Sends the preset catalog + user workers to the LLM, which returns both
     * the workers to deploy and the task assignments in one JSON response.
     */
    private suspend fun planTasksAutoWorkers(goal: String): OrchestrationPlan {
        val userWorkers = CopilotChatSettings.getInstance().workers.filter { it.enabled }

        // Build user workers section for prompt
        val userWorkersSection = if (userWorkers.isNotEmpty()) {
            "User-configured workers (these override presets with the same role):\n" +
                userWorkers.joinToString("\n") { w ->
                    "- ${w.role}: ${w.description.ifBlank { w.systemPrompt.take(120) }}"
                }
        } else {
            "No user-configured workers. Select from the presets above."
        }

        val planningPrompt = planningSystemPrompt
            .replace("{preset_catalog}", WorkerPresets.catalogDescription())
            .replace("{user_workers_section}", userWorkersSection) +
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
            lspClient = LspClient.getInstance(project),
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

        // Stricter validation: limit size and check structure
        if (jsonStr.length > 10000) throw IllegalStateException("LLM plan JSON too large")
        if (!jsonStr.startsWith("{") || !jsonStr.endsWith("}")) throw IllegalStateException("LLM plan JSON malformed")

        return try {
            val root = Json.parseToJsonElement(jsonStr).jsonObject

            // Parse selected workers
            val workerRoles = root["workers"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: listOf("coder")

            // Materialize WorkerEntry objects: user overrides take precedence over presets
            val materializedWorkers = workerRoles.map { role ->
                val userWorker = userWorkers.find { it.role == role }
                if (userWorker != null) {
                    userWorker  // user-configured worker overrides preset
                } else {
                    val preset = WorkerPresets.findByRole(role)
                    if (preset != null) {
                        CopilotChatSettings.WorkerEntry(
                            role = preset.role,
                            description = preset.description,
                            systemPrompt = preset.systemPrompt,
                            enabled = true,
                            toolsEnabled = preset.toolsEnabled?.toMutableList(),
                            agentMode = preset.agentMode,
                        )
                    } else {
                        // Unknown role — create a generic agent
                        log.warn("Unknown worker role '$role' from planner, using generic agent")
                        CopilotChatSettings.WorkerEntry(
                            role = role,
                            description = "Generic agent for $role tasks",
                            systemPrompt = "You are a helpful assistant specializing in $role tasks.",
                            enabled = true,
                            toolsEnabled = null,
                            agentMode = true,
                        )
                    }
                }
            }

            // Parse tasks
            val validRoles = materializedWorkers.map { it.role }.toSet()
            val fallbackRole = materializedWorkers.firstOrNull()?.role ?: "coder"

            val tasksArray = root["tasks"]?.jsonArray ?: throw IllegalStateException("No tasks in plan")
            val tasks = tasksArray.mapIndexed { index, el ->
                val obj = el.jsonObject
                var role = obj["worker_role"]?.jsonPrimitive?.contentOrNull ?: fallbackRole
                if (role !in validRoles) role = fallbackRole
                val task = obj["task"]?.jsonPrimitive?.contentOrNull ?: ""
                val depsArray = obj["depends_on"]?.jsonArray
                val deps = depsArray?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()
                PlannedTask(index, role, task, deps)
            }

            log.info("Auto-planned ${materializedWorkers.size} worker(s), ${tasks.size} task(s)")
            OrchestrationPlan(materializedWorkers, tasks)

        } catch (e: Exception) {
            log.warn("Failed to parse auto-worker plan, falling back to single coder: ${e.message}")
            // Fallback: single coder worker with the full goal
            val fallbackWorker = userWorkers.find { it.role == "coder" }
                ?: WorkerPresets.findByRole("coder")!!.let { preset ->
                    CopilotChatSettings.WorkerEntry(
                        role = preset.role,
                        description = preset.description,
                        systemPrompt = preset.systemPrompt,
                        enabled = true,
                        toolsEnabled = preset.toolsEnabled?.toMutableList(),
                        agentMode = preset.agentMode,
                    )
                }
            OrchestrationPlan(
                workers = listOf(fallbackWorker),
                tasks = listOf(PlannedTask(0, fallbackWorker.role, goal, emptyList())),
            )
        }
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

        // Limit concurrent tasks (configurable)
        val maxConcurrentTasks = 8
        val semaphore = Semaphore(maxConcurrentTasks)

        var deadlockEmitted = false

        while (pending.isNotEmpty()) {
            // Find tasks whose dependencies are all satisfied
            val ready = pending.filter { idx ->
                tasks[idx].dependsOn.all { depIdx -> depIdx in completed }
            }

            if (ready.isEmpty()) {
                log.warn("DAG deadlock: pending=$pending, completed=${completed.keys}")
                if (!deadlockEmitted) {
                    _events.emit(OrchestratorEvent.Deadlock(pending.toList(), completed.keys.toList()))
                    deadlockEmitted = true
                }
                break
            }

            // Launch ready tasks in parallel, but limit concurrency
            val deferreds = ready.map { idx ->
                pending.remove(idx)
                val task = tasks[idx]

                _events.emit(OrchestratorEvent.TaskAssigned(task))

                scope.async {
                    semaphore.acquire()
                    try {
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
                    } finally {
                        semaphore.release()
                    }
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
            completed[task.index] ?: run {
                val skippedResult = TaskResult(
                    index = task.index,
                    workerRole = task.workerRole,
                    task = task.task,
                    status = "skipped",
                    result = "Not executed (dependency failure)",
                )
                _events.emit(OrchestratorEvent.TaskSkipped(task, skippedResult))
                skippedResult
            }
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
                lspClient = LspClient.getInstance(project),
            ).also { session ->
                session.onEvent = { event ->
                    scope.launch {
                        when (event) {
                            is WorkerEvent.Delta -> _events.emit(OrchestratorEvent.TaskProgress(role, event.text))
                            is WorkerEvent.ToolCall -> {} // tool calls are internal details, not shown in orchestrator UI
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
            lspClient = LspClient.getInstance(project),
        )

        return try {
            summarySession.executeTask(summaryPrompt)
        } catch (e: Exception) {
            log.warn("Summary generation failed: ${e.message}")
            "Summary generation failed: ${e.message}"
        }
    }

    /** Create a chat-mode session for the supervisor agent. Persists across iterations. */
    private fun createSupervisorSession(): WorkerSession {
        return WorkerSession(
            workerId = "supervisor-${UUID.randomUUID().toString().take(6)}",
            role = "supervisor",
            systemPrompt = supervisorSystemPrompt,
            model = CopilotChatSettings.getInstance().defaultModel,
            agentMode = false,
            toolsEnabled = null,
            projectName = project.name,
            workspaceRoot = project.basePath ?: "/tmp",
            lspClient = LspClient.getInstance(project),
        )
    }

    /** Ask the supervisor to evaluate whether the goal is met. */
    private suspend fun evaluateWithSupervisor(
        session: WorkerSession,
        goal: String,
        allResults: List<TaskResult>,
        iteration: Int,
    ): SupervisorVerdict {
        val resultsText = allResults.joinToString("\n") { r ->
            "Task ${r.index} [${r.workerRole}] (${r.status}): ${r.result.take(500)}"
        }
        val prompt = """Iteration $iteration evaluation.

Original goal: $goal

Results so far:
$resultsText

Evaluate whether the goal has been achieved. Respond with JSON only."""

        return try {
            val reply = session.executeTask(prompt)
            log.info("Supervisor reply (iteration $iteration): ${reply.take(300)}")
            parseSupervisorVerdict(reply)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Supervisor evaluation failed: ${e.message}, defaulting to COMPLETE")
            SupervisorVerdict(SupervisorDecision.COMPLETE, "Evaluation failed: ${e.message}", null, null, null)
        }
    }

    /** Parse the supervisor's JSON response into a SupervisorVerdict. Falls back to COMPLETE on failure. */
    private fun parseSupervisorVerdict(reply: String): SupervisorVerdict {
        return try {
            var jsonStr = reply.trim()
            if ("```json" in jsonStr) {
                jsonStr = jsonStr.substringAfter("```json").substringBefore("```")
            } else if ("```" in jsonStr) {
                jsonStr = jsonStr.substringAfter("```").substringBefore("```")
            }
            jsonStr = jsonStr.trim()

            val root = Json.parseToJsonElement(jsonStr).jsonObject
            val decisionStr = root["decision"]?.jsonPrimitive?.contentOrNull ?: "COMPLETE"
            val decision = try {
                SupervisorDecision.valueOf(decisionStr.uppercase())
            } catch (_: IllegalArgumentException) {
                SupervisorDecision.COMPLETE
            }
            val reasoning = root["reasoning"]?.jsonPrimitive?.contentOrNull ?: ""
            val summary = root["summary"]?.jsonPrimitive?.contentOrNull
            val question = root["question"]?.jsonPrimitive?.contentOrNull
            val followUpTasks = root["follow_up_tasks"]?.jsonArray?.map { el ->
                val obj = el.jsonObject
                FollowUpTaskSpec(
                    workerRole = obj["worker_role"]?.jsonPrimitive?.contentOrNull ?: "coder",
                    task = obj["task"]?.jsonPrimitive?.contentOrNull ?: "",
                    dependsOn = obj["depends_on"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList(),
                )
            }

            SupervisorVerdict(decision, reasoning, summary, question, followUpTasks)
        } catch (e: Exception) {
            log.warn("Failed to parse supervisor verdict, defaulting to COMPLETE: ${e.message}")
            SupervisorVerdict(SupervisorDecision.COMPLETE, "Parse failure — treating as complete. Raw: ${reply.take(200)}", null, null, null)
        }
    }

    /** Ask the supervisor to produce follow-up tasks incorporating the user's answer. */
    private suspend fun replanWithUserInput(
        session: WorkerSession,
        goal: String,
        allResults: List<TaskResult>,
        userResponse: String,
        currentWorkers: List<CopilotChatSettings.WorkerEntry>,
    ): OrchestrationPlan {
        val availableRoles = currentWorkers.map { it.role }
        val resultsText = allResults.joinToString("\n") { r ->
            "Task ${r.index} [${r.workerRole}] (${r.status}): ${r.result.take(300)}"
        }

        val prompt = """The user has responded to your question.

Original goal: $goal
User's response: $userResponse

Previous results:
$resultsText

Available worker roles: $availableRoles

Based on the user's response, produce follow-up tasks as a JSON object:
{
  "workers": ["role1", "role2"],
  "tasks": [
    {"worker_role": "role", "task": "description", "depends_on": []}
  ]
}

Only include workers that are needed. Respond with JSON only."""

        return try {
            val reply = session.executeTask(prompt)
            log.info("Replan reply: ${reply.take(300)}")

            var jsonStr = reply.trim()
            if ("```json" in jsonStr) {
                jsonStr = jsonStr.substringAfter("```json").substringBefore("```")
            } else if ("```" in jsonStr) {
                jsonStr = jsonStr.substringAfter("```").substringBefore("```")
            }
            jsonStr = jsonStr.trim()

            val root = Json.parseToJsonElement(jsonStr).jsonObject
            val workerRoles = root["workers"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: availableRoles

            val userWorkers = CopilotChatSettings.getInstance().workers.filter { it.enabled }
            val materializedWorkers = workerRoles.map { role ->
                currentWorkers.find { it.role == role } ?: materializeWorkerEntry(role, userWorkers)
            }

            val tasksArray = root["tasks"]?.jsonArray ?: throw IllegalStateException("No tasks in replan")
            val validRoles = materializedWorkers.map { it.role }.toSet()
            val fallbackRole = materializedWorkers.firstOrNull()?.role ?: "coder"

            val tasks = tasksArray.mapIndexed { index, el ->
                val obj = el.jsonObject
                var role = obj["worker_role"]?.jsonPrimitive?.contentOrNull ?: fallbackRole
                if (role !in validRoles) role = fallbackRole
                val task = obj["task"]?.jsonPrimitive?.contentOrNull ?: ""
                val deps = obj["depends_on"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()
                PlannedTask(index, role, task, deps)
            }

            OrchestrationPlan(materializedWorkers, tasks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Replan failed: ${e.message}, creating single follow-up task")
            val fallbackWorker = currentWorkers.firstOrNull() ?: CopilotChatSettings.WorkerEntry(
                role = "coder", description = "Coder", systemPrompt = "You are a helpful coding assistant.",
                enabled = true, toolsEnabled = null, agentMode = true,
            )
            OrchestrationPlan(
                workers = listOf(fallbackWorker),
                tasks = listOf(PlannedTask(0, fallbackWorker.role, "Continue working on: $goal\nUser clarification: $userResponse", emptyList())),
            )
        }
    }

    /** Materialize a WorkerEntry for a role, checking user config then presets. */
    private fun materializeWorkerEntry(
        role: String,
        userWorkers: List<CopilotChatSettings.WorkerEntry>,
    ): CopilotChatSettings.WorkerEntry {
        val userWorker = userWorkers.find { it.role == role }
        if (userWorker != null) return userWorker

        val preset = WorkerPresets.findByRole(role)
        if (preset != null) {
            return CopilotChatSettings.WorkerEntry(
                role = preset.role,
                description = preset.description,
                systemPrompt = preset.systemPrompt,
                enabled = true,
                toolsEnabled = preset.toolsEnabled?.toMutableList(),
                agentMode = preset.agentMode,
            )
        }

        log.warn("Unknown worker role '$role' from supervisor, using generic agent")
        return CopilotChatSettings.WorkerEntry(
            role = role,
            description = "Generic agent for $role tasks",
            systemPrompt = "You are a helpful assistant specializing in $role tasks.",
            enabled = true,
            toolsEnabled = null,
            agentMode = true,
        )
    }

    private fun cleanupSessions() {
        sessions.values.forEach { it.cancel() }
        sessions.clear()
    }

    override fun dispose() {
        disposed = true
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

/** Combined plan: auto-selected workers + task assignments. */
data class OrchestrationPlan(
    val workers: List<CopilotChatSettings.WorkerEntry>,
    val tasks: List<PlannedTask>,
)

/** Supervisor agent's evaluation of goal completion. */
data class SupervisorVerdict(
    val decision: SupervisorDecision,
    val reasoning: String,
    val summary: String?,
    val question: String?,
    val followUpTasks: List<FollowUpTaskSpec>?,
)

enum class SupervisorDecision { COMPLETE, MORE_WORK, NEED_INFO }

data class FollowUpTaskSpec(val workerRole: String, val task: String, val dependsOn: List<Int>)

/** Events emitted by the orchestrator for UI integration. */
sealed class OrchestratorEvent {
    data class PlanStarted(val goal: String) : OrchestratorEvent()
    data class WorkersGenerated(val workers: List<CopilotChatSettings.WorkerEntry>) : OrchestratorEvent()
    data class PlanCompleted(val tasks: List<PlannedTask>) : OrchestratorEvent()
    data class TaskAssigned(val task: PlannedTask) : OrchestratorEvent()
    data class TaskProgress(val workerRole: String, val text: String) : OrchestratorEvent()
    data class TaskCompleted(val result: TaskResult) : OrchestratorEvent()
    data object SummarizeStarted : OrchestratorEvent()
    data class SummarizeCompleted(val summary: String) : OrchestratorEvent()
    data class Finished(val tasks: List<PlannedTask>, val results: List<TaskResult>, val summary: String) : OrchestratorEvent()
    data class Error(val message: String) : OrchestratorEvent()
    data class TaskSkipped(val task: PlannedTask, val result: TaskResult) : OrchestratorEvent()
    data class Deadlock(val pending: List<Int>, val completed: List<Int>) : OrchestratorEvent()
    data class SupervisorEvaluating(val iteration: Int) : OrchestratorEvent()
    data class SupervisorVerdictEvent(val decision: String, val reasoning: String) : OrchestratorEvent()
    data class FollowUpPlanned(val tasks: List<PlannedTask>) : OrchestratorEvent()
    data class WaitingForUser(val question: String) : OrchestratorEvent()
    data class UserResponded(val response: String) : OrchestratorEvent()
}