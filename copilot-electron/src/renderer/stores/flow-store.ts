import { create } from 'zustand'
import type { Node, Edge } from '@xyflow/react'
import dagre from 'dagre'

/* ------------------------------------------------------------------ */
/*  Types                                                              */
/* ------------------------------------------------------------------ */

interface FlowState {
  nodes: Node[]
  edges: Edge[]

  /** Current turn number (1-based) */
  currentTurn: number
  /** ID of the lead node for the current turn */
  currentLeadId: string
  /** ID of the previous turn's lead node (for chaining) */
  previousLeadId: string | null

  // Turn lifecycle
  onNewTurn: (userMessage: string) => void

  // Agent events
  onSubagentSpawned: (agentId: string, agentType: string, description: string) => void
  onSubagentCompleted: (agentId: string, status: 'success' | 'error') => void
  onSubagentDelta: (agentId: string, text: string) => void
  onLeadToolCall: (name: string) => void
  onLeadToolResult: (name: string, status: 'success' | 'error') => void
  onLeadStatus: (status: 'running' | 'done' | 'error') => void

  // Lifecycle
  onReset: () => void

  // Layout
  recomputeLayout: () => void
}

/* ------------------------------------------------------------------ */
/*  Initial state for turn 1 (no user node — used only on reset)      */
/* ------------------------------------------------------------------ */

function makeInitialLeadNode(): Node {
  return {
    id: 'lead-1',
    type: 'lead',
    position: { x: 0, y: 0 },
    data: { label: 'Lead Agent', status: 'idle', round: 1 },
  }
}

/* ------------------------------------------------------------------ */
/*  Layout helper                                                      */
/* ------------------------------------------------------------------ */

function applyDagreLayout(nodes: Node[], edges: Edge[]): Node[] {
  if (nodes.length === 0) return nodes

  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'TB', nodesep: 60, ranksep: 80, marginx: 40, marginy: 40 })

  for (const node of nodes) {
    const width = node.type === 'user' ? 260 : node.type === 'lead' ? 200 : 240
    const height = node.type === 'user' ? 60 : node.type === 'lead' ? 56 : 80
    g.setNode(node.id, { width, height })
  }

  for (const edge of edges) {
    g.setEdge(edge.source, edge.target)
  }

  dagre.layout(g)

  return nodes.map((node) => {
    const pos = g.node(node.id)
    return {
      ...node,
      position: {
        x: pos.x - (pos.width ?? 0) / 2,
        y: pos.y - (pos.height ?? 0) / 2,
      },
    }
  })
}

/* ------------------------------------------------------------------ */
/*  Store                                                              */
/* ------------------------------------------------------------------ */

