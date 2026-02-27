# Agent Tab Architecture

Conceptual guide to the Agent tab's multi-agent orchestration system. Covers design decisions, concurrency model, bug history, and lessons learned.

---

## System Overview

The Agent tab implements a lead-agent/subagent delegation pattern on top of the GitHub Copilot Language Server (LSP). A single lead agent conversation coordinates autonomous subagents via the `delegate_task` tool. Subagents run in parallel within a round, their results are collected after the lead's turn ends, and a follow-up turn feeds results back for synthesis.

The LSP server was not designed for multi-agent use. Most of the architecture's complexity comes from working around that constraint — multiplexing conversations over a single process, routing tool calls by conversation ID, and handling race conditions in the LSP protocol.

---

## Component Map

```
User Input
    |
    v
AgentPanel (Swing UI, EDT)
    |
    v
AgentService (lead orchestrator, Dispatchers.Default)
    |
    +---> WorkerSession (per-subagent conversation)
    |         |
    |         +---> LspClient (shared singleton, IO thread)
    |                   |
    |                   +---> copilot-language-server (subprocess, stdin/stdout)
    |
    +---> ToolRouter (PSI > ide-index > built-in tools)
    |
    +---> TeamService (persistent teammates, mailbox-based)
    |
    v
ConversationManager (LSP init, server request routing, tool enforcement)
```

### Responsibilities

| Component | Role |
|-----------|------|
| **AgentService** | Owns lead conversation, spawns subagents, routes tool calls, multi-round wait loop |
| **WorkerSession** | Lightweight conversation wrapper for one agent. Own conversationId, own workDoneToken |
| **ConversationManager** | LSP initialization, routes ALL server requests (tool calls) to the right owner, enforces tool restrictions |
| **ToolRouter** | Dispatches tool calls with priority chain: PSI tools > ide-index > built-in |
| **LspClient** | Singleton. Manages the LSP subprocess, JSON-RPC framing, progress listener multiplexing |
| **AgentRegistry** | Loads agent definitions: 4 built-in + custom `.md` files from `.claude/agents/` |
| **AgentPanel** | Swing UI. Observes `AgentService.events` via coroutine collection on EDT |

---

## Message Lifecycle

### 1. User sends a message

```
AgentPanel.sendMessage(text)
  -> AgentService.sendMessage(text)
    -> Load agent definitions from registry
    -> Register progress listener for workDoneToken
    -> First turn: conversation/create with system prompt
    -> Subsequent: conversation/turn
    -> Enter multi-round wait loop
```

### 2. Streaming responses

```
LSP sends $/progress notifications
  -> LspClient routes to progressListeners[workDoneToken]
  -> AgentService.handleLeadProgress()
    -> Extracts reply/delta/message text -> replyParts
    -> tryEmit(LeadDelta) for UI [non-blocking, may drop]
    -> Parses editAgentRounds for tool call display
    -> On kind="end": isStreaming = false
```

### 3. Tool calls

```
LSP sends conversation/invokeClientTool request
  -> ConversationManager.handleServerRequest()
    -> Check agentService.ownsConversation(conversationId)
    -> If lead conversation: AgentService.handleToolCall()
      -> delegate_task: spawn subagent, respond immediately
      -> standard tools: ToolRouter.executeTool(), respond
    -> If subagent conversation:
      -> Hard-enforce tool filter (allowlist/blocklist)
      -> ToolRouter.executeTool(), respond
```

### 4. Subagent delegation

```
Lead calls delegate_task
  -> Create WorkerSession with system prompt + tool restrictions
  -> Register onConversationId callback (installs tool filter)
  -> Launch via scope.async (background)
  -> Respond immediately to LSP: "subagent spawned"

Lead turn ends (isStreaming = false):
  -> Wait loop detects pendingSubagents.isNotEmpty()
  -> awaitPendingSubagents(): collect all results
  -> sendFollowUpTurn(): feed results back to lead
  -> Re-enter wait loop for synthesis turn
  -> If lead fires more subagents, repeat
```

