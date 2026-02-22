import { useSettingsStore, type ToolDef } from '../stores/settings-store'

interface ToolPopoverProps {
  onClose: () => void
}

export function ToolPopover({ onClose }: ToolPopoverProps) {
  const tools = useSettingsStore((s) => s.tools)

  // Group by source (built-in vs each MCP server name)
  const grouped = tools.reduce<Record<string, ToolDef[]>>((acc, tool) => {
    const key = tool.source
    if (!acc[key]) acc[key] = []
    acc[key].push(tool)
    return acc
  }, {})

  // Sort: built-in first, then alphabetical
  const sortedSources = Object.keys(grouped).sort((a, b) => {
    if (a === 'built-in') return -1
    if (b === 'built-in') return 1
    return a.localeCompare(b)
  })

  return (
    <div className="absolute bottom-full left-0 mb-2 w-80 bg-gray-900 border border-gray-700 rounded-xl shadow-2xl overflow-hidden z-50">
      <div className="flex items-center justify-between px-4 py-2.5 border-b border-gray-800">
        <span className="text-sm font-medium text-gray-200">Available Tools</span>
        <button onClick={onClose} className="text-gray-500 hover:text-white text-xs">
          Close
        </button>
      </div>
      <div className="max-h-72 overflow-y-auto p-2 space-y-3">
        {sortedSources.map((source) => (
          <div key={source}>
            <div className="flex items-center gap-2 text-[10px] font-semibold text-gray-500 uppercase tracking-wide px-2 py-1">
              <span>{source === 'built-in' ? 'Built-in' : source}</span>
              {source !== 'built-in' && (
                <ServerStatusDot tools={grouped[source]} />
              )}
            </div>
            <div className="space-y-0.5">
              {grouped[source].map((tool) => (
                <ToolRow key={tool.name} tool={tool} />
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function ServerStatusDot({ tools }: { tools: ToolDef[] }) {
  const hasError = tools.some(t => t.status === 'error')
  const allDisabled = tools.every(t => t.status === 'disabled')

  if (hasError) return <span className="inline-block w-1.5 h-1.5 rounded-full bg-red-400" title="Error" />
  if (allDisabled) return <span className="inline-block w-1.5 h-1.5 rounded-full bg-gray-500" title="Disconnected" />
  return <span className="inline-block w-1.5 h-1.5 rounded-full bg-green-400" title="Connected" />
}

function ToolRow({ tool }: { tool: ToolDef }) {
  const statusColor =
    tool.status === 'available' ? 'bg-green-500' :
    tool.status === 'error' ? 'bg-red-500' :
    'bg-gray-600'

  const permLabel =
    tool.permission === 'auto' ? 'Auto' :
    tool.permission === 'confirm' ? 'Confirm' :
    'Ask'

  const permColor =
    tool.permission === 'auto' ? 'text-green-400' :
    tool.permission === 'confirm' ? 'text-yellow-400' :
    'text-red-400'

  return (
    <div className="flex items-center gap-2 px-2 py-1.5 rounded hover:bg-gray-800 transition-colors">
      <span className={`inline-block w-1.5 h-1.5 rounded-full ${statusColor} shrink-0`} />
      <div className="flex-1 min-w-0">
        <div className="text-sm text-gray-200 font-mono truncate">{tool.name}</div>
        <div className="text-[11px] text-gray-500 truncate">{tool.description}</div>
      </div>
      <span className={`text-[10px] ${permColor} shrink-0`}>{permLabel}</span>
      {tool.usageCount > 0 && (
        <span className="text-[10px] text-gray-600 shrink-0">{tool.usageCount}x</span>
      )}
    </div>
  )
}
