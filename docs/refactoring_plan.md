# Copilot-Chat Plugin — Clean Architecture Refactoring Plan

**Date**: 2026-02-24
**Status**: **COMPLETE** — All phases + post-refactoring audit shipped as of 2026-02-24
**Scope**: `copilot-chat/src/main/kotlin/com/citigroup/copilotchat/`

---

## Completion Summary

All 6 phases have been implemented and shipped, followed by a post-refactoring audit (7 findings, all resolved):

| Commit | Description |
|--------|-------------|
| (prior sessions) | Split BuiltInTools into domain groups, add Dispatchers.IO, delete CopilotEmbeddings + QdrantManager |
| `db50aec` | Extract SubagentManager from AgentService |
| `c6ef44e` | Extract LspSession from ConversationManager |
| `0129c2d` | Extract SchemaSanitizer from ClientMcpManager |
| `4ae99c6` | Split PsiTools into NavigationTools, IntelligenceTools, RefactoringTools |
| `8aa8ec1` | Replace manual YAML parsing with SnakeYAML, centralize StoragePaths |
| `a632290` | Split AgentEvent into domain-specific LeadEvent, SubagentEvent, TeamEvent |
| `01f0895` | Unify error handling: log silent catches, fix log levels, document emit rules |
| `0790197` | Move PlaywrightManager to browser/, add interfaces, unify MCP transports |
| `5cb0715` | Audit 1+3: Fix silent catch blocks, split LanguageHandlers.kt into per-language files |
| `1be3a41` | Audit 4-7: Dispatcher docs, interface adoption, test path fix, MCP transport sub-package |

---

## Phase 1: Split God-Classes — COMPLETE

### 1A. Split `BuiltInTools.kt` — DONE
Split into domain-focused tool groups (FileTools, SearchTools, ExecutionTools, MemoryTools, WebTools, BrowserTools, DocsTools). ToolSchemaRegistry holds all schema definitions.

### 1B. Split `AgentService.kt` — DONE
- Extracted `SubagentManager` (spawn, await, collect, worktree lifecycle)
- Extracted `AgentEventBus` interface (decouples event emission from AgentService)
- Split `AgentEvent` sealed class into `LeadEvent`, `SubagentEvent`, `TeamEvent`
- AgentService remains as orchestration facade

### 1C. Split `ConversationManager.kt` — DONE
Extracted `LspSession` for LSP lifecycle management. ConversationManager shrunk to conversation orchestration.

---

## Phase 2: Introduce Interfaces — COMPLETE

| Interface | Implementation | Commit |
|-----------|---------------|--------|
| `EmbeddingsProvider` | `LocalEmbeddings` | (prior session) |
| `VectorSearchEngine` | `VectorStore` | (prior session) |
| `McpTransport` | `McpServer`, `McpSseServer` | (prior session) |
| `AgentEventBus` | `AgentService` (internal MutableSharedFlow) | `db50aec` |
| `AgentConfigRepository` | `AgentRegistry` | `0790197` |
| `ToolExecutor` | `ToolRouter` | `0790197` |

---

## Phase 3: Fix UI-to-Infrastructure Leaks — COMPLETE

### 3A. AgentConfigPanel File I/O Extraction — DONE
- Created `AgentConfigRepository` interface
- `AgentRegistry` implements it
- `AgentConfigPanel` takes `configRepo: AgentConfigRepository` constructor param
- All 4 I/O callsites (loadAll, saveAgent, deleteAgentFile, deleteAgentByName) go through the interface

### 3B. Move PlaywrightManager — DONE
Moved from `ui/PlaywrightManager.kt` to `browser/PlaywrightManager.kt`. Layer violation resolved.

---

## Phase 4: Fix Concurrency Issues — COMPLETE

### 4A. Add Dispatchers.IO to Blocking Callsites — DONE
Wrapped all blocking I/O calls in `withContext(Dispatchers.IO)` across agent, rag, and workingset packages.

### 4B. Break Circular Dependency — DONE
`AgentEventBus` interface decouples AgentService and TeamService. Neither holds a direct reference to the other.

---

## Phase 5: Unify MCP Transports — COMPLETE

### 5A. Extract Shared MCP Protocol — DONE
- Created `McpTransportBase` abstract class (~140 lines) with shared JSON-RPC 2.0 protocol: initialize, listTools, callTool, sendRequest, sendNotification, dispatchMessage, request ID tracking
- `McpServer` (stdio) extends `McpTransportBase` — only process lifecycle + stdio I/O remain
- `McpSseServer` (SSE) extends `McpTransportBase` — only SSE connection + HTTP POST remain
- ~120 lines of duplicated protocol logic eliminated

### 5B. Extract Schema Sanitizer — DONE
Extracted `SchemaSanitizer` from `ClientMcpManager` into standalone `mcp/SchemaSanitizer.kt`.

---

## Phase 6: Cleanup & Dead Code Removal — COMPLETE

### 6A. Remove Deprecated RAG Classes — DONE
Deleted `CopilotEmbeddings.kt` and `QdrantManager.kt`.