### 5. Multi-round delegation

The lead model can delegate in rounds when tasks have dependencies:

- **Round 1:** Fire all independent subtasks (parallel within round)
- Lead receives results in follow-up turn
- **Round 2:** Fire dependent subtasks that needed Round 1 output
- Repeat until done
- **Final round:** Synthesize all results

The wait loop in `sendMessage` handles this automatically — it re-enters whenever subagents are pending after a lead turn ends.

---

## Concurrency Model

### Thread topology

| Thread/Dispatcher | Components |
|-------------------|-----------|
| `Dispatchers.IO` | LspClient reader, progress callbacks, stderr drain |
| `Dispatchers.Default` | AgentService scope, WorkerSession suspend functions, tool call handlers |
| `Dispatchers.Main` (EDT) | AgentPanel UI updates |

### Synchronization

| Mechanism | Where | Why |
|-----------|-------|-----|
| `ConcurrentHashMap` | activeSubagents, pendingSubagents, subagentToolFilters, progressListeners | Concurrent read/write from IO and Default threads |
| `Collections.synchronizedList` | replyParts | Progress handler (IO) writes, coroutine (Default) reads |
| `@Volatile` | leadConversationId, pendingLeadCreate, isStreaming | Single-writer visibility across threads |
| `Mutex` | ConversationManager.initMutex | Prevent concurrent LSP initialization |
| `@Synchronized` | LspTransport.sendMessage | Prevent interleaved writes to process stdin |
| `AtomicInteger` | LspClient.requestId | Thread-safe ID generation |

### The SharedFlow buffer problem

`MutableSharedFlow<AgentEvent>(extraBufferCapacity = 512)`

- **Producers:** Progress handler (IO callback), subagent event callbacks (IO callback)
- **Consumer:** AgentPanel coroutine (Main/EDT)
- If production exceeds consumption, `tryEmit()` drops events silently
- `LeadDone(fullText)` carries the complete accumulated reply as a safety net — correctness depends on `replyParts`, not on the stream of delta events

**Key insight:** The event system is an eventually-consistent UI update mechanism, not a reliable message bus.

---

## Tool Restriction Enforcement

### Two-layer design

**Layer 1 — Prompt-based (soft):**
WorkerSession injects `<tool_restrictions>` on first turn telling the model which tools it may use.

**Layer 2 — Hard enforcement:**
ConversationManager checks `AgentService.isToolAllowedForConversation(conversationId, toolName)` before executing any tool. Returns an error response if blocked.

Filter registration happens via WorkerSession's `onConversationId` callback, which fires when the subagent's conversation is created.

**Why two layers:** Models (especially gpt-4.1) sometimes ignore prompt-based restrictions. Hard enforcement catches this. There's a brief race window (~500ms) before the conversation is created where only prompt-based restriction applies.

**Recursive delegation prevention:** All agent definitions set `disallowedTools = ["delegate_task", "create_team", "send_message", "delete_team"]`, preventing subagents from spawning their own subagents.

---

## Race Conditions

### conversationId capture race

The LSP server sends `conversation/invokeClientTool` requests BEFORE `conversation/create` returns the conversationId. Solution: `pendingLeadCreate` flag. When true, `ownsConversation()` captures the conversationId from the first arriving tool call's params.

### Tool filter registration race

Tool calls can arrive before `onConversationId` fires (the subagent's `conversation/create` hasn't returned yet). During this window, only prompt-based tool restrictions apply. The hard filter is registered as soon as the conversationId is captured.

---

## Bug History and Lessons

### Deadlock #1: SharedFlow emit() blocking tool responses

**Symptom:** Conversation froze after ~15 tool calls.

**Root cause:** `handleLeadProgress` emitted events via suspending `emit()`. When the SharedFlow buffer (originally 64) filled, `emit()` suspended on a Default thread. The tool call handler (same thread pool) couldn't get a thread to send the response back to LSP. Deadlock.

