# Copilot Plugin Tool Invocation — How It Works & How We Aligned

This document explains how the GitHub Copilot IntelliJ plugin resolves the correct project
at tool invocation time, and how the speckit-plugin was updated to use the exact same pattern.

---

## The Problem

The Copilot plugin's `ToolRegistryImpl` is **application-scoped** (singleton). Tools registered
into it persist across all open projects. When a user has two projects open — say Project A and
Project B — the registry holds one set of tool instances shared by both.

The original speckit-plugin bound each tool to a specific project's `basePath` at construction
time, inside `postStartupActivity`. This created three bugs:

1. **Double registration** — `postStartupActivity` runs per project; each project tried to
   register the same 25 tools again into the shared registry.
2. **Wrong project** — All tool instances were locked to the first project's `basePath`. If the
   user opened Copilot Chat in Project B, tools would read/write files in Project A.
3. **Stale state** — If the user closed and reopened projects, the stored `basePath` pointed to
   a potentially disposed project.

---

## How the Copilot Plugin Solves This

### Key Classes (decompiled from `core.jar`)

| Class | Scope | Purpose |
|-------|-------|---------|
| `ToolRegistryImpl` | Application | Singleton tool registry — holds all registered tool instances |
| `ToolInvocationRequest` | Per-call | Carries `name`, `input`, `conversationId`, `turnId`, `toolCallId`, `roundId` |
| `ToolInvocationIdentifier` | Per-call | Subset of request fields: `conversationId`, `turnId`, `toolCallId`, `roundId` |
| `ToolInvocationManager` | Application | Service that maps a `ToolInvocationIdentifier` to the correct `Project` |
| `LanguageModelToolRegistration` | Interface | Contract: `toolDefinition` + `suspend handleInvocation(request)` |

### The Pattern — `CreateFileTool` (decompiled)

Copilot's own `CreateFileTool` is a Kotlin `object` (singleton) with zero constructor parameters:

```kotlin
object CreateFileTool : LanguageModelToolRegistration {
    const val TOOL_NAME = "create_file"

    override val toolDefinition by lazy { /* schema */ }

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        // 1. Get the application-level ToolInvocationManager service
        val manager = ApplicationManager.getApplication()
            .getService(ToolInvocationManager::class.java)

        // 2. Resolve which project this invocation belongs to
        val project = manager.findProjectForInvocation(request.identifier)
            ?: return LanguageModelToolResult.Companion.error(
                "Error: Cannot find project for tool invocation"
            )

        // 3. Use the project for file operations
        // ...
    }
}
```

### How `findProjectForInvocation` Works

The method signature (from bytecode with annotations):

```kotlin
// @Nullable return, @NotNull parameters
suspend fun findProjectForInvocation(
    identifier: ToolInvocationIdentifier
): Project?
```

Internally, `ToolInvocationManager` correlates the `conversationId`/`turnId` from the
invocation request with the project that initiated the Copilot Chat conversation. The Copilot
server tracks which conversation belongs to which editor window, and the manager resolves this
mapping on the client side.

### `ToolInvocationRequest.getIdentifier()`

The request object exposes a computed `identifier` property that bundles the routing fields:

```kotlin
data class ToolInvocationRequest(
    val name: String,
    val input: JsonObject?,
    val conversationId: String,
    val turnId: String,
    val toolCallId: String,
    val roundId: Number
) {
    val identifier: ToolInvocationIdentifier
        get() = ToolInvocationIdentifier(conversationId, turnId, toolCallId, roundId.toInt())
}
```

### Registration Pattern

All of Copilot's built-in tools (`CreateFileTool`, `EditFileTool`, `RunInTerminalTool`,
`GetErrorsTool`, `OpenFileTool`, `ShowContentTool`, `GetTerminalOutputTool`) are registered
once into `ToolRegistryImpl`. They are stateless singletons — no project or path is stored
at construction time.

---

## How speckit-plugin Was Aligned

### Before (broken)

```kotlin
// SpeckitPluginInstaller.kt — ran per project
class SpeckitPluginInstaller : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        val basePath = project.basePath ?: return    // <-- locked to first project
        val tools = listOf(
            SpeckitSpecifyAgent(basePath),            // <-- basePath baked in
            SpeckitWriteMemory(project),              // <-- project baked in
            // ... 25 tools total
        )
        for (tool in tools) registry.registerTool(tool)
    }
}

// Each tool stored basePath as a field
class SpeckitSpecifyAgent(private val basePath: String) : AgentTool(
    basePath = basePath,
    // ...
)
```

### After (aligned)

#### 1. Registration Guard (`SpeckitPluginInstaller.kt`)

```kotlin
class SpeckitPluginInstaller : StartupActivity.DumbAware {
    companion object {
        private val registered = AtomicBoolean(false)
    }

    override fun runActivity(project: Project) {
        if (!registered.compareAndSet(false, true)) {
            log.info("Spec-Kit tools already registered, skipping")
            return
        }
        try {
            registerTools()
        } catch (e: Exception) {
            registered.set(false)  // allow retry on failure
            log.warn("Spec-Kit plugin tool registration failed", e)
        }
    }

    private fun registerTools() {
        val tools = listOf(
            SpeckitSpecifyAgent(),     // <-- no-arg constructor
            SpeckitWriteMemory(),      // <-- no-arg constructor
            // ... 25 tools total
        )
        for (tool in tools) registry.registerTool(tool)
    }
}
```

#### 2. Dynamic Project Resolution (every tool)

Every tool's `handleInvocation` now starts with:

