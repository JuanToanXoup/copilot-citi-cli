# LspClientPool Implementation Plan

## Context

Currently, a single `LspClient` (@Service, project-scoped) runs one `copilot-language-server` process per IntelliJ project. All conversations — Chat tab, lead agent, subagents, teammates — share this process. Tool registration (`conversation/registerTools`) is global per process, so per-agent tool scoping is enforced at runtime by `AgentService.isToolAllowedForConversation()`, which rejects tool calls after the model already sees them.

**Goal**: Partition LSP instances by tool registration boundaries. Agents with the same tool set share a process; agents with different tool sets get separate processes. This eliminates runtime tool filtering — each process only has the tools its agents need.

**Cost**: ~50MB per additional process. Auth happens once and is cached. Typical case: 2-3 distinct tool profiles (default/full, explore-only, code-review-only).

---

## Phase 0: Extract LspClient from @Service

**Goal**: Make `LspClient` a plain class; `LspClientPool` becomes the new `@Service`. Zero behavior change — all consumers still use a single client.

### Files to modify
- `lsp/LspClient.kt` — Remove `@Service`, remove `companion object { getInstance() }`, make constructor take `clientId: String = "default"` instead of `project: Project`
- `lsp/LspClientPool.kt` — **NEW** `@Service(Level.PROJECT)` wrapping a single default `LspClient`
- `META-INF/plugin.xml` line 25 — Replace `LspClient` registration with `LspClientPool`
- `conversation/ConversationManager.kt` line 50 — `LspClientPool.getInstance(project).default`
- `agent/AgentService.kt` line 50 — same
- `agent/TeamService.kt` line 128 — same
- `agent/SubagentManager.kt` line 26 — no change (injected by AgentService)

### LspClient changes
```kotlin
// REMOVE: @Service(Service.Level.PROJECT)
class LspClient(val clientId: String = "default") : Disposable {
    // REMOVE companion object { getInstance(project) }
    // Everything else unchanged
}
```

### LspClientPool (new)
```kotlin
@Service(Service.Level.PROJECT)
class LspClientPool(private val project: Project) : Disposable {
    private val defaultClient = LspClient("default")
    val default: LspClient get() = defaultClient

    companion object {
        fun getInstance(project: Project): LspClientPool =
            project.getService(LspClientPool::class.java)
    }
    override fun dispose() { defaultClient.dispose() }
}
```

### Verification
Run plugin — Chat tab and Agent tab work identically. The only change is indirection through `LspClientPool`.

---

## Phase 1: CachedAuth Infrastructure

**Goal**: Extract auth state so it can be performed once and shared across multiple LspClient instances.

### Files
- `lsp/CachedAuth.kt` — **NEW** shared auth state holder
- `conversation/LspSession.kt` — Accept `CachedAuth` param; populate on first init, reuse on subsequent inits

### CachedAuth (new)
```kotlin
class CachedAuth {
    @Volatile var binaryPath: String? = null
    @Volatile var lspEnv: Map<String, String> = emptyMap()
    @Volatile var authToken: String? = null   // from CopilotAuth.readAuth()
    @Volatile var appId: String = ""
    @Volatile var featureFlags: Map<String, Any> = emptyMap()
    @Volatile var isServerMcpEnabled: Boolean = false
    val isResolved: Boolean get() = binaryPath != null && authToken != null
}
```

### LspSession changes
- New constructor param: `cachedAuth: CachedAuth = CachedAuth()`
- In `ensureInitialized()`: after auth succeeds, populate `cachedAuth`
- When `cachedAuth.isResolved` on entry: skip binary discovery, use cached `binaryPath`/`lspEnv`; still do per-process `initialize`/`setEditorInfo`; use cached token for `signInConfirm` (skip `checkStatus` first); skip feature flag wait (use cached)

### ConversationManager change
- Create `CachedAuth` instance, pass to `LspSession`
- Expose `cachedAuth` for pool to share

### Verification
Run plugin — first init populates CachedAuth. Logs show auth values cached. No behavior change.

---

## Phase 2: Multi-Client Pool + ToolSetKey

**Goal**: LspClientPool manages multiple clients keyed by tool set. No consumers use multi-client yet.

### Files
- `lsp/LspClientPool.kt` — Add `ToolSetKey`, `getClient(tools)`, `ensureInitialized(key)`, lifecycle

### ToolSetKey
```kotlin
@JvmInline
value class ToolSetKey(val value: String) {
    companion object {
        val ALL = ToolSetKey("*")
        fun of(tools: Collection<String>): ToolSetKey {
            if (tools.isEmpty()) return ALL
            return ToolSetKey(tools.sorted().distinct().joinToString(","))
        }
    }
}
```

