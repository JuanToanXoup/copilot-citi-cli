import { create } from 'zustand'
import type { Node, Edge } from '@xyflow/react'
import type { ChatMessage } from './agent-store'
import { computeLayout } from '../flow/layout'

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

export interface FlowState {
  nodes: Node[]
  edges: Edge[]
  currentTurn: number

  /** The current agent message node receiving streaming deltas */
  currentAgentMsgId: string
  /** Monotonic counter for unique agent message node IDs */
  agentMsgSeq: number

  /** Tracks active branch nodes (tools/subagents) that haven't returned yet */
  activeBranches: Set<string>
  /** The last spine node — new branches and agent messages connect from here */
  spineNodeId: string
  /** Previous turn's last spine node — for inter-turn spine edges */
  previousSpineId: string | null

  onNewTurn: (userMessage: string) => void
  onAgentDelta: (text: string) => void
  onAgentStatus: (status: 'running' | 'done' | 'error') => void
  onLeadToolCall: (name: string, input?: Record<string, unknown>) => string
  onLeadToolResult: (toolNodeId: string, status: 'success' | 'error', output?: string) => void
  onTerminalCommand: (command: string) => string
  onTerminalResult: (termNodeId: string, status: 'success' | 'error', output?: string) => void
  onSubagentSpawned: (agentId: string, agentType: string, description: string) => void
  onSubagentCompleted: (agentId: string, status: 'success' | 'error', text?: string) => void
  onSubagentDelta: (agentId: string, text: string) => void
  onReset: () => void
  loadFromMessages: (messages: ChatMessage[]) => void
  recomputeLayout: () => void
}

export type FlowStoreApi = ReturnType<typeof createFlowStore>

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

function edge(source: string, target: string, color: string, status = 'active'): Edge {
  return {
    id: `e-${source}-${target}`,
    source,
    target,
    type: 'particle',
    data: { status, color },
  }
}

/* ------------------------------------------------------------------ */
/*  Factory                                                            */
/* ------------------------------------------------------------------ */

