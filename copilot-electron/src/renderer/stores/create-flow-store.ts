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
  seq: number
  /** ID of the last node added — new nodes connect from here */
  lastNodeId: string
  /** Map of branch node IDs to the node they branched from (for fan-out) */
  branchParents: Map<string, string>

  addEvent: (type: string, id: string, data: Record<string, unknown>, parentId?: string) => void
  updateNode: (id: string, data: Record<string, unknown>) => void
  updateEdge: (targetId: string, data: Record<string, unknown>) => void

  onReset: () => void
  loadFromMessages: (messages: ChatMessage[]) => void
  recomputeLayout: () => void
}

export type FlowStoreApi = ReturnType<typeof createFlowStore>

/* ------------------------------------------------------------------ */
/*  Factory                                                            */
/* ------------------------------------------------------------------ */

export function createFlowStore() {
  return create<FlowState>((set, get) => ({
    nodes: [],
    edges: [],
    seq: 0,
    lastNodeId: '',
    branchParents: new Map(),

    addEvent: (type, id, data, parentId) => {
      const state = get()
      const source = parentId ?? state.lastNodeId
      const newNode: Node = { id, type, position: { x: 0, y: 0 }, data }
      const newEdges: Edge[] = []
      if (source) {
        newEdges.push({
          id: `e-${source}-${id}`,
          source,
          target: id,
          type: 'particle',
          data: { status: 'active', color: colorForType(type) },
        })
      }
      set({
        nodes: [...state.nodes, newNode],
        edges: [...state.edges, ...newEdges],
        seq: state.seq + 1,
        lastNodeId: id,
      })
      get().recomputeLayout()
    },

    updateNode: (id, data) => {
      set((state) => ({
        nodes: state.nodes.map((n) =>
          n.id === id ? { ...n, data: { ...n.data, ...data } } : n,
        ),
      }))
    },

    updateEdge: (targetId, data) => {
      set((state) => ({
        edges: state.edges.map((e) =>
          e.target === targetId ? { ...e, data: { ...e.data, ...data } } : e,
        ),
      }))
    },

    onReset: () => {
      set({ nodes: [], edges: [], seq: 0, lastNodeId: '', branchParents: new Map() })
    },

    loadFromMessages: (messages) => {
      const s = get()
      s.onReset()

      // Replay events from saved messages
      let lastAgentNodeId = ''
      let lastUserNodeId = ''

      for (const msg of messages) {
        const state = get()
        switch (msg.type) {
          case 'user': {
            const id = `user-${state.seq}`
            get().addEvent('user', id, { message: msg.text })
            lastUserNodeId = id
            lastAgentNodeId = ''
            break
          }
          case 'agent': {
            if (!lastAgentNodeId) {
              const id = `agent-${state.seq}`
              get().addEvent('agent', id, { status: 'done', text: msg.text })
              lastAgentNodeId = id
            } else {
              get().updateNode(lastAgentNodeId, {
                text: ((get().nodes.find(n => n.id === lastAgentNodeId)?.data.text as string) || '') + msg.text,
              })
            }
            break
          }
          case 'tool': {
            if (msg.meta?.toolName) {
              const toolId = `tool-${state.seq}`
              const parent = lastAgentNodeId || state.lastNodeId
              get().addEvent('tool', toolId, {
                name: msg.meta.toolName, status: msg.status ?? 'success', input: msg.meta.input ?? {},
              }, parent)
              if (msg.status && msg.status !== 'running') {
                const resultId = `result-${state.seq}`
                get().addEvent('toolResult', resultId, {
                  name: msg.meta.toolName, status: msg.status, output: msg.meta.output ?? '',
                }, toolId)
                get().updateEdge(toolId, { status: msg.status === 'success' ? 'success' : 'error' })
                get().updateEdge(resultId, { status: msg.status === 'success' ? 'success' : 'error' })
              }
              lastAgentNodeId = '' // next agent delta creates new node
            }
            break
          }
          case 'terminal': {
            if (msg.meta?.command) {
              const termId = `term-${state.seq}`
              const parent = lastAgentNodeId || state.lastNodeId
              get().addEvent('terminal', termId, {
                command: msg.meta.command, status: msg.status ?? 'success',
              }, parent)
              if (msg.status && msg.status !== 'running') {
                const resultId = `termresult-${state.seq}`
                get().addEvent('terminalResult', resultId, {
                  command: msg.meta.command, status: msg.status, output: msg.meta.output ?? '',
                }, termId)
                get().updateEdge(termId, { status: msg.status === 'success' ? 'success' : 'error' })
                get().updateEdge(resultId, { status: msg.status === 'success' ? 'success' : 'error' })
              }
              lastAgentNodeId = ''
            }
            break
          }
          case 'subagent': {
            if (msg.nodeId) {
              const parent = lastAgentNodeId || state.lastNodeId
              get().addEvent('subagent', msg.nodeId, {
                agentId: msg.nodeId.replace('subagent-', ''),
                agentType: msg.meta?.agentType ?? 'agent',
                description: msg.text,
                status: msg.status ?? 'success',
                textPreview: '',
              }, parent)
              if (msg.status && msg.status !== 'running') {
                const resultId = `subresult-${msg.nodeId}`
                get().addEvent('subagentResult', resultId, {
                  agentId: msg.nodeId.replace('subagent-', ''),
                  agentType: msg.meta?.agentType ?? 'agent',
                  status: msg.status,
                  text: msg.meta?.output ?? '',
                }, msg.nodeId)
                get().updateEdge(msg.nodeId, { status: msg.status === 'success' ? 'success' : 'error' })
                get().updateEdge(resultId, { status: msg.status === 'success' ? 'success' : 'error' })
              }
              lastAgentNodeId = ''
            }
            break
          }
        }
      }
    },

    recomputeLayout: () => {
      const state = get()
      set({ nodes: computeLayout(state.nodes, state.edges) })
    },
  }))
}

function colorForType(type: string): string {
  switch (type) {
    case 'user': return '#6b7280'
    case 'agent': return '#10b981'
    case 'tool': return '#8b5cf6'
    case 'toolResult': return '#22c55e'
    case 'terminal': return '#a855f7'
    case 'terminalResult': return '#22c55e'
    case 'subagent': return '#3b82f6'
    case 'subagentResult': return '#22c55e'
    default: return '#6b7280'
  }
}