**Fix:** Switch all hot-path emissions from `emit()` to `tryEmit()` (non-suspending). Accept dropped UI events. Buffer increased to 512.

**Lesson:** Never put suspending operations in a hot path that shares thread pool resources with other critical operations.

### Deadlock #2: Double event emission

**Symptom:** Buffer filled twice as fast as expected.

**Root cause:** Both `handleToolCall` and `handleLeadProgress` emitted `LeadToolCall`/`LeadToolResult` for the same tool calls. Double emission filled the buffer.

**Fix:** Removed duplicate emissions from `handleToolCall` (the critical response path). Progress handler is the sole event source.

### Race condition: delegate_task not recognized

**Symptom:** `delegate_task` tool calls were routed as standard tools instead of spawning subagents.

**Root cause:** Tool call arrived before `conversation/create` returned the lead's conversationId. `ownsConversation()` returned false.

**Fix:** `pendingLeadCreate` flag captures the conversationId from the tool call itself.

### Silent failure: Invalid model IDs

**Symptom:** Conversation created successfully but produced no output.

**Root cause:** The Copilot LSP server's model catalog only accepts specific IDs. Invalid IDs (e.g., `"claude-3-haiku"`) silently fail.

**Lesson:** All model tiers currently resolve to `"gpt-4.1"`. Document the valid model IDs.

### Empty subagent output

**Symptom:** Subagent completed with checkmark but showed no content.

**Root causes:**
1. `replyParts` was an unsynchronized list written from multiple threads
2. gpt-4.1 sometimes produces agent turns with tool calls but no reply text

**Fixes:**
1. Made `replyParts` thread-safe via `Collections.synchronizedList`
2. Added follow-up turn: if subagent returns empty, send another turn asking for a summary
3. If still empty after retry, mark as error

---

## Design Trade-offs

### Single LSP process, multiplexed conversations
All conversations share one `copilot-language-server` process. Saves resources but means all tool calls flow through one `serverRequestHandler`, requiring conversation-based routing. The `workDoneToken`-based progress routing works well for multiplexing streaming responses.

### Immediate tool response for delegate_task
Responding immediately to the LSP ("subagent spawned") and running the subagent in the background allows multiple parallel `delegate_task` calls within a single turn. If we blocked until completion, the LSP would serialize subagent execution.

### run_in_terminal hijacking
The Copilot server only forwards tool names on its internal allowlist. `delegate_task` is not on it. Delegation commands are smuggled via `run_in_terminal` with a `delegate --type X --prompt "..."` command string. Fragile but functional.

### Polling-based wait loops
Both AgentService and WorkerSession use `delay(100)` polling loops with a DONE_SENTINEL pattern. Simple, survived multiple bug-fix iterations. A `CompletableDeferred` approach would be more efficient but adds complexity.

### tryEmit vs emit
`tryEmit()` drops events when the buffer is full. This is acceptable because correctness depends on the accumulated `replyParts` (reliable data store), not on the stream of delta events (best-effort UI updates). `LeadDone(fullText)` is the safety net.

---

## Agent Definition System

### Built-in agents (4)

| Type | Tools | Model | Purpose |
|------|-------|-------|---------|
| Explore | ide, read_file, list_dir, grep_search, file_search | HAIKU | Fast read-only codebase exploration |
| Plan | ide, read_file, list_dir, grep_search, file_search | INHERIT | Architecture and implementation planning |
| Bash | run_in_terminal | INHERIT | Command execution |
| general-purpose | all | INHERIT | Fallback for complex multi-step tasks |

### Custom agents

Loaded from `.claude/agents/*.md` (project-level) and `~/.claude/agents/*.md` (user-level). YAML frontmatter for metadata, markdown body for system prompt.

```yaml
---
name: my-agent
description: Does something
tools: [read_file, grep_search]
model: haiku
maxTurns: 10
---
System prompt body...
```

All custom agents inherit the same `disallowedTools` blocklist as built-ins.
