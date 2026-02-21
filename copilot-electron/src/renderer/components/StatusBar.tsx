interface StatusBarProps {
  connected: boolean
  viewMode: string
  model?: string
}

export function StatusBar({ connected, viewMode, model }: StatusBarProps) {
  return (
    <div className="flex items-center justify-between px-4 py-1.5 text-xs text-gray-500 bg-gray-900 border-t border-gray-800 shrink-0">
      <div className="flex items-center gap-3">
        <span className="flex items-center gap-1.5">
          <span
            className={`inline-block w-2 h-2 rounded-full ${
              connected ? 'bg-green-500' : 'bg-red-500'
            }`}
          />
          {connected ? 'Connected' : 'Disconnected'}
        </span>
        <span className="text-gray-600">|</span>
        <span className="capitalize">{viewMode} view</span>
      </div>
      <div className="flex items-center gap-3">
        {model && <span>{model}</span>}
        <span className="text-gray-600">Cmd+\ View · Cmd+/ Sidebar</span>
      </div>
    </div>
  )
}