### 6B. Split PsiTools.kt — DONE
Split 1509-line file into 4 files:
- `PsiTools.kt` (~150 lines, registry + helpers)
- `NavigationTools.kt` (11 tools)
- `IntelligenceTools.kt` (5 tools)
- `RefactoringTools.kt` (3 tools + shared utility)

### 6C. Centralize Storage Paths — DONE
Created `config/StoragePaths.kt` with centralized path resolution. Updated 8 files (15 callsites). Zero hardcoded `~/.copilot-chat/` references remain in production code. `userRootOverride` field enables test injection.

### 6D. Unify Error Handling — DONE
- Added `log.warn()` to 5 silent catch blocks in WorktreeManager
- Promoted unrecoverable subagent failures from `warn` to `error` level
- Documented `emit()` vs `tryEmit()` usage rules on AgentEventBus
- Fixed CancellationException handling consistency (rethrow instead of swallow)
- Replaced manual YAML parsing with SnakeYAML in AgentRegistry

---

## Post-Refactoring Audit — COMPLETE

7 findings identified, all resolved across commits `5cb0715` and `1be3a41`.

### 1. Silent Catch Blocks (High) — DONE
Added `log.warn()` to 10 silent catch sites across 6 files (WorkerSession, ToolsPanel, AgentConfigPanel, RecorderPanel, RagIndexer, RefactoringTools). Intentionally silent catches (LanguageHandlers reflection, JSON parsing loops) left as-is.

### 2. Unused Imports in LanguageHandlers.kt (Low) — DONE
Resolved as part of item 3 (file split removed stale imports).

### 3. LanguageHandlers.kt God-Class (High) — DONE
Split 2,667 LOC file into 7 files:
- `LanguageHandlers.kt` (321 LOC) — interfaces, registry, utilities, OptimizedSymbolSearch
- `JavaKotlinHandlers.kt` (637 LOC)
- `PythonHandlers.kt` (351 LOC)
- `JavaScriptHandlers.kt` (384 LOC)
- `GoHandlers.kt` (252 LOC)
- `RustHandlers.kt` (401 LOC)
- `PhpHandlers.kt` (365 LOC)

### 4. Inconsistent Dispatcher Documentation (Medium) — DONE
All 10 CoroutineScope sites now have inline comments justifying their dispatcher choice (Main for Swing UI, IO for blocking I/O, Default for orchestration).

### 5. Incomplete Interface Adoption (Medium) — DONE
- `VectorStore.getInstance()` return type changed to `VectorSearchEngine`
- `MemoryTools`, `RagIndexer`, `RagQueryService` use `EmbeddingsProvider` property instead of direct `LocalEmbeddings` calls

### 6. Test Hardcoded Path (Low) — DONE
`RagIntegrationTest.kt` now uses `StoragePaths.userRoot` instead of hardcoded `~/.copilot-chat/`.

### 7. MCP Package Sub-Organization (Low) — DONE
Moved 4 transport files to `mcp/transport/` sub-package: `McpTransport`, `McpTransportBase`, `McpServer`, `McpSseServer`. `ClientMcpManager` and `SchemaSanitizer` remain in root `mcp/`.

---

## Architecture After Refactoring

```
┌─────────────────────────────────────────────────────────────────┐
│  UI Layer (presentation only)                                    │
│  AgentPanel, AgentConfigPanel, RecorderPanel, WorkingSetPanel    │
│  Consumes: SharedFlow<AgentEvent>, calls interface methods       │
│  AgentConfigPanel → AgentConfigRepository (injected)             │
└──────────────────────────┬──────────────────────────────────────┘
                           │ interfaces only
┌──────────────────────────▼──────────────────────────────────────┐
│  Domain Layer (orchestration + business rules)                   │
│  AgentService, SubagentManager, TeamService, ConversationManager │
│  AgentEventBus (LeadEvent / SubagentEvent / TeamEvent)           │
│  ToolRouter : ToolExecutor                                       │
│  Depends on: EmbeddingsProvider, VectorSearchEngine,             │
│              McpTransport, AgentConfigRepository                 │
└──────────────────────────┬──────────────────────────────────────┘
                           │ implementations
┌──────────────────────────▼──────────────────────────────────────┐
│  Infrastructure Layer                                            │
│  mcp/transport/: McpTransportBase → McpServer, McpSseServer      │
│  VectorStore, LocalEmbeddings, LspSession, StoragePaths          │
│  SchemaSanitizer, PlaywrightManager (browser/)                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## What's Already Good (Preserved)

- **SharedFlow event patterns** — `LeadEvent`, `SubagentEvent`, `TeamEvent`, `ChatEvent`, `WorkingSetEvent`
- **WorkingSet lifecycle** — `captureBeforeState` → `captureAfterState` → `revert`/`accept`
- **WorktreeManager** — focused, self-contained
- **PsiToolBase template method** — good abstraction for PSI operations
- **WordPieceTokenizer** — single-concern, thread-safe, zero deps
- **VectorStore cosine similarity** — correct, clean Kotlin implementation
- **Dirty state tracking** in AgentConfigPanel — `withoutTracking()` pattern
- **ChatInputPanel** — pure presentation, no business logic leaks
