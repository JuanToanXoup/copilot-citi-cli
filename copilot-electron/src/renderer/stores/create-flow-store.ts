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
  currentAgentId: string
  previousAgentId: string | null
  /** ID of the last node added — used to chain edges sequentially */
  lastNodeId: string

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

function chainEdge(source: string, target: string, color: string, status = 'active'): Edge {
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
    currentAgentId: '',
    previousAgentId: null,
    lastNodeId: '',

    onNewTurn: (userMessage: string) => {
      const state = get()
      const turn = state.currentTurn + 1
      const userId = `user-${turn}`
      const agentId = `agent-${turn}`

      const newNodes: Node[] = [
        { id: userId, type: 'user', position: { x: 0, y: 0 }, data: { message: userMessage } },
        { id: agentId, type: 'agent', position: { x: 0, y: 0 }, data: { status: 'idle', text: '' } },
      ]

      const newEdges: Edge[] = []

      // Connect previous turn's last node → this user (spine)
      if (state.lastNodeId) {
        newEdges.push(chainEdge(state.lastNodeId, userId, '#6b7280', 'dashed'))
      }
      // user → agent
      newEdges.push(chainEdge(userId, agentId, '#6b7280'))

      set({
        nodes: [...state.nodes, ...newNodes],
        edges: [...state.edges, ...newEdges],
        currentTurn: turn,
        currentAgentId: agentId,
        previousAgentId: agentId,
        lastNodeId: agentId,
      })

      get().recomputeLayout()
    },

    onAgentDelta: (text: string) => {
      const state = get()
      set({
        nodes: state.nodes.map((n) =>
          n.id === state.currentAgentId
            ? { ...n, data: { ...n.data, text: ((n.data.text as string) || '') + text } }
            : n,
        ),
      })
    },

    onAgentStatus: (status) => {
      const state = get()
      set({
        nodes: state.nodes.map((n) =>
          n.id === state.currentAgentId ? { ...n, data: { ...n.data, status } } : n,
        ),
        edges: state.edges.map((e) =>
          e.target === state.currentAgentId
            ? { ...e, data: { ...e.data, status: status === 'done' ? 'success' : status === 'error' ? 'error' : 'active' } }
            : e,
        ),
      })
    },

    onLeadToolCall: (name, input) => {
      const state = get()
      const toolId = `tool-${name}-${Date.now()}`
      set({
        nodes: [...state.nodes, {
          id: toolId, type: 'tool', position: { x: 0, y: 0 },
          data: { name, status: 'running', input: input ?? {} },
        }],
        edges: [...state.edges, chainEdge(state.lastNodeId, toolId, '#8b5cf6')],
        lastNodeId: toolId,
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
        edges.push(chainEdge(toolNodeId, resultId, status === 'success' ? '#22c55e' : '#ef4444', status === 'success' ? 'success' : 'error'))
        return { nodes, edges, lastNodeId: resultId }
      })
      get().recomputeLayout()
    },

    onTerminalCommand: (command) => {
      const state = get()
      const termId = `terminal-${Date.now()}`
      set({
        nodes: [...state.nodes, {
          id: termId, type: 'terminal', position: { x: 0, y: 0 },
          data: { command, status: 'running' },
        }],
        edges: [...state.edges, chainEdge(state.lastNodeId, termId, '#a855f7')],
        lastNodeId: termId,
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
        edges.push(chainEdge(termNodeId, resultId, status === 'success' ? '#22c55e' : '#ef4444', status === 'success' ? 'success' : 'error'))
        return { nodes, edges, lastNodeId: resultId }
      })
      get().recomputeLayout()
    },

    onSubagentSpawned: (agentId, agentType, description) => {
      const state = get()
      const nodeId = `subagent-${agentId}`
      if (state.nodes.some((n) => n.id === nodeId)) return

      set({
        nodes: [...state.nodes, {
          id: nodeId, type: 'subagent', position: { x: 0, y: 0 },
          data: { agentId, agentType, description, status: 'running', textPreview: '' },
        }],
        edges: [...state.edges, chainEdge(state.lastNodeId, nodeId, '#3b82f6')],
        lastNodeId: nodeId,
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
        edges.push(chainEdge(targetId, resultId, status === 'success' ? '#22c55e' : '#ef4444', status === 'success' ? 'success' : 'error'))
        return { nodes, edges, lastNodeId: resultId }
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
      set({ nodes: [], edges: [], currentTurn: 0, currentAgentId: '', previousAgentId: null, lastNodeId: '' })
    },

    loadFromMessages: (messages) => {
      get().onReset()
      for (const msg of messages) {
        switch (msg.type) {
          case 'user':
            get().onNewTurn(msg.text)
            get().onAgentStatus('running')
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
      set({ nodes: computeLayout(state.nodes) })
    },
  }))
}
