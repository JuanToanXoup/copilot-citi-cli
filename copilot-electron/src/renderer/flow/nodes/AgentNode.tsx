import { Handle, Position, type NodeProps } from '@xyflow/react'

export function AgentNode({ data }: NodeProps) {
  const { status, text } = data as { status: string; text: string }

  const borderColor =
    status === 'running'
      ? 'border-emerald-400'
      : status === 'done'
        ? 'border-green-400'
        : status === 'error'
          ? 'border-red-400'
          : 'border-gray-600'

  const bgColor =
    status === 'running'
      ? 'bg-emerald-950'
      : status === 'done'
        ? 'bg-green-950'
        : status === 'error'
          ? 'bg-red-950'
          : 'bg-gray-800'

  return (
    <div className={`relative rounded-lg border ${borderColor} ${bgColor} px-4 py-3 shadow-sm w-80`}>
      {status === 'running' && <PulseRing color="rgba(52, 211, 153, 0.4)" />}
      <Handle type="target" position={Position.Top} className="!bg-gray-500" />
      <div className="flex items-center gap-2 mb-1">
        {status === 'running' && (
          <span className="inline-block w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
        )}
        {status === 'done' && (
          <span className="text-green-400 text-xs">&#10003;</span>
        )}
        {status === 'error' && (
          <span className="text-red-400 text-xs">&#10007;</span>
        )}
        <span className="text-xs font-medium text-gray-400">Agent</span>
      </div>
      {text ? (
        <p className={`text-sm text-gray-200 ${status === 'done' ? 'line-clamp-6' : 'line-clamp-4'} whitespace-pre-wrap`}>
          {text}
        </p>
      ) : status === 'running' ? (
        <p className="text-sm text-gray-500 italic">Thinking...</p>
      ) : null}
      <Handle type="source" position={Position.Bottom} className="!bg-gray-500" />
    </div>
  )
}

function PulseRing({ color }: { color: string }) {
  return (
    <div
      className="absolute inset-0 rounded-lg pointer-events-none"
      style={{
        boxShadow: `0 0 0 0 ${color}`,
        animation: 'pulse-ring 2s cubic-bezier(0.4, 0, 0.6, 1) infinite',
      }}
    />
  )
}
