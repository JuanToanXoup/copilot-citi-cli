import { Handle, Position, type NodeProps } from '@xyflow/react'

export function UserNode({ data }: NodeProps) {
  const { message } = data as { message: string }

  return (
    <div className="rounded-lg border border-gray-600 bg-gray-800 px-4 py-3 shadow-sm w-64">
      <Handle type="target" position={Position.Top} className="!bg-gray-500" />
      <div className="flex items-center gap-2 mb-1">
        <span className="text-xs font-medium text-gray-400">You</span>
      </div>
      <p className="text-sm text-gray-200 truncate">{message}</p>
      <Handle type="source" position={Position.Bottom} className="!bg-gray-500" />
    </div>
  )
}
