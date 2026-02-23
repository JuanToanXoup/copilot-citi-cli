import { useSettingsStore, type ThemeTokens, type ColorMode } from '../../stores/settings-store'

function TokenEditor({ tokenKey, value }: { tokenKey: keyof ThemeTokens; value: string }) {
  return (
    <div className="flex items-center gap-2 bg-gray-800/50 rounded px-2 py-1.5">
      <input
        type="color"
        value={value}
        onChange={(e) => useSettingsStore.getState().setToken(tokenKey, e.target.value)}
        className="w-5 h-5 rounded border-0 cursor-pointer bg-transparent"
      />
      <div className="flex-1 min-w-0">
        <div className="text-[11px] text-gray-300 truncate">{tokenKey}</div>
        <div className="text-[10px] text-gray-500 font-mono">{value}</div>
      </div>
    </div>
  )
}

export function ThemeSettings() {
  const colorMode = useSettingsStore((s) => s.colorMode)
  const tokens = useSettingsStore((s) => s.tokens)

  return (
    <div className="space-y-4">
      <div>
        <label className="text-xs text-gray-400 mb-1 block">Color Mode</label>
        <div className="flex gap-1">
          {(['system', 'light', 'dark'] as ColorMode[]).map((mode) => (
            <button
              key={mode}
              onClick={() => useSettingsStore.getState().setColorMode(mode)}
              className={`px-3 py-1.5 text-xs rounded transition-colors ${
                colorMode === mode ? 'bg-blue-600 text-white' : 'bg-gray-800 text-gray-400 hover:text-white'
              }`}
            >
              {mode.charAt(0).toUpperCase() + mode.slice(1)}
            </button>
          ))}
        </div>
      </div>
      <div className="border-t border-gray-800 pt-3">
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs text-gray-400">Theme Tokens</span>
          <button
            onClick={() => useSettingsStore.getState().resetTokens()}
            className="text-[10px] text-gray-500 hover:text-white transition-colors"
          >
            Reset to defaults
          </button>
        </div>
        <div className="grid grid-cols-2 gap-2">
          {(Object.keys(tokens) as (keyof ThemeTokens)[]).map((key) => (
            <TokenEditor key={key} tokenKey={key} value={tokens[key]} />
          ))}
        </div>
      </div>
    </div>
  )
}
