import { Handle, Position, type NodeProps } from '@xyflow/react'

export function TerminalResultNode({ data }: NodeProps) {
  const { command, status, output } = data as { command: string; status: string; output?: string }

  const borderColor = status === 'success' ? 'border-green-400' : status === 'error' ? 'border-red-400' : 'border-gray-600'
  const bgColor = status === 'success' ? 'bg-green-950' : status === 'error' ? 'bg-red-950' : 'bg-gray-800'

  return (
    <div className={`rounded-lg border ${borderColor} ${bgColor} px-3 py-2 shadow-sm w-60`}>
      <Handle type="target" position={Position.Top} className="!bg-gray-500" />
      <div className="flex items-center gap-2 mb-1">
        {status === 'success' ? (
          <span className="text-green-400 text-xs">&#10003;</span>
        ) : (
          <span className="text-red-400 text-xs">&#10007;</span>
        )}
        <span className="text-xs font-medium text-gray-400">$ result</span>
      </div>
      {output && (
        <p className="text-xs text-gray-300 line-clamp-2 font-mono whitespace-pre-wrap">{output}</p>
      )}
    </div>
  )
}