### Pool internals
```kotlin
private data class PoolEntry(
    val client: LspClient,
    var session: LspSession? = null,
    val refCount: AtomicInteger = AtomicInteger(0),
)

private val clients = ConcurrentHashMap<ToolSetKey, PoolEntry>()

fun getClient(tools: Collection<String> = emptyList()): LspClient {
    val key = ToolSetKey.of(tools)
    return clients.computeIfAbsent(key) { PoolEntry(LspClient(it.value)) }.client
}

fun acquireClient(tools: Collection<String>): LspClient {
    val entry = clients.computeIfAbsent(ToolSetKey.of(tools)) { ... }
    entry.refCount.incrementAndGet()
    return entry.client
}

fun releaseClient(tools: Collection<String>) { ... decrement, dispose if 0 ... }
```

### Verification
Unit-testable: `getClient(emptyList())` returns same instance; `getClient(listOf("a","b"))` vs `getClient(listOf("b","a"))` returns same; different tool sets return different instances.

---

## Phase 3: Tool-Filtered Registration + Pool Session Init

**Goal**: Each pool session's `registerTools()` only sends the tools matching its filter. Pool clients get a simple `serverRequestHandler` that executes tools directly.

### Files
- `conversation/LspSession.kt` — Add `toolFilter: Set<String>` param; filter schemas in `registerTools()`
- `lsp/LspClientPool.kt` — `ensureInitialized(key, ...)` creates and initializes pool sessions

### LspSession.registerTools() filter
```kotlin
// After collecting all schemas, before registration:
val filtered = if (toolFilter.isNotEmpty()) {
    allSchemas.filter { schema ->
        val name = extractToolName(schema)
        name != null && isToolInFilter(name, toolFilter)
    }
} else allSchemas
```

### Pool session serverRequestHandler
For non-default pool clients, the handler is simpler than ConversationManager's:
```kotlin
// Set by LspClientPool when creating a pool session
poolClient.serverRequestHandler = { method, id, params ->
    scope.launch {
        when (method) {
            "conversation/invokeClientToolConfirmation" -> poolClient.sendResponse(id, acceptResult)
            "conversation/invokeClientTool" -> {
                val toolName = params["name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                val toolInput = params["input"]?.jsonObject ?: JsonObject(emptyMap())
                val result = toolRouter.executeTool(toolName, toolInput, wsOverride)
                poolClient.sendResponse(id, result)
            }
            else -> poolClient.sendResponse(id, JsonNull)
        }
    }
}
```

Key difference from default client's handler: no `AgentService.ownsConversation()` routing, no tool filtering, no Chat tab events. Tool calls go straight to execution because the process only has the right tools.

### Verification
Manually create a pool session with `toolFilter = setOf("read_file", "list_dir")`. Check logs: `conversation/registerTools` only sends 2 tools.

---

## Phase 4: Wire SubagentManager to Pool

**Goal**: Subagents get pool clients based on their tool set. The critical correctness change.

### Files
- `agent/AgentService.kt` lines 50, 53-54 — Use pool; pass pool to SubagentManager
- `agent/SubagentManager.kt` lines 26, 142-151, 216/233/249/266 — Use pool for subagent clients; use originating client for responses
- `agent/TeamService.kt` line 128 — Use pool for teammate clients

### Critical: Response routing
When `spawnSubagent()` calls `lspClient.sendResponse(id, toolResult)` (lines 216, 233, 249, 266), the `id` belongs to the **lead's** LSP process. The response MUST go to the lead's client, not the subagent's client.

```kotlin
// SubagentManager now holds pool reference
class SubagentManager(
    private val project: Project,
    private val scope: CoroutineScope,
    private val eventBus: AgentEventBus,
    private val pool: LspClientPool,          // was: lspClient: LspClient
    private val leadClient: LspClient,        // NEW: for sending responses back to lead
    private val conversationManager: ConversationManager,
) {
    // In spawnSubagent():
    val subagentClient = pool.acquireClient(allowedTools)
    pool.ensureInitialized(ToolSetKey.of(allowedTools), ...)

    val session = WorkerSession(
        lspClient = subagentClient,  // subagent's own LSP process
        ...
    )

    // Responses to the lead go through leadClient:
    leadClient.sendResponse(id, toolResult)  // NOT subagentClient
}
```

### AgentService changes
```kotlin
private val pool: LspClientPool get() = LspClientPool.getInstance(project)
private val lspClient: LspClient get() = pool.default  // lead uses default

private val subagentManager by lazy {
    SubagentManager(project, scope, this, pool, lspClient, conversationManager)
}
```

### TeamService changes
```kotlin
// In spawnTeammate():
val pool = LspClientPool.getInstance(project)
val teammateClient = pool.acquireClient(agentDef.tools)
pool.ensureInitialized(ToolSetKey.of(agentDef.tools), ...)
val session = WorkerSession(lspClient = teammateClient, ...)
```

