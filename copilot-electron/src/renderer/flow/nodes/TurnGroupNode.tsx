import { type NodeProps } from '@xyflow/react'

export function TurnGroupNode({ data }: NodeProps) {
  const { turn } = data as { turn: number }

  return (
    <div className="turn-group-container">
      <span className="turn-group-label">Turn {turn}</span>
    </div>
  )
}
