import { create } from 'zustand'
import type { Node, Edge } from '@xyflow/react'
import dagre from 'dagre'
import type { ChatMessage } from './agent-store'

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

export interface FlowState {
  nodes: Node[]
  edges: Edge[]
  currentTurn: number
  currentLeadId: string
  currentGroupId: string
  previousGroupId: string | null

  onNewTurn: (userMessage: string) => void
  onSubagentSpawned: (agentId: string, agentType: string, description: string) => void
  onSubagentCompleted: (agentId: string, status: 'success' | 'error') => void
  onSubagentDelta: (agentId: string, text: string) => void
  onLeadToolCall: (name: string) => string
  onLeadToolResult: (name: string, status: 'success' | 'error') => string | undefined
  onTerminalCommand: (command: string) => string
  onTerminalResult: (command: string, status: 'success' | 'error') => string | undefined
  onLeadStatus: (status: 'running' | 'done' | 'error') => void
  onReset: () => void
  loadFromMessages: (messages: ChatMessage[]) => void
  recomputeLayout: () => void
}

export type FlowStoreApi = ReturnType<typeof createFlowStore>

/* ------------------------------------------------------------------ */
/*  Layout constants                                                   */
/* ------------------------------------------------------------------ */

const NODE_WIDTHS: Record<string, number> = { user: 260, lead: 200, subagent: 240, tool: 160, terminal: 210 }
const NODE_HEIGHTS: Record<string, number> = { user: 60, lead: 56, subagent: 80, tool: 40, terminal: 56 }
const GROUP_PADDING = 40

function applyLayout(nodes: Node[], edges: Edge[]): Node[] {
  const childNodes = nodes.filter((n) => n.type !== 'turnGroup')
  const groupNodes = nodes.filter((n) => n.type === 'turnGroup')
  if (childNodes.length === 0) return nodes

  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'TB', nodesep: 60, ranksep: 80, marginx: 40, marginy: 40 })

  for (const node of childNodes) {
    const width = NODE_WIDTHS[node.type ?? 'subagent'] ?? 200
    const height = NODE_HEIGHTS[node.type ?? 'subagent'] ?? 60
    g.setNode(node.id, { width, height })
  }

  for (const edge of edges) {
    const sourceIsChild = childNodes.some((n) => n.id === edge.source)
    const targetIsChild = childNodes.some((n) => n.id === edge.target)
    if (sourceIsChild && targetIsChild) g.setEdge(edge.source, edge.target)
  }

  dagre.layout(g)

  const positioned = childNodes.map((node) => {
    const pos = g.node(node.id)
    return { ...node, position: { x: pos.x - (pos.width ?? 0) / 2, y: pos.y - (pos.height ?? 0) / 2 } }
  })

  const updatedGroups = groupNodes.map((group) => {
    const turnId = group.data.turn as number
    const children = positioned.filter((n) => n.data._turn === turnId)
    if (children.length === 0) return group

    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
    for (const child of children) {
      const w = NODE_WIDTHS[child.type ?? 'subagent'] ?? 200
      const h = NODE_HEIGHTS[child.type ?? 'subagent'] ?? 60
      minX = Math.min(minX, child.position.x)
      minY = Math.min(minY, child.position.y)
      maxX = Math.max(maxX, child.position.x + w)
      maxY = Math.max(maxY, child.position.y + h)
    }

    return {
      ...group,
      position: { x: minX - GROUP_PADDING, y: minY - GROUP_PADDING },
      style: { width: maxX - minX + GROUP_PADDING * 2, height: maxY - minY + GROUP_PADDING * 2 },
    }
  })

  return [...positioned, ...updatedGroups]
}

/* ------------------------------------------------------------------ */
/*  Factory                                                            */
/* ------------------------------------------------------------------ */

