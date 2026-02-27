# Electron Project Structure

Electron app that spawns the Copilot LSP server directly from Node.js, with React + Tailwind UI and React Flow agent visualization.

---

## Directory Layout

```
copilot-electron/
├── package.json
├── electron-builder.yml
├── tailwind.config.js
├── tsconfig.json
├── tsconfig.node.json
│
├── src/
│   ├── main/                          # Electron main process (Node.js)
│   │   ├── index.ts                   # App entry, window creation
│   │   ├── ipc.ts                     # IPC channel registration (main ↔ renderer)
│   │   │
│   │   ├── lsp/                       # LSP client layer
│   │   │   ├── transport.ts           # Content-Length framing over stdin/stdout
│   │   │   ├── client.ts             # JSON-RPC dispatch, request/response matching
│   │   │   ├── binary.ts             # Binary discovery (JetBrains, VS Code paths)
│   │   │   └── auth.ts               # apps.json token discovery, signInConfirm
│   │   │
│   │   ├── agent/                     # Orchestration layer
│   │   │   ├── agent-service.ts       # Lead agent, subagent spawning, multi-round loop
│   │   │   ├── worker-session.ts      # Single-agent conversation wrapper
│   │   │   ├── agent-registry.ts      # Built-in + custom .md agent definitions
│   │   │   ├── agent-models.ts        # AgentDefinition, SubagentToolFilter types
│   │   │   └── tool-filter.ts         # Allowlist/blocklist enforcement
│   │   │
│   │   ├── tools/                     # Tool execution
│   │   │   ├── tool-router.ts         # Routes tool calls: MCP → built-in
│   │   │   ├── built-in-tools.ts      # File ops, grep, terminal, search
│   │   │   └── tool-schemas.ts        # Tool registration schemas for LSP
│   │   │
│   │   ├── mcp/                       # MCP client
│   │   │   ├── mcp-manager.ts         # Server-side + client-side MCP handling
│   │   │   ├── mcp-transport.ts       # stdio and SSE transports
│   │   │   └── psi-bridge.ts          # SSE client to IntelliJ PSI MCP server
│   │   │
│   │   └── conversation/              # Conversation lifecycle
│   │       ├── conversation-manager.ts # Init sequence, server request handler
│   │       └── conversation-state.ts   # Per-conversation state tracking
│   │
│   ├── renderer/                      # Electron renderer process (React)
│   │   ├── index.html
│   │   ├── main.tsx                   # React entry point
│   │   ├── App.tsx                    # Root layout, tab routing
│   │   │
│   │   ├── stores/                    # State management
│   │   │   ├── agent-store.ts         # Agent events → React state (Zustand)
│   │   │   ├── flow-store.ts          # React Flow nodes/edges state
│   │   │   └── settings-store.ts      # User preferences, proxy config
│   │   │
│   │   ├── components/                # Shared UI components
│   │   │   ├── ChatInput.tsx          # Message input with model selector
│   │   │   ├── MarkdownRenderer.tsx   # Streaming markdown display
│   │   │   ├── StatusBar.tsx          # Connection status, model info
│   │   │   └── Sidebar.tsx            # Conversation history, settings
│   │   │
│   │   ├── views/                     # Tab views
│   │   │   ├── ChatView.tsx           # Standard chat (no flow graph)
│   │   │   └── AgentView.tsx          # Agent tab with flow visualization
│   │   │
│   │   └── flow/                      # React Flow components
│   │       ├── AgentFlow.tsx          # React Flow canvas, layout engine
│   │       ├── layout.ts             # Dagre/ELK layout computation
│   │       │
│   │       ├── nodes/                 # Custom node components
│   │       │   ├── LeadNode.tsx       # Lead agent node
│   │       │   ├── SubagentNode.tsx   # Subagent node
│   │       │   ├── ToolNode.tsx       # Tool call node
│   │       │   ├── UserNode.tsx       # User message node
│   │       │   └── RoundDivider.tsx   # Visual round separator
│   │       │
│   │       ├── edges/                 # Custom edge components
│   │       │   └── AnimatedEdge.tsx   # Pulsing edge for in-progress connections
│   │       │
│   │       └── panels/               # Flow overlay panels
│   │           ├── NodeDetail.tsx     # Expanded view when clicking a node
│   │           └── FlowControls.tsx   # Zoom, fit, minimap toggle
│   │
│   ├── shared/                        # Types shared between main and renderer
│   │   ├── events.ts                  # AgentEvent type definitions
│   │   ├── ipc-channels.ts           # IPC channel name constants
│   │   └── types.ts                   # AgentDefinition, ToolSchema, etc.
│   │
│   └── preload/
│       └── index.ts                   # contextBridge exposing IPC to renderer
│
├── resources/                         # Static assets
│   ├── icons/
│   └── agents/                        # Default agent .md definitions
│
└── tests/
    ├── main/
    │   ├── lsp/transport.test.ts
    │   ├── agent/agent-service.test.ts
    │   └── tools/tool-router.test.ts
    └── renderer/
        ├── flow/AgentFlow.test.tsx
        └── stores/flow-store.test.ts
```

