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
  /** ID of the current turn's group node */
  currentGroupId: string
  /** ID of the previous turn's group node (for spine chaining) */
  previousGroupId: string | null

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
/*  Constants                                                          */
/* ------------------------------------------------------------------ */

const NODE_WIDTHS: Record<string, number> = {
  user: 260,
  lead: 200,
  subagent: 240,
  tool: 160,
}

const NODE_HEIGHTS: Record<string, number> = {
  user: 60,
  lead: 56,
  subagent: 80,
  tool: 40,
}

const GROUP_PADDING = 40

/* ------------------------------------------------------------------ */
/*  Layout: Dagre for internal nodes, then wrap with group bounds      */
/* ------------------------------------------------------------------ */

function applyLayout(nodes: Node[], edges: Edge[]): Node[] {
  const childNodes = nodes.filter((n) => n.type !== 'turnGroup')
  const groupNodes = nodes.filter((n) => n.type === 'turnGroup')

  if (childNodes.length === 0) return nodes

  // Layout only child nodes with Dagre
  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'TB', nodesep: 60, ranksep: 80, marginx: 40, marginy: 40 })

  for (const node of childNodes) {
    const width = NODE_WIDTHS[node.type ?? 'subagent'] ?? 200
    const height = NODE_HEIGHTS[node.type ?? 'subagent'] ?? 60
    g.setNode(node.id, { width, height })
  }

  // Only add edges between child nodes (not spine edges between groups)
  for (const edge of edges) {
    const sourceIsChild = childNodes.some((n) => n.id === edge.source)
    const targetIsChild = childNodes.some((n) => n.id === edge.target)
    if (sourceIsChild && targetIsChild) {
      g.setEdge(edge.source, edge.target)
    }
  }

  dagre.layout(g)

  // Position child nodes from dagre results
  const positioned = childNodes.map((node) => {
    const pos = g.node(node.id)
    return {
      ...node,
      position: {
        x: pos.x - (pos.width ?? 0) / 2,
        y: pos.y - (pos.height ?? 0) / 2,
      },
    }
  })

  // Compute group bounding boxes from their children
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

    const groupWidth = maxX - minX + GROUP_PADDING * 2
    const groupHeight = maxY - minY + GROUP_PADDING * 2

    return {
      ...group,
      position: {
        x: minX - GROUP_PADDING,
        y: minY - GROUP_PADDING,
      },
      style: {
        width: groupWidth,
        height: groupHeight,
      },
    }
  })

  return [...positioned, ...updatedGroups]
}

/* ------------------------------------------------------------------ */
/*  Store                                                              */
/* ------------------------------------------------------------------ */

export const useFlowStore = create<FlowState>((set, get) => ({
  nodes: [],
  edges: [],
  currentTurn: 0,
  currentLeadId: '',
  currentGroupId: '',
  previousGroupId: null,

  /* ---- Turn lifecycle ------------------------------------------- */

  onNewTurn: (userMessage: string) => {
    const state = get()
    const turn = state.currentTurn + 1
    const groupId = `group-${turn}`
    const userId = `user-${turn}`
    const leadId = `lead-${turn}`

    const newNodes: Node[] = [
      // Turn group container
      {
        id: groupId,
        type: 'turnGroup',
        position: { x: 0, y: 0 },
        data: { turn },
        style: { width: 400, height: 300 },
      },
      // User message node
      {
        id: userId,
        type: 'user',
        position: { x: 0, y: 0 },
        data: { message: userMessage, _turn: turn },
      },
      // Lead node for this turn
      {
        id: leadId,
        type: 'lead',
        position: { x: 0, y: 0 },
        data: { label: 'Lead Agent', status: 'idle', round: turn, _turn: turn },
      },
    ]

    const newEdges: Edge[] = [
      // User → Lead (within this turn) — particle edge
      {
        id: `e-${userId}-${leadId}`,
        source: userId,
        target: leadId,
        type: 'particle',
        data: { status: 'active', color: '#6b7280' },
      },
    ]

    // Spine: chain previous turn's group → this turn's group (dashed)
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

  /* ---- Agent events --------------------------------------------- */

  onSubagentSpawned: (agentId, agentType, description) => {
    const state = get()
    const nodeId = `subagent-${agentId}`

    if (state.nodes.some((n) => n.id === nodeId)) return

    const newNode: Node = {
      id: nodeId,
      type: 'subagent',
      position: { x: 0, y: 0 },
      data: {
        agentId, agentType, description,
        status: 'running', textPreview: '',
        _turn: state.currentTurn,
      },
    }

    const newEdge: Edge = {
      id: `e-${state.currentLeadId}-${nodeId}`,
      source: state.currentLeadId,
      target: nodeId,
      type: 'particle',
      data: { status: 'active', color: '#3b82f6' },
    }

    set({
      nodes: [...state.nodes, newNode],
      edges: [...state.edges, newEdge],
    })

    get().recomputeLayout()
  },

  onSubagentCompleted: (agentId, status) => {
    const targetId = `subagent-${agentId}`
    set((state) => ({
      nodes: state.nodes.map((n) =>
        n.id === targetId ? { ...n, data: { ...n.data, status } } : n,
      ),
      edges: state.edges.map((e) =>
        e.target === targetId
          ? { ...e, data: { ...e.data, status: status === 'success' ? 'success' : 'error' } }
          : e,
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
      data: { name, status: 'running', _turn: state.currentTurn },
    }

    const newEdge: Edge = {
      id: `e-${state.currentLeadId}-${toolId}`,
      source: state.currentLeadId,
      target: toolId,
      type: 'particle',
      data: { status: 'active', color: '#8b5cf6' },
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
        if (targetNode?.type === 'tool' && targetNode.data.name === name && targetNode.data.status === 'running') {
          return { ...e, data: { ...e.data, status: status === 'success' ? 'success' : 'error' } }
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
      // Update user→lead edge status when lead completes
      edges: state.edges.map((e) =>
        e.target === state.currentLeadId
          ? { ...e, data: { ...e.data, status: status === 'done' ? 'success' : status === 'error' ? 'error' : 'active' } }
          : e,
      ),
    })
  },

  /* ---- Lifecycle ------------------------------------------------ */

  onReset: () => {
    set({
      nodes: [],
      edges: [],
      currentTurn: 0,
      currentLeadId: '',
      currentGroupId: '',
      previousGroupId: null,
    })
  },

  /* ---- Layout --------------------------------------------------- */

  recomputeLayout: () => {
    const state = get()
    const laid = applyLayout(state.nodes, state.edges)
    set({ nodes: laid })
  },
}))