```kotlin
override suspend fun handleInvocation(
    request: ToolInvocationRequest
): LanguageModelToolResult {
    val manager = ApplicationManager.getApplication()
        .getService(ToolInvocationManager::class.java)
    val project = manager.findProjectForInvocation(request.identifier)
        ?: return LanguageModelToolResult.Companion.error("No project found for invocation")
    val basePath = project.basePath
        ?: return LanguageModelToolResult.Companion.error("No project base path")

    // ... tool logic uses basePath as a local variable
}
```

#### 3. `AgentTool` Base Class

The abstract `AgentTool` base (used by 9 agent subclasses) resolves the project once and
passes `basePath` down to the subclass:

```kotlin
abstract class AgentTool(
    private val toolName: String,          // no basePath parameter
    private val toolDescription: String,
    private val agentFileName: String,
) : LanguageModelToolRegistration {

    override suspend fun handleInvocation(request: ToolInvocationRequest): LanguageModelToolResult {
        val manager = ApplicationManager.getApplication().getService(ToolInvocationManager::class.java)
        val project = manager.findProjectForInvocation(request.identifier)
            ?: return LanguageModelToolResult.Companion.error("No project found for invocation")
        val basePath = project.basePath
            ?: return LanguageModelToolResult.Companion.error("No project base path")

        // ... loads agent instructions, constitution, etc. using basePath

        val extra = gatherExtraContext(request, basePath)  // basePath passed to subclass
        // ...
    }

    // Subclasses override this — basePath is a parameter, not a field
    protected open fun gatherExtraContext(request: ToolInvocationRequest, basePath: String): String = ""

    // Helper methods also take basePath as parameter
    protected fun readFileIfExists(basePath: String, relativePath: String): String? { /* ... */ }
    protected fun readFeatureArtifact(basePath: String, featureDir: String, fileName: String): String? { /* ... */ }
    protected fun findCurrentFeatureDir(basePath: String): String? { /* ... */ }
}
```

#### 4. `SpeckitWriteMemory` — Project for `WriteCommandAction`

This tool needs a `Project` instance (not just `basePath`) for IntelliJ's write-action API.
It now resolves the project dynamically, matching how it was previously injected:

```kotlin
class SpeckitWriteMemory : LanguageModelToolRegistration {
    override suspend fun handleInvocation(request: ToolInvocationRequest): LanguageModelToolResult {
        val manager = ApplicationManager.getApplication().getService(ToolInvocationManager::class.java)
        val project = manager.findProjectForInvocation(request.identifier)
            ?: return LanguageModelToolResult.Companion.error("No project found for invocation")
        val basePath = project.basePath
            ?: return LanguageModelToolResult.Companion.error("No project base path")

        // project is now a local variable — used for WriteCommandAction
        WriteCommandAction.runWriteCommandAction(project) { /* ... */ }
    }
}
```

---

## Verification — Bytecode Comparison

Decompiled from `core.jar` (`CreateFileTool.handleInvocation` bytecode):

| Offset | Instruction | What it does |
|--------|-------------|--------------|
| 257 | `ldc ToolInvocationManager.class` | Load the class reference |
| 261 | `ApplicationManager.getApplication()` | Get the application instance |
| 266 | `Application.getService(Class)` | Get the ToolInvocationManager service |
| 348 | `ToolInvocationRequest.getIdentifier()` | Extract the routing identifier |
| 379 | `ToolInvocationManager.findProjectForInvocation(identifier, continuation)` | Suspend call to resolve project |
| 428 | `checkcast Project` | Cast result to Project |
| 432 | `ifnonnull` → continue | Null check |
| 436-456 | `LanguageModelToolResult.Companion.error(...)` + `areturn` | Return error if null |
| 457 | `astore` → continue with project | Store non-null project as local |

Our speckit-plugin tools produce the same bytecode pattern after compilation.

---

## Files Changed

All files are in `speckit-plugin/src/main/kotlin/com/speckit/plugin/`.

### Installer
- `installer/SpeckitPluginInstaller.kt` — `AtomicBoolean` guard, no-arg tool constructors

### Agent Base + 10 Agent Subclasses
- `tools/agents/AgentTool.kt` — Removed `basePath` constructor param, dynamic resolution
- `tools/agents/SpeckitConstitutionAgent.kt`
- `tools/agents/SpeckitSpecifyAgent.kt`
- `tools/agents/SpeckitClarifyAgent.kt`
- `tools/agents/SpeckitPlanAgent.kt`
- `tools/agents/SpeckitTasksAgent.kt`
- `tools/agents/SpeckitAnalyzeAgent.kt`
- `tools/agents/SpeckitChecklistAgent.kt`
- `tools/agents/SpeckitImplementAgent.kt`
- `tools/agents/SpeckitTasksToIssuesAgent.kt`
- `tools/agents/SpeckitCoverageAgent.kt`

### 14 Standalone Tools
- `tools/SpeckitAnalyzeProject.kt`
- `tools/SpeckitSetupFeature.kt`
- `tools/SpeckitSetupPlan.kt`
- `tools/SpeckitUpdateAgents.kt`
- `tools/SpeckitDiscover.kt`
- `tools/SpeckitRunTests.kt`
- `tools/SpeckitParseCoverage.kt`
- `tools/SpeckitListAgents.kt`
- `tools/SpeckitReadAgent.kt`
- `tools/SpeckitListTemplates.kt`
- `tools/SpeckitReadTemplate.kt`
- `tools/SpeckitListSpecs.kt`
- `tools/SpeckitReadSpec.kt`
- `tools/SpeckitReadMemory.kt`
- `tools/SpeckitWriteMemory.kt`

### Unchanged
- `tools/ResourceLoader.kt` — Already takes `basePath` as a function parameter
- `tools/ScriptRunner.kt` — Stateless utility, no project dependency
- `tools/FeatureWorkspace.kt` — Already takes `basePath` as a function parameter
- `META-INF/plugin.xml` — No changes needed
