# Copilot-Chat Plugin — Clean Architecture Refactoring Plan

**Date**: 2026-02-24
**Status**: **COMPLETE** — All phases shipped as of 2026-02-24
**Scope**: `copilot-chat/src/main/kotlin/com/citigroup/copilotchat/`

---

## Completion Summary

All 6 phases have been implemented and shipped across the following commits:

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
│  McpTransportBase → McpServer (stdio), McpSseServer (SSE)        │
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
