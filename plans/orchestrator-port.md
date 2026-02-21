# Plan: Port Python Orchestrator to Kotlin Plugin

## Context
The Python CLI has a full multi-agent orchestrator (`orchestrator.py` + `mcp_agent.py`) with LLM-driven task planning, DAG-based parallel execution, per-worker system prompts, tool filtering, dependency context passing, and result synthesis. The Kotlin plugin has a naive `WorkerPanel.orchestrate()` that sends a single text prompt to the shared `ConversationManager` — no plan parsing, no parallel workers, no isolated conversations.

The Copilot LSP server supports multiple concurrent conversations via separate `conversationId` + `workDoneToken` pairs on the same connection. Tool calls are matched by JSON-RPC request ID (stateless routing). This means we can run N worker conversations in parallel on the single `LspClient` without process-level isolation.

We implement **queue transport only** (in-process coroutines) — no MCP child processes. The Kotlin plugin already has all tools in-process.

## Architecture

```
User enters goal in WorkerPanel
   ↓
OrchestratorService (project-level)
   ├── Planning WorkerSession (conversationId-0, agentMode=false)
   │   ├── planTasks() → JSON array of {worker_role, task, depends_on}
   │   └── summarize() → final synthesis text
   │
   └── DAG executor (parallel coroutines)
       ├── WorkerSession "coder" (conversationId-1, agentMode=true, all tools)
       ├── WorkerSession "reviewer" (conversationId-2, agentMode=true, read-only tools)
       └── WorkerSession "tester" (conversationId-3, agentMode=true, all tools)
```

Each `WorkerSession` independently:
- Calls `conversation/create` on the shared `LspClient` (gets its own `conversationId`)
- Registers its own `workDoneToken` progress listener (isolated streaming)
- Injects `<system_instructions>` on first turn only
- Injects `<shared_context>` with dependency results
- Injects `<tool_restrictions>` if `toolsEnabled` is set
- Tool calls routed through existing `ConversationManager.handleServerRequest()` → `ToolRouter` (stateless)

## Files to Create

### 1. `copilot-chat/src/main/kotlin/com/citigroup/copilotchat/orchestrator/WorkerSession.kt`

Lightweight conversation wrapper (~180 lines). Port of Python `QueueWorker._handle_task()` logic.

- `class WorkerSession(workerId, role, systemPrompt, model, agentMode, toolsEnabled, projectName, workspaceRoot)`
- `suspend fun executeTask(task: String, dependencyContext: Map<String, String> = emptyMap()): String`
  - Builds prompt: `<system_instructions>` (first turn) + `<tool_restrictions>` (if filtered) + `<shared_context>` (if deps) + task
  - Creates conversation via `lspClient.sendRequest("conversation/create", ...)` or continues via `"conversation/turn"`
  - Registers unique `workDoneToken` progress listener, collects reply text
  - Returns full reply string
- `fun close()` — cleanup
- `sealed class WorkerEvent` — Delta, ToolCall, Done, Error (for UI streaming)
- Progress handling mirrors `ConversationManager.handleProgress()` (lines 478-551)

### 2. `copilot-chat/src/main/kotlin/com/citigroup/copilotchat/orchestrator/OrchestratorService.kt`

Project-level service (~250 lines). Port of Python `Orchestrator` class.

- `@Service(Service.Level.PROJECT) class OrchestratorService(project)`
- `fun run(goal: String)` — launches orchestration coroutine
- `fun cancel()` — cancels all running workers
- `val events: SharedFlow<OrchestratorEvent>` — for UI integration
- Private methods:
  - `planTasks(goal)` — creates chat-only `WorkerSession`, sends `PLANNING_SYSTEM_PROMPT` (port of lines 480-501, 604-663 from orchestrator.py), parses JSON plan, validates worker roles
  - `executeDag(tasks)` — tracks completed/pending sets, finds ready tasks (deps satisfied), launches ready tasks in parallel via `async`/`awaitAll()`, builds `depContext` from completed results (port of lines 694-780)
  - `executeOneTask(task, ...)` — gets/creates `WorkerSession` for role, calls `session.executeTask()`, returns `TaskResult`
  - `summarize(goal, results)` — calls LLM with all results for synthesis (port of lines 871-891)
