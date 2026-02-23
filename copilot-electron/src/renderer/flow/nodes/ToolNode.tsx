import { Handle, Position, type NodeProps } from '@xyflow/react'

export function ToolNode({ data }: NodeProps) {
  const { name, status, input } = data as { name: string; status: string; input?: Record<string, unknown> }
  const inputPreview = input ? formatInput(input) : ''

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
    <div className={`relative rounded-lg border ${borderColor} ${bgColor} px-3 py-2 shadow-sm w-60`}>
      {status === 'running' && <PulseRing color="rgba(192, 132, 252, 0.4)" />}
      <Handle type="target" position={Position.Top} className="!bg-purple-400" />
      <div className="flex items-center gap-2">
        {status === 'running' && (
          <span className="inline-block w-2 h-2 rounded-full bg-purple-400 animate-pulse" />
        )}
        <span className="text-xs font-medium text-gray-300">{name}</span>
      </div>
      {inputPreview && (
        <p className="text-[10px] text-gray-500 truncate mt-1 font-mono">{inputPreview}</p>
      )}
      <Handle type="source" position={Position.Bottom} className="!bg-purple-400" />
    </div>
  )
}

function formatInput(input: Record<string, unknown>): string {
  const entries = Object.entries(input)
  if (entries.length === 0) return ''
  if (entries.length === 1) return `${entries[0][0]}: ${JSON.stringify(entries[0][1])}`
  return entries.map(([k]) => k).join(', ')
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
