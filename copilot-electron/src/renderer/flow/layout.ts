import dagre from 'dagre'
import type { Node, Edge } from '@xyflow/react'

export const NODE_WIDTHS: Record<string, number> = {
  user: 260,
  lead: 200,
  subagent: 240,
  tool: 160,
}

export const NODE_HEIGHTS: Record<string, number> = {
  user: 60,
  lead: 56,
  subagent: 80,
  tool: 40,
}

export const GROUP_PADDING = 40

/**
 * Compute Dagre layout for child nodes, then compute bounding boxes
 * for turn group containers.
 */
export function computeLayout(nodes: Node[], edges: Edge[]): Node[] {
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
    if (sourceIsChild && targetIsChild) {
      g.setEdge(edge.source, edge.target)
    }
  }

  dagre.layout(g)

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
      position: {
        x: minX - GROUP_PADDING,
        y: minY - GROUP_PADDING,
      },
      style: {
        width: maxX - minX + GROUP_PADDING * 2,
        height: maxY - minY + GROUP_PADDING * 2,
      },
    }
  })

  return [...positioned, ...updatedGroups]
}