---

## Data Flow

```
┌─────────────────────────────────────────────────────────┐
│  Renderer Process (Chromium)                            │
│                                                         │
│  AgentView.tsx                                          │
│    ├── ChatInput ──→ ipcRenderer.send('agent:message')  │
│    └── AgentFlow.tsx (React Flow canvas)                │
│          ├── reads from flow-store (Zustand)            │
│          └── nodes: LeadNode, SubagentNode, ToolNode    │
│                                                         │
│  agent-store.ts                                         │
│    └── ipcRenderer.on('agent:event') → updates stores   │
│                                                         │
│  flow-store.ts                                          │
│    └── agent-store subscription → adds/updates nodes    │
│         and edges, triggers re-layout on structural     │
│         changes only                                    │
└──────────────────────┬──────────────────────────────────┘
                       │ IPC (contextBridge)
┌──────────────────────┴──────────────────────────────────┐
│  Main Process (Node.js)                                 │
│                                                         │
│  ipc.ts                                                 │
│    ├── on('agent:message') → agent-service.sendMessage() │
│    └── agent-service events → send('agent:event', evt)  │
│                                                         │
│  agent-service.ts                                       │
│    ├── lead conversation via client.ts                  │
│    ├── spawns worker-session.ts per subagent            │
│    ├── tool calls → tool-filter.ts → tool-router.ts    │
│    └── emits AgentEvent (EventEmitter)                  │
│                                                         │
│  conversation-manager.ts                                │
│    ├── ensureInitialized() — 10-step LSP init           │
│    └── handleServerRequest() — tool call dispatch       │
│                                                         │
│  client.ts ←──stdio──→ copilot-language-server          │
│                                                         │
│  psi-bridge.ts ←──SSE──→ IntelliJ MCP Plugin           │
└─────────────────────────────────────────────────────────┘
```

---

## Key Module Responsibilities

### `src/main/lsp/client.ts`

Core LSP communication. Single instance, manages the subprocess.

```typescript
// Simplified API surface
class LspClient extends EventEmitter {
  start(binaryPath: string, env: Record<string, string>): void
  sendRequest(method: string, params?: any, timeoutMs?: number): Promise<any>
  sendNotification(method: string, params?: any): void
  sendResponse(id: number, result: any): void
  onServerRequest(handler: (method: string, id: number, params: any) => void): void
  registerProgressListener(token: string, cb: (value: any) => void): void
  removeProgressListener(token: string): void
  get featureFlags(): Record<string, any>
  shutdown(): void
}
```

No concurrency primitives needed. Node's event loop handles everything:
- `process.stdout.on('data')` feeds the transport reader
- Pending requests stored in `Map<number, {resolve, reject, timer}>`
- Progress listeners stored in `Map<string, Function>`

