# Refactoring Steps — Progress Tracker

**Last updated**: 2026-02-24
**Status**: **ALL COMPLETE**

See [refactoring_plan.md](refactoring_plan.md) for full rationale and architecture diagrams.

---

## Phase 4A: Dispatchers.IO Fixes (Quick Win) — DONE
- [x] `WorktreeManager.runGit()` — wrap ProcessBuilder in `withContext(Dispatchers.IO)`
- [x] `Mailbox` file reads/writes — wrap in `withContext(Dispatchers.IO)`
- [x] `LocalEmbeddings.embed()` — wrap ONNX session.run in `withContext(Dispatchers.IO)`
- [x] `CopilotCommitService.commit()` — wrap git process in `withContext(Dispatchers.IO)`
- [x] `VectorStore.load()/save()` — wrap JSON file I/O in `withContext(Dispatchers.IO)`

## Phase 6A: Delete Dead Code (Quick Win) — DONE
- [x] Delete `rag/CopilotEmbeddings.kt` — replaced by LocalEmbeddings
- [x] Delete `rag/QdrantManager.kt` — replaced by VectorStore
- [x] Grep for remaining imports, remove stale references

## Phase 1A: Split BuiltInTools — DONE
- [x] Create domain-focused tool groups (FileTools, SearchTools, ExecutionTools, etc.)
- [x] Create `tools/ToolSchemaRegistry.kt` (all schemas as declarative map)
- [x] Update `ToolRouter` to delegate via registry
- [x] Verify: buildPlugin compiles

## Phase 1C: Split ConversationManager — DONE
- [x] Extract `LspSession` (LSP lifecycle management)
- [x] Slim ConversationManager to conversation orchestration
- [x] Verify: plugin initializes, authenticates, streams responses

## Phase 2: Introduce Interfaces — DONE
- [x] Define `EmbeddingsProvider` interface in `rag/` — `LocalEmbeddings` implements
- [x] Define `VectorSearchEngine` interface in `rag/` — `VectorStore` implements
- [x] Define `McpTransport` interface in `mcp/` — `McpServer`, `McpSseServer` implement
- [x] Define `AgentEventBus` interface in `agent/` — `AgentService` implements
- [x] Define `AgentConfigRepository` interface in `agent/` — `AgentRegistry` implements
- [x] Define `ToolExecutor` interface in `tools/` — `ToolRouter` implements
- [x] Update consumers to depend on interface type

## Phase 1B: Split AgentService — DONE
- [x] Extract `agent/AgentEventBus.kt` interface
- [x] Extract `agent/SubagentManager.kt` (spawn/await/collect/worktree lifecycle)
- [x] Split `AgentEvent` into `LeadEvent`, `SubagentEvent`, `TeamEvent`
- [x] AgentService remains as orchestration facade
- [x] Update AgentPanel + TeamService to use AgentEventBus

## Phase 3: Fix UI-to-Infrastructure Leaks — DONE
- [x] Create `AgentConfigRepository` interface
- [x] `AgentRegistry` implements `AgentConfigRepository`
- [x] `AgentConfigPanel` takes `configRepo: AgentConfigRepository` (constructor injection)
- [x] All 4 I/O callsites go through the interface
- [x] Move `PlaywrightManager` from `ui/` to `browser/` package

## Phase 5: Unify MCP Transports — DONE
- [x] Create `McpTransportBase` abstract class (shared JSON-RPC protocol)
- [x] `McpServer` extends `McpTransportBase` (process lifecycle + stdio only)
- [x] `McpSseServer` extends `McpTransportBase` (SSE connection + HTTP POST only)
- [x] ~120 lines of duplicated protocol logic eliminated
- [x] Extract `mcp/SchemaSanitizer.kt` (pure function)

## Phase 6B-D: Cleanup — DONE
- [x] Split `PsiTools.kt` into NavigationTools, IntelligenceTools, RefactoringTools
- [x] Centralize storage paths via `config/StoragePaths.kt` (replaces hardcoded `~/.copilot-chat/`)
- [x] Unify error handling: fix silent catches, align log levels, document emit rules
- [x] Replace manual YAML parsing with SnakeYAML in AgentRegistry
