interface StatusBarProps {
  connected: boolean
  model?: string
}

export function StatusBar({ connected, model }: StatusBarProps) {
  return (
    <div className="flex items-center justify-between px-4 py-1.5 text-xs text-gray-500 bg-gray-900 border-t border-gray-800">
      <div className="flex items-center gap-2">
        <span
          className={`inline-block w-2 h-2 rounded-full ${
            connected ? 'bg-green-500' : 'bg-red-500'
          }`}
        />
        <span>{connected ? 'Connected' : 'Disconnected'}</span>
      </div>
      {model && <span>{model}</span>}
    </div>
  )
}
