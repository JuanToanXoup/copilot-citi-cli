import dagre from 'dagre'
import type { Node, Edge } from '@xyflow/react'

export const NODE_WIDTHS: Record<string, number> = {
  user: 260,
  agent: 320,
  tool: 240,
  toolResult: 240,
  terminal: 210,
  terminalResult: 240,
  subagent: 240,
  subagentResult: 240,
}

export const NODE_HEIGHTS: Record<string, number> = {
  user: 60,
  agent: 100,
  tool: 56,
  toolResult: 56,
  terminal: 56,
  terminalResult: 56,
  subagent: 80,
  subagentResult: 56,
}

/**
 * Dagre hierarchical layout — parallel work (subagents, tools)
 * fans out horizontally, sequential flow goes top-to-bottom.
 */
export function computeLayout(nodes: Node[], edges: Edge[]): Node[] {
  if (nodes.length === 0) return nodes

  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'TB', nodesep: 40, ranksep: 60, marginx: 40, marginy: 40 })

  for (const node of nodes) {
    const width = NODE_WIDTHS[node.type ?? 'agent'] ?? 200
    const height = NODE_HEIGHTS[node.type ?? 'agent'] ?? 60
    g.setNode(node.id, { width, height })
  }

  for (const edge of edges) {
    if (nodes.some((n) => n.id === edge.source) && nodes.some((n) => n.id === edge.target)) {
      g.setEdge(edge.source, edge.target)
    }
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
