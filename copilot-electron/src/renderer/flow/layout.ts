import type { Node } from '@xyflow/react'

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

const GAP = 24
const MAX_WIDTH = 320 // widest node

/**
 * Simple chronological vertical layout.
 * Nodes are stacked top-to-bottom in array order (insertion order),
 * centered horizontally.
 */
export function computeLayout(nodes: Node[]): Node[] {
  if (nodes.length === 0) return nodes

  let y = 0
  return nodes.map((node) => {
    const w = NODE_WIDTHS[node.type ?? 'agent'] ?? 200
    const h = NODE_HEIGHTS[node.type ?? 'agent'] ?? 60
    const x = (MAX_WIDTH - w) / 2
    const positioned = { ...node, position: { x, y } }
    y += h + GAP
    return positioned
  })
}
