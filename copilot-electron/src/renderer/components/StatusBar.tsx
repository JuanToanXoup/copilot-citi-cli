interface StatusBarProps {
  connected: boolean
  viewMode: string
  model?: string
  branch?: string
  changesCount?: number
}

export function StatusBar({ connected, viewMode, model, branch, changesCount }: StatusBarProps) {
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
        <span className="text-gray-700">|</span>
        <span className="capitalize">{viewMode} view</span>
        {branch && (
          <>
            <span className="text-gray-700">|</span>
            <span className="flex items-center gap-1.5 cursor-pointer hover:text-gray-300 transition-colors" title="Click to switch branch">
              <BranchIcon />
              {branch}
              {changesCount != null && changesCount > 0 && (
                <span className="text-yellow-500 ml-0.5">{changesCount} changes</span>
              )}
            </span>
          </>
        )}
      </div>
      <div className="flex items-center gap-3">
        {model && <span>{model}</span>}
        <span className="text-gray-600">Cmd+\ View · Cmd+/ Sidebar · Cmd+K Search</span>
      </div>
    </div>
  )
}

function BranchIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 16 16" fill="currentColor" className="text-gray-500">
      <path fillRule="evenodd" d="M11.75 2.5a.75.75 0 1 0 0 1.5.75.75 0 0 0 0-1.5zm-2.25.75a2.25 2.25 0 1 1 3 2.122V6A2.5 2.5 0 0 1 10 8.5H6a1 1 0 0 0-1 1v1.128a2.251 2.251 0 1 1-1.5 0V5.372a2.25 2.25 0 1 1 1.5 0v1.836A2.492 2.492 0 0 1 6 7h4a1 1 0 0 0 1-1v-.628A2.25 2.25 0 0 1 9.5 3.25zM4.25 12a.75.75 0 1 0 0 1.5.75.75 0 0 0 0-1.5zM3.5 3.25a.75.75 0 1 1 1.5 0 .75.75 0 0 1-1.5 0z" />
    </svg>
  )
}