- Data classes: `PlannedTask(index, workerRole, task, dependsOn)`, `TaskResult(index, workerRole, task, status, result)`
- `sealed class OrchestratorEvent` — PlanStarted, PlanCompleted, TaskAssigned, TaskProgress, TaskCompleted, SummarizeCompleted, Finished, Error

## Files to Modify

### 3. `copilot-chat/src/main/kotlin/com/citigroup/copilotchat/config/CopilotChatSettings.kt`

Extend `WorkerEntry` (line 23-29):

```kotlin
data class WorkerEntry(
    var role: String = "",
    var description: String = "",
    var model: String = "",
    var systemPrompt: String = "",
    var enabled: Boolean = true,
    // New fields:
    var toolsEnabled: MutableList<String>? = null,  // null = all tools
    var agentMode: Boolean = true,
)
```

### 4. `copilot-chat/src/main/kotlin/com/citigroup/copilotchat/ui/WorkerPanel.kt`

**Rewrite `orchestrate()`** (lines 214-245): delegate to `OrchestratorService.run(goal)`, collect `OrchestratorEvent` flow, update UI (plan display, per-worker status icons, streaming output).

**Rewrite `sendTaskToWorker()`** (lines 160-212): use `WorkerSession` directly instead of sharing `ConversationManager`, so individual worker tasks get isolated conversations.

**Update `WorkerDialog`** (lines 286-350): add `JCheckBox` for agentMode, `JTextArea` for toolsEnabled (comma-separated names, blank=all).

### 5. `copilot-chat/src/main/resources/META-INF/plugin.xml`

Register `OrchestratorService` (after line 31):
```xml
<projectService serviceImplementation="com.citigroup.copilotchat.orchestrator.OrchestratorService"/>
```

## Key Design Decisions

**Tool call routing**: No changes to `LspClient.kt` needed. Tool calls arrive via `serverRequestHandler` (single callback set by `ConversationManager`). `ToolRouter.executeTool()` is stateless — doesn't need to know which conversation triggered it. Per-worker tool filtering is enforced via prompt injection (`<tool_restrictions>`), not hard rejection.

**LSP initialization**: `OrchestratorService.run()` calls `ConversationManager.getInstance(project).ensureInitialized()` first — reuses the existing LSP lifecycle (binary discovery, auth, proxy, tool registration).

**Worker session reuse**: Each role gets one `WorkerSession` per orchestration run. If the same role handles multiple tasks (via depends_on ordering), subsequent tasks become `conversation/turn` on the existing conversation — preserving context.

**Concurrency safety**: DAG executor uses `async`/`awaitAll()` (Kotlin coroutines). `SupervisorJob` ensures one worker failure doesn't cancel siblings. `sessions` map access is `synchronized`.

## Implementation Order

1. Extend `WorkerEntry` in `CopilotChatSettings.kt` (prerequisite for all else)
2. Create `WorkerSession.kt` (independent, testable in isolation)
3. Create `OrchestratorService.kt` + register in `plugin.xml`
4. Rewrite `WorkerPanel.kt` — `orchestrate()`, `sendTaskToWorker()`, `WorkerDialog`
5. Build and verify end-to-end

## Verification

1. **Build**: `./gradlew :copilot-chat:buildPlugin` — must compile
2. **Worker isolation**: Create a worker, send it a task via sendTaskToWorker. Verify it creates a NEW conversationId (check IDE logs) separate from the main chat.
3. **Planning**: Enter a goal with 2+ workers configured. Verify the planner produces a valid JSON task array logged in the IDE log.
4. **DAG execution**: Configure coder + reviewer workers. Goal: "Read the README and suggest improvements". Verify:
   - Tasks execute in correct dependency order
   - Each worker gets its own conversationId
   - Worker status icons update in the UI (idle → working → done)
   - Per-worker output appears when worker is selected
5. **Parallel execution**: Configure 2 workers with no dependencies. Verify both launch concurrently (overlapping log timestamps).
6. **Synthesis**: Verify the final summary appears in the output area after all workers complete.
7. **Cancellation**: Start an orchestration, click cancel. Verify workers stop and UI resets.