export function createFlowStore() {
  return create<FlowState>((set, get) => ({
    nodes: [],
    edges: [],
    currentTurn: 0,
    currentAgentMsgId: '',
    agentMsgSeq: 0,
    activeBranches: new Set<string>(),
    spineNodeId: '',
    previousSpineId: null,

    onNewTurn: (userMessage: string) => {
      const state = get()
      const turn = state.currentTurn + 1
      const userId = `user-${turn}`

      const newNodes: Node[] = [
        { id: userId, type: 'user', position: { x: 0, y: 0 }, data: { message: userMessage } },
      ]
      const newEdges: Edge[] = []

      // Spine: connect previous turn → this user
      if (state.previousSpineId) {
        newEdges.push(edge(state.previousSpineId, userId, '#6b7280', 'dashed'))
      }

      set({
        nodes: [...state.nodes, ...newNodes],
        edges: [...state.edges, ...newEdges],
        currentTurn: turn,
        currentAgentMsgId: '',
        activeBranches: new Set(),
        spineNodeId: userId,
      })

      get().recomputeLayout()
    },

    onAgentDelta: (text: string) => {
      const state = get()

      if (state.currentAgentMsgId) {
        // Append to existing agent message node
        set({
          nodes: state.nodes.map((n) =>
            n.id === state.currentAgentMsgId
              ? { ...n, data: { ...n.data, text: ((n.data.text as string) || '') + text } }
              : n,
          ),
        })
      } else {
        // Create a new agent message node on the spine
        const seq = state.agentMsgSeq + 1
        const msgId = `agent-msg-${state.currentTurn}-${seq}`
        const newNode: Node = {
          id: msgId, type: 'agent', position: { x: 0, y: 0 },
          data: { status: 'running', text },
        }
        const newEdge = edge(state.spineNodeId, msgId, '#10b981')

        set({
          nodes: [...state.nodes, newNode],
          edges: [...state.edges, newEdge],
          currentAgentMsgId: msgId,
          agentMsgSeq: seq,
          spineNodeId: msgId,
        })
        get().recomputeLayout()
      }
    },

    onAgentStatus: (status) => {
      const state = get()
      // Update all agent message nodes in the current turn to this status
      set({
        nodes: state.nodes.map((n) => {
          if (n.type === 'agent' && n.id.startsWith(`agent-msg-${state.currentTurn}-`)) {
            const nodeStatus = status === 'done' ? 'done' : status === 'error' ? 'error' : n.data.status
            return { ...n, data: { ...n.data, status: nodeStatus } }
          }
          return n
        }),
        edges: state.edges.map((e) => {
          const targetNode = state.nodes.find((n) => n.id === e.target)
          if (targetNode?.type === 'agent' && targetNode.id.startsWith(`agent-msg-${state.currentTurn}-`)) {
            return { ...e, data: { ...e.data, status: status === 'done' ? 'success' : status === 'error' ? 'error' : 'active' } }
          }
          return e
        }),
        // Save spine for next turn
        previousSpineId: state.spineNodeId,
      })
    },

    onLeadToolCall: (name, input) => {
      const state = get()
      const toolId = `tool-${name}-${Date.now()}`

      // Break the current agent message — next delta starts a new node
      const branches = new Set(state.activeBranches)
      branches.add(toolId)

      set({
        nodes: [...state.nodes, {
          id: toolId, type: 'tool', position: { x: 0, y: 0 },
          data: { name, status: 'running', input: input ?? {} },
        }],
        edges: [...state.edges, edge(state.spineNodeId, toolId, '#8b5cf6')],
        currentAgentMsgId: '', // break agent text — next delta creates new node
        activeBranches: branches,
      })
      get().recomputeLayout()
      return toolId
    },

    onLeadToolResult: (toolNodeId, status, output) => {
      const resultId = `result-${toolNodeId}`
      set((state) => {
        const nodes = state.nodes.map((n) =>
          n.id === toolNodeId ? { ...n, data: { ...n.data, status } } : n,
        )
        const edges = state.edges.map((e) =>
          e.target === toolNodeId
            ? { ...e, data: { ...e.data, status: status === 'success' ? 'success' : 'error' } }
            : e,
        )
        const toolNode = state.nodes.find((n) => n.id === toolNodeId)
        const toolName = (toolNode?.data?.name as string) ?? ''
        nodes.push({
          id: resultId, type: 'toolResult', position: { x: 0, y: 0 },
          data: { name: toolName, status, output: output ?? '' },
        })
        edges.push(edge(toolNodeId, resultId, status === 'success' ? '#22c55e' : '#ef4444', status === 'success' ? 'success' : 'error'))

        const branches = new Set(state.activeBranches)
        branches.delete(toolNodeId)
        return { nodes, edges, activeBranches: branches }
      })
      get().recomputeLayout()
    },

    onTerminalCommand: (command) => {
      const state = get()
      const termId = `terminal-${Date.now()}`

      const branches = new Set(state.activeBranches)
      branches.add(termId)

      set({
        nodes: [...state.nodes, {
          id: termId, type: 'terminal', position: { x: 0, y: 0 },
          data: { command, status: 'running' },
        }],
        edges: [...state.edges, edge(state.spineNodeId, termId, '#a855f7')],
        currentAgentMsgId: '',
        activeBranches: branches,
      })
      get().recomputeLayout()
      return termId
    },

    onTerminalResult: (termNodeId, status, output) => {
      const resultId = `termresult-${termNodeId}`
      set((state) => {
        const nodes = state.nodes.map((n) =>
          n.id === termNodeId ? { ...n, data: { ...n.data, status } } : n,
        )
        const edges = state.edges.map((e) =>
          e.target === termNodeId
            ? { ...e, data: { ...e.data, status: status === 'success' ? 'success' : 'error' } }
            : e,
        )
        const termNode = state.nodes.find((n) => n.id === termNodeId)
        const cmd = (termNode?.data?.command as string) ?? ''
        nodes.push({
          id: resultId, type: 'terminalResult', position: { x: 0, y: 0 },
          data: { command: cmd, status, output: output ?? '' },
        })
        edges.push(edge(termNodeId, resultId, status === 'success' ? '#22c55e' : '#ef4444', status === 'success' ? 'success' : 'error'))

        const branches = new Set(state.activeBranches)
        branches.delete(termNodeId)
        return { nodes, edges, activeBranches: branches }
      })
      get().recomputeLayout()
    },

    onSubagentSpawned: (agentId, agentType, description) => {
      const state = get()
      const nodeId = `subagent-${agentId}`
      if (state.nodes.some((n) => n.id === nodeId)) return

      const branches = new Set(state.activeBranches)
      branches.add(nodeId)

      set({
        nodes: [...state.nodes, {
          id: nodeId, type: 'subagent', position: { x: 0, y: 0 },
          data: { agentId, agentType, description, status: 'running', textPreview: '' },
        }],
        edges: [...state.edges, edge(state.spineNodeId, nodeId, '#3b82f6')],
        currentAgentMsgId: '', // break agent text
        activeBranches: branches,
      })
      get().recomputeLayout()
    },

    onSubagentCompleted: (agentId, status, text) => {
      const targetId = `subagent-${agentId}`
      const resultId = `subresult-${agentId}`
      set((state) => {
        const nodes = state.nodes.map((n) =>
          n.id === targetId ? { ...n, data: { ...n.data, status } } : n,
        )
        const edges = state.edges.map((e) =>
          e.target === targetId
            ? { ...e, data: { ...e.data, status: status === 'success' ? 'success' : 'error' } }
            : e,
        )
        const subNode = state.nodes.find((n) => n.id === targetId)
        const agentType = (subNode?.data?.agentType as string) ?? 'agent'
        nodes.push({
          id: resultId, type: 'subagentResult', position: { x: 0, y: 0 },
          data: { agentId, agentType, status, text: text ?? '' },
        })
        edges.push(edge(targetId, resultId, status === 'success' ? '#22c55e' : '#ef4444', status === 'success' ? 'success' : 'error'))

        const branches = new Set(state.activeBranches)
        branches.delete(targetId)
        return { nodes, edges, activeBranches: branches }
      })
      get().recomputeLayout()
    },

    onSubagentDelta: (agentId, text) => {
      set((state) => ({
        nodes: state.nodes.map((n) =>
          n.id === `subagent-${agentId}` ? { ...n, data: { ...n.data, textPreview: ((n.data.textPreview as string) || '') + text } } : n,
        ),
      }))
    },

    onReset: () => {
      set({
        nodes: [], edges: [], currentTurn: 0,
        currentAgentMsgId: '', agentMsgSeq: 0,
        activeBranches: new Set(), spineNodeId: '', previousSpineId: null,
      })
    },

    loadFromMessages: (messages) => {
      get().onReset()
      for (const msg of messages) {
        switch (msg.type) {
          case 'user':
            get().onNewTurn(msg.text)
            break
          case 'agent':
            get().onAgentDelta(msg.text)
            break
          case 'tool':
            if (msg.meta?.toolName) {
              const toolId = get().onLeadToolCall(msg.meta.toolName, msg.meta.input)
              if (msg.status && msg.status !== 'running') {
                get().onLeadToolResult(toolId, msg.status as 'success' | 'error', msg.meta?.output)
              }
            }
            break
          case 'terminal':
            if (msg.meta?.command) {
              const termId = get().onTerminalCommand(msg.meta.command)
              if (msg.status && msg.status !== 'running') {
                get().onTerminalResult(termId, msg.status as 'success' | 'error', msg.meta?.output)
              }
            }
            break
          case 'subagent':
            if (msg.nodeId) {
              const agentId = msg.nodeId.replace('subagent-', '')
              get().onSubagentSpawned(agentId, msg.meta?.agentType ?? 'agent', msg.text)
              if (msg.status && msg.status !== 'running') {
                get().onSubagentCompleted(agentId, msg.status as 'success' | 'error', msg.meta?.output)
              }
            }
            break
        }
      }
      if (get().currentTurn > 0) get().onAgentStatus('done')
    },

    recomputeLayout: () => {
      const state = get()
      set({ nodes: computeLayout(state.nodes, state.edges) })
    },
  }))
}
