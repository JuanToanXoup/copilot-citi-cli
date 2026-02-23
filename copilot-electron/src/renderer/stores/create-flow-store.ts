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
/*  Factory                                                            */
/* ------------------------------------------------------------------ */

export function createFlowStore() {
  return create<FlowState>((set, get) => ({
    nodes: [],
    edges: [],
    currentTurn: 0,
    currentAgentId: '',
    previousAgentId: null,

    onNewTurn: (userMessage: string) => {
      const state = get()
      const turn = state.currentTurn + 1
      const userId = `user-${turn}`
      const agentId = `agent-${turn}`

      const newNodes: Node[] = [
        { id: userId, type: 'user', position: { x: 0, y: 0 }, data: { message: userMessage } },
        { id: agentId, type: 'agent', position: { x: 0, y: 0 }, data: { status: 'idle', text: '' } },
      ]

      const newEdges: Edge[] = [
        { id: `e-${userId}-${agentId}`, source: userId, target: agentId, type: 'particle', data: { status: 'active', color: '#6b7280' } },
      ]

      // Spine: connect previous agent → this user
      if (state.previousAgentId) {
        newEdges.push({
          id: `e-spine-${state.previousAgentId}-${userId}`,
          source: state.previousAgentId,
          target: userId,
          type: 'particle',
          data: { status: 'dashed', color: '#6b7280' },
        })
      }

      set({
        nodes: [...state.nodes, ...newNodes],
        edges: [...state.edges, ...newEdges],
        currentTurn: turn,
        currentAgentId: agentId,
        previousAgentId: agentId,
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
        edges: [...state.edges, {
          id: `e-${state.currentAgentId}-${toolId}`,
          source: state.currentAgentId, target: toolId,
          type: 'particle', data: { status: 'active', color: '#8b5cf6' },
        }],
      })
      get().recomputeLayout()
      return toolId
    },

    onLeadToolResult: (toolNodeId, status, output) => {
      const resultId = `result-${toolNodeId}`
      set((state) => {
        // Update tool node status
        const nodes = state.nodes.map((n) =>
          n.id === toolNodeId ? { ...n, data: { ...n.data, status } } : n,
        )
        // Update tool edge status
        const edges = state.edges.map((e) =>
          e.target === toolNodeId
            ? { ...e, data: { ...e.data, status: status === 'success' ? 'success' : 'error' } }
            : e,
        )
        // Create result node + edge
        const toolNode = state.nodes.find((n) => n.id === toolNodeId)
        const toolName = (toolNode?.data?.name as string) ?? ''
        nodes.push({
          id: resultId, type: 'toolResult', position: { x: 0, y: 0 },
          data: { name: toolName, status, output: output ?? '' },
        })
        edges.push({
          id: `e-${toolNodeId}-${resultId}`,
          source: toolNodeId, target: resultId,
          type: 'particle', data: { status: status === 'success' ? 'success' : 'error', color: status === 'success' ? '#22c55e' : '#ef4444' },
        })
        return { nodes, edges }
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
        edges: [...state.edges, {
          id: `e-${state.currentAgentId}-${termId}`,
          source: state.currentAgentId, target: termId,
          type: 'particle', data: { status: 'active', color: '#a855f7' },
        }],
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
        edges.push({
          id: `e-${termNodeId}-${resultId}`,
          source: termNodeId, target: resultId,
          type: 'particle', data: { status: status === 'success' ? 'success' : 'error', color: status === 'success' ? '#22c55e' : '#ef4444' },
        })
        return { nodes, edges }
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
        edges: [...state.edges, {
          id: `e-${state.currentAgentId}-${nodeId}`,
          source: state.currentAgentId, target: nodeId,
          type: 'particle', data: { status: 'active', color: '#3b82f6' },
        }],
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
        edges.push({
          id: `e-${targetId}-${resultId}`,
          source: targetId, target: resultId,
          type: 'particle', data: { status: status === 'success' ? 'success' : 'error', color: status === 'success' ? '#22c55e' : '#ef4444' },
        })
        return { nodes, edges }
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
      set({ nodes: [], edges: [], currentTurn: 0, currentAgentId: '', previousAgentId: null })
    },

    loadFromMessages: (messages) => {
      get().onReset()
      let lastAgentText = ''
      for (const msg of messages) {
        switch (msg.type) {
          case 'user':
            get().onNewTurn(msg.text)
            get().onAgentStatus('running')
            lastAgentText = ''
            break
          case 'agent':
            lastAgentText += msg.text
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
      // Mark last turn as done
      if (get().currentTurn > 0) get().onAgentStatus('done')
    },

    recomputeLayout: () => {
      const state = get()
      set({ nodes: computeLayout(state.nodes, state.edges) })
    },
  }))
}
