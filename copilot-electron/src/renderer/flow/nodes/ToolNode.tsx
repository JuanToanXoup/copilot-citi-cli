import { Handle, Position, type NodeProps } from '@xyflow/react'

export function ToolNode({ data }: NodeProps) {
  const { name, status } = data as { name: string; status: string }

  const borderColor =
    status === 'running'
      ? 'border-purple-400'
      : status === 'success'
        ? 'border-green-400'
        : status === 'error'
          ? 'border-red-400'
          : 'border-gray-600'

  const bgColor =
    status === 'running'
      ? 'bg-purple-950'
      : status === 'success'
        ? 'bg-green-950'
        : status === 'error'
          ? 'bg-red-950'
          : 'bg-gray-800'

  return (
    <div className={`rounded-lg border ${borderColor} ${bgColor} px-3 py-2 shadow-sm`}>
      <Handle type="target" position={Position.Top} className="!bg-purple-400" />
      <div className="flex items-center gap-2">
        {status === 'running' && (
          <span className="inline-block w-2 h-2 rounded-full bg-purple-400 animate-pulse" />
        )}
        <span className="text-xs font-medium text-gray-300">{name}</span>
      </div>
    </div>
  )
}
