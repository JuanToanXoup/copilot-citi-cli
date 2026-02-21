import { Handle, Position, type NodeProps } from '@xyflow/react'

export function LeadNode({ data }: NodeProps) {
  const { label, status, round } = data as { label: string; status: string; round: number }

  const borderColor =
    status === 'running'
      ? 'border-yellow-400'
      : status === 'done'
        ? 'border-green-400'
        : status === 'error'
          ? 'border-red-400'
          : 'border-gray-600'

  const bgColor =
    status === 'running'
      ? 'bg-yellow-950'
      : status === 'done'
        ? 'bg-green-950'
        : status === 'error'
          ? 'bg-red-950'
          : 'bg-gray-800'

  const displayLabel = round > 1 ? `${label} · Turn ${round}` : label

  return (
    <div className={`rounded-lg border ${borderColor} ${bgColor} px-4 py-3 shadow-sm w-48`}>
      <Handle type="target" position={Position.Top} className="!bg-gray-500" />
      <div className="flex items-center gap-2">
        {status === 'running' && (
          <span className="inline-block w-2 h-2 rounded-full bg-yellow-400 animate-pulse" />
        )}
        <span className="text-sm font-semibold text-gray-100">{displayLabel}</span>
      </div>
      <Handle type="source" position={Position.Bottom} className="!bg-gray-500" />
    </div>
  )
}