export const useFlowStore = create<FlowState>((set, get) => ({
  nodes: [makeInitialLeadNode()],
  edges: [],
  currentTurn: 1,
  currentLeadId: 'lead-1',
  previousLeadId: null,

  /* ---- Turn lifecycle ------------------------------------------- */

  onNewTurn: (userMessage: string) => {
    const state = get()
    const turn = state.currentTurn + 1
    const userId = `user-${turn}`
    const leadId = `lead-${turn}`

    const newNodes: Node[] = [
      // User message node
      {
        id: userId,
        type: 'user',
        position: { x: 0, y: 0 },
        data: { message: userMessage },
      },
      // Lead node for this turn
      {
        id: leadId,
        type: 'lead',
        position: { x: 0, y: 0 },
        data: { label: 'Lead Agent', status: 'idle', round: turn },
      },
    ]

    const newEdges: Edge[] = [
      // User → Lead (within this turn)
      {
        id: `e-${userId}-${leadId}`,
        source: userId,
        target: leadId,
        style: { stroke: '#6b7280' },
      },
    ]

    // Chain from previous turn's lead → this turn's user
    if (state.previousLeadId) {
      newEdges.push({
        id: `e-${state.previousLeadId}-${userId}`,
        source: state.previousLeadId,
        target: userId,
        style: { stroke: '#6b7280', strokeDasharray: '6 3' },
        animated: false,
      })
    }

    set({
      nodes: [...state.nodes, ...newNodes],
      edges: [...state.edges, ...newEdges],
      currentTurn: turn,
      currentLeadId: leadId,
      previousLeadId: leadId,
    })

    get().recomputeLayout()
  },

  /* ---- Agent events --------------------------------------------- */

  onSubagentSpawned: (agentId, agentType, description) => {
    const state = get()
    const nodeId = `subagent-${agentId}`

    // Skip if already exists
    if (state.nodes.some((n) => n.id === nodeId)) return

    const newNode: Node = {
      id: nodeId,
      type: 'subagent',
      position: { x: 0, y: 0 },
      data: { agentId, agentType, description, status: 'running', textPreview: '' },
    }

    const newEdge: Edge = {
      id: `e-${state.currentLeadId}-${nodeId}`,
      source: state.currentLeadId,
      target: nodeId,
      animated: true,
      style: { stroke: '#3b82f6' },
    }

    set({
      nodes: [...state.nodes, newNode],
      edges: [...state.edges, newEdge],
    })

    get().recomputeLayout()
  },

  onSubagentCompleted: (agentId, status) => {
    set((state) => ({
      nodes: state.nodes.map((n) =>
        n.id === `subagent-${agentId}` ? { ...n, data: { ...n.data, status } } : n,
      ),
      // Stop edge animation
      edges: state.edges.map((e) =>
        e.target === `subagent-${agentId}` ? { ...e, animated: false } : e,
      ),
    }))
  },

  onSubagentDelta: (agentId, text) => {
    set((state) => ({
      nodes: state.nodes.map((n) =>
        n.id === `subagent-${agentId}`
          ? {
              ...n,
              data: {
                ...n.data,
                textPreview: ((n.data.textPreview as string) || '') + text,
              },
            }
          : n,
      ),
    }))
  },

  onLeadToolCall: (name) => {
    const state = get()
    const toolId = `tool-${name}-${Date.now()}`

    const newNode: Node = {
      id: toolId,
      type: 'tool',
      position: { x: 0, y: 0 },
      data: { name, status: 'running' },
    }

    const newEdge: Edge = {
      id: `e-${state.currentLeadId}-${toolId}`,
      source: state.currentLeadId,
      target: toolId,
      animated: true,
      style: { stroke: '#8b5cf6' },
    }

    set({
      nodes: [...state.nodes, newNode],
      edges: [...state.edges, newEdge],
    })

    get().recomputeLayout()
  },

  onLeadToolResult: (name, status) => {
    set((state) => ({
      nodes: state.nodes.map((n) =>
        n.type === 'tool' && n.data.name === name && n.data.status === 'running'
          ? { ...n, data: { ...n.data, status } }
          : n,
      ),
      edges: state.edges.map((e) => {
        const targetNode = state.nodes.find((n) => n.id === e.target)
        if (targetNode?.type === 'tool' && targetNode.data.name === name) {
          return { ...e, animated: false }
        }
        return e
      }),
    }))
  },

  onLeadStatus: (status) => {
    const state = get()
    set({
      nodes: state.nodes.map((n) =>
        n.id === state.currentLeadId ? { ...n, data: { ...n.data, status } } : n,
      ),
    })
  },

  /* ---- Lifecycle ------------------------------------------------ */

  onReset: () => {
    set({
      nodes: applyDagreLayout([makeInitialLeadNode()], []),
      edges: [],
      currentTurn: 1,
      currentLeadId: 'lead-1',
      previousLeadId: null,
    })
  },

  /* ---- Layout --------------------------------------------------- */

  recomputeLayout: () => {
    const state = get()
    const laid = applyDagreLayout(state.nodes, state.edges)
    set({ nodes: laid })
  },
}))
