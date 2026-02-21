import dagre from 'dagre'
import type { Node, Edge } from '@xyflow/react'

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

export function computeLayout(nodes: Node[], edges: Edge[]): Node[] {
  if (nodes.length === 0) return nodes

  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'TB', nodesep: 60, ranksep: 80, marginx: 40, marginy: 40 })

  for (const node of nodes) {
    const width = NODE_WIDTHS[node.type ?? 'subagent'] ?? 200
    const height = NODE_HEIGHTS[node.type ?? 'subagent'] ?? 60
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
