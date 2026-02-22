import { Handle, Position, type NodeProps } from '@xyflow/react'

export function TerminalNode({ data }: NodeProps) {
  const { command, status } = data as { command: string; status: string }

  const borderColor =
    status === 'running'
      ? 'border-purple-400'
      : status === 'success'
        ? 'border-green-400'
        : status === 'error'
          ? 'border-red-400'
          : 'border-gray-600'

  return (
    <div className={`relative rounded-lg border ${borderColor} bg-gray-900 px-3 py-2 shadow-sm w-52`}>
      {status === 'running' && <PulseRing color="rgba(168, 85, 247, 0.4)" />}
      <Handle type="target" position={Position.Top} className="!bg-gray-500" />
      <div className="flex items-center gap-2 mb-1">
        <span className="text-[10px] font-mono text-purple-400">$</span>
        <span className="text-xs font-medium text-gray-400">Terminal</span>
        {status === 'running' && (
          <span className="inline-block w-1.5 h-1.5 rounded-full bg-purple-400 animate-pulse" />
        )}
      </div>
      <p className="text-xs text-gray-300 font-mono truncate">{command}</p>
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