export function createFlowStore() {
  return create<FlowState>((set, get) => ({
    nodes: [],
    edges: [],
    currentTurn: 0,
    currentLeadId: '',
    currentGroupId: '',
    previousGroupId: null,

    onNewTurn: (userMessage: string) => {
      const state = get()
      const turn = state.currentTurn + 1
      const groupId = `group-${turn}`
      const userId = `user-${turn}`
      const leadId = `lead-${turn}`

      const newNodes: Node[] = [
        { id: groupId, type: 'turnGroup', position: { x: 0, y: 0 }, data: { turn }, style: { width: 400, height: 300 } },
        { id: userId, type: 'user', position: { x: 0, y: 0 }, data: { message: userMessage, _turn: turn } },
        { id: leadId, type: 'lead', position: { x: 0, y: 0 }, data: { label: 'Lead Agent', status: 'idle', round: turn, _turn: turn } },
      ]

      const newEdges: Edge[] = [
        { id: `e-${userId}-${leadId}`, source: userId, target: leadId, type: 'particle', data: { status: 'active', color: '#6b7280' } },
      ]

      if (state.previousGroupId) {
        newEdges.push({
          id: `e-spine-${state.previousGroupId}-${groupId}`,
          source: state.previousGroupId,
          target: groupId,
          type: 'particle',
          data: { status: 'dashed', color: '#6b7280' },
        })
      }

      set({
        nodes: [...state.nodes, ...newNodes],
        edges: [...state.edges, ...newEdges],
        currentTurn: turn,
        currentLeadId: leadId,
        currentGroupId: groupId,
        previousGroupId: groupId,
      })

      get().recomputeLayout()
    },

    onSubagentSpawned: (agentId, agentType, description) => {
      const state = get()
      const nodeId = `subagent-${agentId}`
      if (state.nodes.some((n) => n.id === nodeId)) return

      set({
        nodes: [...state.nodes, { id: nodeId, type: 'subagent', position: { x: 0, y: 0 }, data: { agentId, agentType, description, status: 'running', textPreview: '', _turn: state.currentTurn } }],
        edges: [...state.edges, { id: `e-${state.currentLeadId}-${nodeId}`, source: state.currentLeadId, target: nodeId, type: 'particle', data: { status: 'active', color: '#3b82f6' } }],
      })
      get().recomputeLayout()
    },

    onSubagentCompleted: (agentId, status) => {
      const targetId = `subagent-${agentId}`
      set((state) => ({
        nodes: state.nodes.map((n) => n.id === targetId ? { ...n, data: { ...n.data, status } } : n),
        edges: state.edges.map((e) => e.target === targetId ? { ...e, data: { ...e.data, status: status === 'success' ? 'success' : 'error' } } : e),
      }))
    },

    onSubagentDelta: (agentId, text) => {
      set((state) => ({
        nodes: state.nodes.map((n) =>
          n.id === `subagent-${agentId}` ? { ...n, data: { ...n.data, textPreview: ((n.data.textPreview as string) || '') + text } } : n,
        ),
      }))
    },

    onLeadToolCall: (name) => {
      const state = get()
      const toolId = `tool-${name}-${Date.now()}`
      set({
        nodes: [...state.nodes, { id: toolId, type: 'tool', position: { x: 0, y: 0 }, data: { name, status: 'running', _turn: state.currentTurn } }],
        edges: [...state.edges, { id: `e-${state.currentLeadId}-${toolId}`, source: state.currentLeadId, target: toolId, type: 'particle', data: { status: 'active', color: '#8b5cf6' } }],
      })
      get().recomputeLayout()
      return toolId
    },

    onLeadToolResult: (name, status) => {
      let matchedNodeId: string | undefined
      set((state) => {
        const nodes = state.nodes.map((n) => {
          if (n.type === 'tool' && n.data.name === name && n.data.status === 'running') {
            matchedNodeId = n.id
            return { ...n, data: { ...n.data, status } }
          }
          return n
        })
        const edges = state.edges.map((e) => {
          const targetNode = state.nodes.find((n) => n.id === e.target)
          if (targetNode?.type === 'tool' && targetNode.data.name === name && targetNode.data.status === 'running') {
            return { ...e, data: { ...e.data, status: status === 'success' ? 'success' : 'error' } }
          }
          return e
        })
        return { nodes, edges }
      })
      return matchedNodeId
    },

    onTerminalCommand: (command) => {
      const state = get()
      const termId = `terminal-${Date.now()}`
      set({
        nodes: [...state.nodes, { id: termId, type: 'terminal', position: { x: 0, y: 0 }, data: { command, status: 'running', _turn: state.currentTurn } }],
        edges: [...state.edges, { id: `e-${state.currentLeadId}-${termId}`, source: state.currentLeadId, target: termId, type: 'particle', data: { status: 'active', color: '#a855f7' } }],
      })
      get().recomputeLayout()
      return termId
    },

    onTerminalResult: (command, status) => {
      let matchedNodeId: string | undefined
      set((state) => {
        const nodes = state.nodes.map((n) => {
          if (n.type === 'terminal' && n.data.command === command && n.data.status === 'running') {
            matchedNodeId = n.id
            return { ...n, data: { ...n.data, status } }
          }
          return n
        })
        const edges = state.edges.map((e) => {
          const targetNode = state.nodes.find((n) => n.id === e.target)
          if (targetNode?.type === 'terminal' && targetNode.data.command === command && targetNode.data.status === 'running') {
            return { ...e, data: { ...e.data, status: status === 'success' ? 'success' : 'error' } }
          }
          return e
        })
        return { nodes, edges }
      })
      return matchedNodeId
    },

    onLeadStatus: (status) => {
      const state = get()
      set({
        nodes: state.nodes.map((n) =>
          n.id === state.currentLeadId ? { ...n, data: { ...n.data, status } } : n,
        ),
        edges: state.edges.map((e) =>
          e.target === state.currentLeadId
            ? { ...e, data: { ...e.data, status: status === 'done' ? 'success' : status === 'error' ? 'error' : 'active' } }
            : e,
        ),
      })
    },

    onReset: () => {
      set({ nodes: [], edges: [], currentTurn: 0, currentLeadId: '', currentGroupId: '', previousGroupId: null })
    },

    loadFromMessages: (messages) => {
      get().onReset()
      let lastUserText = ''
      for (const msg of messages) {
        switch (msg.type) {
          case 'user':
            lastUserText = msg.text
            get().onNewTurn(msg.text)
            break
          case 'tool':
            if (msg.meta?.toolName) {
              get().onLeadToolCall(msg.meta.toolName)
              if (msg.status && msg.status !== 'running') get().onLeadToolResult(msg.meta.toolName, msg.status as 'success' | 'error')
            }
            break
          case 'terminal':
            if (msg.meta?.command) {
              get().onTerminalCommand(msg.meta.command)
              if (msg.status && msg.status !== 'running') get().onTerminalResult(msg.meta.command, msg.status as 'success' | 'error')
            }
            break
          case 'subagent':
            if (msg.nodeId) {
              const agentId = msg.nodeId.replace('subagent-', '')
              get().onSubagentSpawned(agentId, msg.meta?.agentType ?? 'agent', msg.text)
              if (msg.status && msg.status !== 'running') get().onSubagentCompleted(agentId, msg.status as 'success' | 'error')
            }
            break
        }
      }
      if (lastUserText) get().onLeadStatus('done')
    },

    recomputeLayout: () => {
      const state = get()
      set({ nodes: applyLayout(state.nodes, state.edges) })
    },
  }))
}
