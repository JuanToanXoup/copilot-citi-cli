import { useState } from 'react'
import { useAgentStore, type FileChange } from '../stores/agent-store'

interface ChangesPanelProps {
  onClose: () => void
}

export function ChangesPanel({ onClose }: ChangesPanelProps) {
  const changedFiles = useAgentStore((s) => s.changedFiles)

  const handleAcceptAll = () => {
    const store = useAgentStore.getState()
    for (const f of changedFiles) {
      store.acceptChange(f.path)
    }
  }

  const handleRejectAll = () => {
    const store = useAgentStore.getState()
    // Reject in reverse to avoid index issues with state updates
    for (const f of [...changedFiles].reverse()) {
      store.rejectChange(f.path)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={onClose}>
      <div
        className="w-[640px] max-h-[80vh] bg-gray-900 border border-gray-700 rounded-xl shadow-2xl flex flex-col overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-4 py-3 border-b border-gray-800">
          <div className="flex items-center gap-2">
            <h2 className="text-sm font-semibold text-gray-100">Changes</h2>
            <span className="text-xs text-gray-500">{changedFiles.length} files</span>
          </div>
          <div className="flex items-center gap-2">
            {changedFiles.length > 1 && (
              <>
                <button
                  onClick={handleAcceptAll}
                  className="text-xs px-2 py-1 text-green-400 hover:text-green-300 hover:bg-green-500/10 rounded transition-colors"
                  title="Accept all changes"
                >
                  Accept All
                </button>
                <button
                  onClick={handleRejectAll}
                  className="text-xs px-2 py-1 text-red-400 hover:text-red-300 hover:bg-red-500/10 rounded transition-colors"
                  title="Reject all changes (revert files)"
                >
                  Reject All
                </button>
              </>
            )}
            {changedFiles.length > 0 && (
              <button
                onClick={() => useAgentStore.getState().clearChanges()}
                className="text-xs px-2 py-1 text-gray-400 hover:text-white transition-colors"
              >
                Clear all
              </button>
            )}
            <button onClick={onClose} className="text-gray-500 hover:text-white text-sm ml-2">
              Esc
            </button>
          </div>
        </div>
        <div className="flex-1 overflow-y-auto">
          {changedFiles.length === 0 ? (
            <div className="flex items-center justify-center h-32 text-sm text-gray-500">
              No file changes to review
            </div>
          ) : (
            <div className="divide-y divide-gray-800">
              {changedFiles.map((change) => (
                <FileChangeRow
                  key={change.path}
                  change={change}
                  onAccept={() => useAgentStore.getState().acceptChange(change.path)}
                  onReject={() => useAgentStore.getState().rejectChange(change.path)}
                />
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function FileChangeRow({
  change,
  onAccept,
  onReject,
}: {
  change: FileChange
  onAccept: () => void
  onReject: () => void
}) {
  const [expanded, setExpanded] = useState(false)
  const [diffContent, setDiffContent] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const statusColor =
    change.action === 'created' ? 'text-green-400' :
    change.action === 'deleted' ? 'text-red-400' :
    'text-yellow-400'

  const statusLabel =
    change.action === 'created' ? 'A' :
    change.action === 'deleted' ? 'D' :
    'M'

  const handleToggleExpand = async () => {
    if (!expanded && diffContent === null) {
      setLoading(true)
      try {
        const diff = await window.api?.git?.diff(change.path)
        setDiffContent(diff || '(no diff available - file may be untracked)')
      } catch {
        setDiffContent('(failed to load diff)')
      }
      setLoading(false)
    }
    setExpanded(!expanded)
  }

  return (
    <div className="transition-colors">
      <div
        className="flex items-center gap-3 px-4 py-2 hover:bg-gray-800/50 cursor-pointer"
        onClick={handleToggleExpand}
      >
        <svg
          width="10"
          height="10"
          viewBox="0 0 10 10"
          fill="currentColor"
          className={`text-gray-600 transition-transform shrink-0 ${expanded ? 'rotate-90' : ''}`}
        >
          <path d="M3 2l4 3-4 3z" />
        </svg>
        <span className={`text-xs font-mono font-bold ${statusColor} w-4`}>{statusLabel}</span>
        <span className="text-sm text-gray-300 font-mono flex-1 truncate">{change.path}</span>
        <button
          onClick={(e) => { e.stopPropagation(); onAccept() }}
          className="text-xs text-green-400 hover:text-green-300 transition-colors px-1"
          title="Accept change"
        >
          &#10003;
        </button>
        <button
          onClick={(e) => { e.stopPropagation(); onReject() }}
          className="text-xs text-red-400 hover:text-red-300 transition-colors px-1"
          title="Reject change (revert file)"
        >
          &#10007;
        </button>
      </div>

      {expanded && (
        <div className="px-4 pb-3">
          {loading ? (
            <div className="text-xs text-gray-500 py-2">Loading diff...</div>
          ) : (
            <pre className="text-[11px] font-mono bg-gray-950 rounded border border-gray-800 p-3 overflow-x-auto max-h-64 overflow-y-auto whitespace-pre">
              {diffContent?.split('\n').map((line, i) => {
                let color = 'text-gray-400'
                if (line.startsWith('+') && !line.startsWith('+++')) color = 'text-green-400'
                else if (line.startsWith('-') && !line.startsWith('---')) color = 'text-red-400'
                else if (line.startsWith('@@')) color = 'text-blue-400'
                else if (line.startsWith('diff') || line.startsWith('index')) color = 'text-gray-600'
                return (
                  <span key={i} className={color}>
                    {line}
                    {'\n'}
                  </span>
                )
              })}
            </pre>
          )}
        </div>
      )}
    </div>
  )
}
