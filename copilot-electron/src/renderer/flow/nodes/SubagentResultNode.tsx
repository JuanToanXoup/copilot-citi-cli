import { Handle, Position, type NodeProps } from '@xyflow/react'

export function SubagentResultNode({ data }: NodeProps) {
  const { agentType, status, text } = data as { agentId: string; agentType: string; status: string; text?: string }

  const borderColor = status === 'success' ? 'border-green-400' : status === 'error' ? 'border-red-400' : 'border-gray-600'
  const bgColor = status === 'success' ? 'bg-green-950' : status === 'error' ? 'bg-red-950' : 'bg-gray-800'

  return (
    <div className={`rounded-lg border ${borderColor} ${bgColor} px-3 py-2 shadow-sm w-60`}>
      <Handle type="target" position={Position.Top} className="!bg-blue-400" />
      <div className="flex items-center gap-2 mb-1">
        {status === 'success' ? (
          <span className="text-green-400 text-xs">&#10003;</span>
        ) : (
          <span className="text-red-400 text-xs">&#10007;</span>
        )}
        <span className="text-xs font-medium text-gray-400">{agentType} done</span>
      </div>
      {text && (
        <p className="text-xs text-gray-300 line-clamp-2 whitespace-pre-wrap">{text}</p>
      )}
    </div>
  )
}
