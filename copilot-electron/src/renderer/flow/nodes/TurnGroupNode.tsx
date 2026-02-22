import { Handle, Position, type NodeProps } from '@xyflow/react'

export function TurnGroupNode({ data }: NodeProps) {
  const { turn } = data as { turn: number }

  return (
    <div className="turn-group-container">
      <Handle type="target" position={Position.Top} className="!bg-transparent !border-0" />
      <span className="turn-group-label">Turn {turn}</span>
      <Handle type="source" position={Position.Bottom} className="!bg-transparent !border-0" />
    </div>
  )
}