### WorkerSession — NO changes needed
Already takes `LspClient` as constructor param. Pool just provides a different instance.

### Verification
Run a lead agent that delegates to an `explore` subagent. Check logs:
1. Lead runs on default client (all tools registered)
2. Explore subagent creates a new LSP process with only its 5 tools
3. Lead's `delegate_task` response goes back correctly
4. Explore subagent's tool calls execute on its own process

---

## Phase 5: Remove Runtime Tool Filtering

**Goal**: Since each process only has the tools it needs, remove the runtime filtering layer.

### Files
- `agent/AgentService.kt` — Remove `isToolAllowedForConversation()`, `isToolInFilter()`, `leadToolFilter`, `directSubagentToolFilter`
- `agent/SubagentManager.kt` — Remove `subagentToolFilters`, `isToolAllowed()`, `registerToolFilter()`
- `conversation/ConversationManager.kt` lines 314-389 — Remove tool filtering blocks in `handleServerRequest()`
- `agent/AgentService.kt` — Remove `registerTeammateToolFilter()`
- `agent/TeamService.kt` — Remove `registerTeammateToolFilter` calls

### Safety: Keep as deprecated no-op first
```kotlin
@Deprecated("Tool filtering now handled at process level by LspClientPool")
fun isToolAllowedForConversation(conversationId: String?, toolName: String): Boolean = true
```

Remove entirely once validated.

### Verification
Run agent tasks, check logs for absence of "Tool blocked" messages. Verify subagents can only see their registered tools (model never tries to call tools it doesn't know about).

---

## Phase 6: Lifecycle + Cleanup

**Goal**: Reference counting, process cleanup on subagent completion, graceful shutdown.

### Pool lifecycle rules
- Default client (`ToolSetKey.ALL`): never released, lives for project lifetime
- Partitioned clients: reference-counted; released when refcount hits 0
- `LspClientPool.dispose()`: stops ALL clients unconditionally

### SubagentManager cleanup
```kotlin
// In awaitAll(), after collecting each result:
pool.releaseClient(pending.definition.tools)

// In cancelAll():
// release all acquired pool clients
```

### Debug support
```kotlin
fun debugDump(): String {
    return clients.entries.joinToString("\n") { (key, entry) ->
        "  ${key.value}: refs=${entry.refCount.get()}, running=${entry.client.isRunning}"
    }
}
```

### Verification
Run multi-subagent task. After completion, verify `pool.activeCount` returns 1 (only default). Check no orphan processes.

---

## Dependency Graph

```
Phase 0 (extract @Service)
  -> Phase 1 (CachedAuth)
    -> Phase 2 (multi-client pool)
      -> Phase 3 (filtered registration + pool handler)
        -> Phase 4 (wire SubagentManager)  <-- highest risk
          -> Phase 5 (remove runtime filtering)
          -> Phase 6 (lifecycle cleanup)
```

Each phase is independently testable. Phases 0-2 have zero behavior change. Phase 3 is testable in isolation. Phase 4 is the integration point. Phases 5-6 are cleanup.

---

## Risk Matrix

| Risk | Severity | Mitigation |
|------|----------|------------|
| Response routing to wrong process | Critical | Explicit `leadClient` param in SubagentManager; never use subagent client for lead responses |
| serverRequestHandler conflict | High | Pool clients get their own simple handler; default client keeps ConversationManager handler |
| Auth failing on 2nd+ client | Medium | CachedAuth reuses token; signInConfirm is idempotent |
| Missing tool in partition | Medium | Log registered tools per client at INFO; keep deprecated isToolAllowed as safety valve |
| Process leak | Medium | Ref counting + dispose-all on project close + debugDump() |

## Files Summary

| File | Phase | Change |
|------|-------|--------|
| `lsp/LspClient.kt` | 0 | Remove @Service, remove companion, add clientId |
| `lsp/LspClientPool.kt` | 0,2,3,6 | NEW: @Service, pool management, ToolSetKey |
| `lsp/CachedAuth.kt` | 1 | NEW: shared auth state |
| `conversation/LspSession.kt` | 1,3 | Accept CachedAuth + toolFilter; cached init path |
| `conversation/ConversationManager.kt` | 0,5 | Pool indirection; remove tool filtering |
| `agent/AgentService.kt` | 0,4,5 | Pool indirection; pass pool to SubagentManager; remove filtering |
| `agent/SubagentManager.kt` | 4,5,6 | Accept pool + leadClient; acquire/release pool clients |
| `agent/TeamService.kt` | 0,4 | Pool indirection for teammates |
| `orchestrator/WorkerSession.kt` | — | No changes needed |
| `META-INF/plugin.xml` | 0 | Replace LspClient with LspClientPool |
