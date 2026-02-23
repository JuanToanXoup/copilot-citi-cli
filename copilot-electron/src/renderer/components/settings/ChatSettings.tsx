import { useSettingsStore, type ToolDisplayMode } from '../../stores/settings-store'

export function ChatSettings() {
  const toolDisplayMode = useSettingsStore((s) => s.toolDisplayMode)
  const debugLogging = useSettingsStore((s) => s.debugLogging)

  const modes: { value: ToolDisplayMode; label: string; desc: string }[] = [
    { value: 'collapsible', label: 'Collapsible blocks', desc: 'Compact blocks that expand on click' },
    { value: 'inline', label: 'Minimal inline', desc: 'Small gray labels between messages' },
    { value: 'hidden', label: 'Hidden', desc: 'Show N tool calls toggle' },
  ]

  return (
    <div className="space-y-4">
      <div>
        <label className="text-xs text-gray-400 mb-2 block">Tool Call Display</label>
        <div className="space-y-1.5">
          {modes.map((mode) => (
            <button
              key={mode.value}
              onClick={() => useSettingsStore.getState().setToolDisplayMode(mode.value)}
              className={`w-full text-left px-3 py-2 rounded text-sm transition-colors ${
                toolDisplayMode === mode.value
                  ? 'bg-blue-600/20 border border-blue-500 text-white'
                  : 'bg-gray-800 border border-gray-700 text-gray-400 hover:text-white'
              }`}
            >
              <div className="font-medium">{mode.label}</div>
              <div className="text-xs text-gray-500 mt-0.5">{mode.desc}</div>
            </button>
          ))}
        </div>
      </div>
      <div className="border-t border-gray-800 pt-4">
        <div className="flex items-center justify-between">
          <div>
            <div className="text-sm text-gray-300">Debug Logging</div>
            <div className="text-xs text-gray-500 mt-0.5">Write verbose logs to .copilot/logs/</div>
          </div>
          <button
            onClick={() => useSettingsStore.getState().setDebugLogging(!debugLogging)}
            className={`relative w-9 h-5 rounded-full transition-colors ${
              debugLogging ? 'bg-blue-600' : 'bg-gray-700'
            }`}
          >
            <span
              className={`absolute top-0.5 w-4 h-4 bg-white rounded-full transition-transform ${
                debugLogging ? 'translate-x-4' : 'translate-x-0.5'
              }`}
            />
          </button>
        </div>
      </div>
    </div>
  )
}
