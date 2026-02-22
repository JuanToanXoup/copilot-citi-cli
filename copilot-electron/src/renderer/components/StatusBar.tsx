import { useState, useEffect, useRef } from 'react'

interface StatusBarProps {
  connected: boolean
  viewMode: string
  model?: string
  branch?: string
  changesCount?: number
}

export function StatusBar({ connected, viewMode, model, branch, changesCount }: StatusBarProps) {
  const [switcherOpen, setSwitcherOpen] = useState(false)
  const [branches, setBranches] = useState<string[]>([])
  const [creating, setCreating] = useState(false)
  const [newBranch, setNewBranch] = useState('')
  const switcherRef = useRef<HTMLDivElement>(null)

  // Close on outside click
  useEffect(() => {
    if (!switcherOpen) return
    const handler = (e: MouseEvent) => {
      if (switcherRef.current && !switcherRef.current.contains(e.target as HTMLElement)) {
        setSwitcherOpen(false)
        setCreating(false)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [switcherOpen])

  const handleBranchClick = async () => {
    if (!window.api?.git?.branches) return
    const list = await window.api.git.branches()
    setBranches(list)
    setSwitcherOpen((s) => !s)
  }

  const handleCheckout = async (name: string) => {
    if (!window.api?.git?.checkout) return
    await window.api.git.checkout(name)
    setSwitcherOpen(false)
  }

  const handleCreateBranch = async () => {
    if (!newBranch.trim() || !window.api?.git?.checkout) return
    await window.api.git.checkout(newBranch.trim(), true)
    setNewBranch('')
    setCreating(false)
    setSwitcherOpen(false)
  }

  return (
    <div className="flex items-center justify-between px-4 py-1.5 text-xs text-token-text-secondary bg-token-surface border-t border-token-border shrink-0">
      <div className="flex items-center gap-3">
        <span className="flex items-center gap-1.5">
          <span
            className={`inline-block w-2 h-2 rounded-full ${
              connected ? 'bg-token-success' : 'bg-token-error'
            }`}
          />
          {connected ? 'Connected' : 'Disconnected'}
        </span>
        <span className="text-token-border">|</span>
        <span className="capitalize">{viewMode} view</span>
        {branch && (
          <>
            <span className="text-token-border">|</span>
            <div className="relative" ref={switcherRef}>
              <span
                onClick={handleBranchClick}
                className="flex items-center gap-1.5 cursor-pointer hover:text-token-text transition-colors"
                title="Click to switch branch"
              >
                <BranchIcon />
                {branch}
                {changesCount != null && changesCount > 0 && (
                  <span className="text-token-warning ml-0.5">{changesCount} changes</span>
                )}
              </span>
              {switcherOpen && (
                <div className="absolute bottom-full left-0 mb-1 w-52 bg-gray-900 border border-gray-700 rounded-lg shadow-xl overflow-hidden z-50">
                  {creating ? (
                    <div className="p-2">
                      <input
                        autoFocus
                        value={newBranch}
                        onChange={(e) => setNewBranch(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') handleCreateBranch()
                          if (e.key === 'Escape') { setCreating(false); setNewBranch('') }
                        }}
                        placeholder="new-branch-name"
                        className="w-full px-2 py-1 text-xs bg-gray-800 border border-gray-700 rounded text-gray-200 placeholder-gray-600 outline-none focus:border-blue-500"
                      />
                    </div>
                  ) : (
                    <>
                      <button
                        onClick={() => setCreating(true)}
                        className="w-full text-left px-3 py-1.5 text-xs text-blue-400 hover:bg-gray-800 transition-colors border-b border-gray-800"
                      >
                        + Create new branch
                      </button>
                      <div className="max-h-48 overflow-y-auto">
                        {branches.map((b) => (
                          <button
                            key={b}
                            onClick={() => handleCheckout(b)}
                            className={`w-full text-left px-3 py-1.5 text-xs hover:bg-gray-800 transition-colors truncate ${
                              b === branch ? 'text-blue-400 font-medium' : 'text-gray-300'
                            }`}
                          >
                            {b === branch ? `● ${b}` : b}
                          </button>
                        ))}
                      </div>
                    </>
                  )}
                </div>
              )}
            </div>
          </>
        )}
      </div>
      <div className="flex items-center gap-3">
        {model && <span>{model}</span>}
        <span className="text-token-text-secondary/60">Cmd+\ View · Cmd+/ Sidebar · Cmd+K Search</span>
      </div>
    </div>
  )
}

function BranchIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 16 16" fill="currentColor" className="text-token-text-secondary">
      <path fillRule="evenodd" d="M11.75 2.5a.75.75 0 1 0 0 1.5.75.75 0 0 0 0-1.5zm-2.25.75a2.25 2.25 0 1 1 3 2.122V6A2.5 2.5 0 0 1 10 8.5H6a1 1 0 0 0-1 1v1.128a2.251 2.251 0 1 1-1.5 0V5.372a2.25 2.25 0 1 1 1.5 0v1.836A2.492 2.492 0 0 1 6 7h4a1 1 0 0 0 1-1v-.628A2.25 2.25 0 0 1 9.5 3.25zM4.25 12a.75.75 0 1 0 0 1.5.75.75 0 0 0 0-1.5zM3.5 3.25a.75.75 0 1 1 1.5 0 .75.75 0 0 1-1.5 0z" />
    </svg>
  )
}