### `src/main/agent/agent-service.ts`

Direct port of AgentService.kt. Simpler in TypeScript:

```typescript
class AgentService extends EventEmitter {
  // Events: same AgentEvent types
  async sendMessage(text: string, model?: string): Promise<void>
  handleToolCall(id: number, name: string, input: any, conversationId?: string): Promise<void>
  isToolAllowedForConversation(conversationId: string | null, toolName: string): boolean
  ownsConversation(conversationId: string | null): boolean
  cancel(): void
  newConversation(): void
}
```

Key simplifications vs Kotlin:
- `async/await` replaces coroutines
- No `tryEmit` — `EventEmitter.emit()` is synchronous, never blocks
- No `synchronizedList` — single-threaded, plain arrays
- No `@Volatile` — plain variables
- Subagents: `Promise.all()` replaces `Deferred.await()`
- Wait loop: `await new Promise(resolve => progressListener.once('end', resolve))`
  instead of `delay(100)` polling

### `src/main/agent/worker-session.ts`

Direct port of WorkerSession.kt:

```typescript
class WorkerSession extends EventEmitter {
  // Events: 'delta', 'toolcall', 'done', 'error', 'conversationId'
  async executeTask(task: string, dependencyContext?: Record<string, string>): Promise<string>
  cancel(): void
}
```

No DONE_SENTINEL needed — resolve a Promise when `kind: "end"` arrives.

### `src/main/mcp/psi-bridge.ts`

SSE client connecting to the IntelliJ MCP plugin for PSI tools:

```typescript
class PsiBridge {
  connect(sseUrl: string): Promise<void>    // e.g., http://localhost:3000/sse
  listTools(): Promise<ToolSchema[]>
  callTool(name: string, input: any): Promise<string>
  disconnect(): void
  get isConnected(): boolean
}
```

Registered as a tool source in `tool-router.ts`. When IntelliJ isn't running,
these tools are simply unavailable — the router skips them.

### `src/renderer/stores/flow-store.ts`

Translates AgentEvents into React Flow nodes and edges:

```typescript
interface FlowState {
  nodes: Node[]
  edges: Edge[]
  currentRound: number

  // Actions triggered by agent-store subscription
  onSubagentSpawned(agentId: string, agentType: string, description: string): void
  onSubagentCompleted(agentId: string, status: string): void
  onLeadToolCall(name: string): void
  onLeadToolResult(name: string, status: string): void
  onNewRound(): void
  onReset(): void

  // Layout
  recomputeLayout(): void   // Dagre, only on structural changes
}
```

Layout recomputation is debounced — triggered by node addition/removal, not by
delta text updates. Text updates modify node data in-place without re-layout.

### `src/renderer/flow/AgentFlow.tsx`

The React Flow canvas:

```tsx
function AgentFlow() {
  const { nodes, edges } = useFlowStore()

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      edgeTypes={edgeTypes}
      fitView
      minZoom={0.1}
      maxZoom={2}
    >
      <Background variant="dots" gap={16} />
      <MiniMap />
      <FlowControls />
    </ReactFlow>
  )
}

const nodeTypes = {
  user: UserNode,
  lead: LeadNode,
  subagent: SubagentNode,
  tool: ToolNode,
  roundDivider: RoundDivider,
}
```

### `src/renderer/flow/nodes/SubagentNode.tsx`

Example custom node with Tailwind:

```tsx
function SubagentNode({ data }: NodeProps) {
  const { agentType, description, status, textPreview } = data

  return (
    <div className={cn(
      "rounded-lg border px-4 py-3 shadow-sm w-64",
      status === 'running' && "border-blue-400 bg-blue-50",
      status === 'success' && "border-green-400 bg-green-50",
      status === 'error'   && "border-red-400 bg-red-50",
    )}>
      <div className="flex items-center gap-2 mb-1">
        <StatusIcon status={status} />
        <span className="text-xs font-medium text-gray-500">{agentType}</span>
      </div>
      <p className="text-sm font-medium truncate">{description}</p>
      {textPreview && (
        <p className="text-xs text-gray-500 mt-1 line-clamp-2">{textPreview}</p>
      )}
      <Handle type="target" position={Position.Top} />
      <Handle type="source" position={Position.Bottom} />
    </div>
  )
}
```

