import { Handle, Position, type NodeProps } from '@xyflow/react'

export function SubagentNode({ data }: NodeProps) {
  const { agentType, description, status, textPreview } = data as {
    agentType: string
    description: string
    status: string
    textPreview?: string
  }

  const borderColor =
    status === 'running'
      ? 'border-blue-400'
      : status === 'success'
        ? 'border-green-400'
        : status === 'error'
          ? 'border-red-400'
          : 'border-gray-600'

  const bgColor =
    status === 'running'
      ? 'bg-blue-950'
      : status === 'success'
        ? 'bg-green-950'
        : status === 'error'
          ? 'bg-red-950'
          : 'bg-gray-800'

  return (
    <div className={`relative rounded-lg border ${borderColor} ${bgColor} px-4 py-3 shadow-sm w-60`}>
      {status === 'running' && <PulseRing color="rgba(96, 165, 250, 0.4)" />}
      <Handle type="target" position={Position.Top} className="!bg-blue-400" />
      <div className="flex items-center gap-2 mb-1">
        <StatusIcon status={status} />
        <span className="text-xs font-medium text-gray-400">{agentType}</span>
      </div>
      <p className="text-sm font-medium text-gray-200 truncate">{description}</p>
      {textPreview && (
        <p className="text-xs text-gray-500 mt-1 line-clamp-2">{textPreview}</p>
      )}
      <Handle type="source" position={Position.Bottom} className="!bg-blue-400" />
    </div>
  )
}

function StatusIcon({ status }: { status: string }) {
  if (status === 'running') {
    return <span className="inline-block w-2 h-2 rounded-full bg-blue-400 animate-pulse" />
  }
  if (status === 'success') {
    return <span className="text-green-400 text-xs">&#10003;</span>
  }
  if (status === 'error') {
    return <span className="text-red-400 text-xs">&#10007;</span>
  }
  return <span className="inline-block w-2 h-2 rounded-full bg-gray-500" />
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