---

## IPC Channel Contract

Communication between main and renderer via contextBridge:

```typescript
// src/shared/ipc-channels.ts

// Renderer → Main
'agent:message'           // { text: string, model?: string }
'agent:cancel'            // void
'agent:new-conversation'  // void
'chat:message'            // { text: string }
'settings:update'         // { key: string, value: any }

// Main → Renderer
'agent:event'             // AgentEvent (all event types)
'chat:event'              // ChatEvent
'lsp:status'              // { connected: boolean, user?: string }
'psi:status'              // { connected: boolean, tools: string[] }
```

Preload script exposes a typed API:

```typescript
// src/preload/index.ts
contextBridge.exposeInMainWorld('api', {
  agent: {
    sendMessage: (text: string, model?: string) =>
      ipcRenderer.send('agent:message', { text, model }),
    cancel: () => ipcRenderer.send('agent:cancel'),
    newConversation: () => ipcRenderer.send('agent:new-conversation'),
    onEvent: (cb: (event: AgentEvent) => void) =>
      ipcRenderer.on('agent:event', (_, event) => cb(event)),
  },
  chat: { ... },
  settings: { ... },
})
```

---

## Dependencies

```json
{
  "dependencies": {
    "@xyflow/react": "^12",
    "dagre": "^0.8",
    "react": "^19",
    "react-dom": "^19",
    "react-markdown": "^9",
    "zustand": "^5",
    "eventsource": "^3"
  },
  "devDependencies": {
    "electron": "^34",
    "electron-builder": "^25",
    "vite": "^6",
    "vite-plugin-electron": "^0.28",
    "tailwindcss": "^4",
    "typescript": "^5",
    "vitest": "^3"
  }
}
```

---

## Build Pipeline

```
vite build (renderer)  →  dist/renderer/    (HTML + JS + CSS)
tsc (main + preload)   →  dist/main/        (Node.js modules)
electron-builder       →  platform installer (dmg/exe/AppImage)
```

Vite handles the renderer with HMR during development.
TypeScript compiles the main process separately.
electron-builder bundles everything plus the copilot-language-server binary.

---

## Migration Path from IntelliJ Plugin

| Kotlin module | TypeScript module | Effort |
|---|---|---|
| `lsp/LspTransport.kt` | `lsp/transport.ts` | Low — ~50 lines |
| `lsp/LspClient.kt` | `lsp/client.ts` | Low — simpler without concurrency |
| `auth/CopilotAuth.kt` | `lsp/auth.ts` | Low — file read + JSON parse |
| `conversation/ConversationManager.kt` | `conversation/conversation-manager.ts` | Medium — 10-step init sequence |
| `agent/AgentService.kt` | `agent/agent-service.ts` | Medium — core logic, but simpler in TS |
| `orchestrator/WorkerSession.kt` | `agent/worker-session.ts` | Low — async/await simplifies everything |
| `agent/AgentRegistry.kt` | `agent/agent-registry.ts` | Low — file parsing |
| `tools/ToolRouter.kt` | `tools/tool-router.ts` | Low — routing logic |
| `tools/BuiltInTools.kt` | `tools/built-in-tools.ts` | Medium — 26 tool implementations |
| `mcp/ClientMcpManager.kt` | `mcp/mcp-manager.ts` | Medium — MCP handshake + compound tools |
| `ui/AgentPanel.kt` (Swing) | `flow/*` + `views/AgentView.tsx` | High — complete rewrite, but into a better framework |

Total: ~15 TypeScript files for the core, ~10 for the UI.
