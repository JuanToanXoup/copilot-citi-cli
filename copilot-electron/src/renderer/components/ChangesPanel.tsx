import { useState } from 'react'

interface FileChange {
  path: string
  status: 'modified' | 'added' | 'deleted'
  additions: number
  deletions: number
}

interface ChangesPanelProps {
  onClose: () => void
}

// Placeholder data — will be populated from git/agent file changes via IPC
const PLACEHOLDER_CHANGES: FileChange[] = []

export function ChangesPanel({ onClose }: ChangesPanelProps) {
  const [changes] = useState<FileChange[]>(PLACEHOLDER_CHANGES)
  const [selected, setSelected] = useState<Set<string>>(new Set())

  const toggleSelect = (path: string) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(path)) next.delete(path)
      else next.add(path)
      return next
    })
  }

  const selectAll = () => {
    if (selected.size === changes.length) setSelected(new Set())
    else setSelected(new Set(changes.map((c) => c.path)))
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={onClose}>
      <div
        className="w-[560px] max-h-[70vh] bg-gray-900 border border-gray-700 rounded-xl shadow-2xl flex flex-col overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-4 py-3 border-b border-gray-800">
          <div className="flex items-center gap-2">
            <h2 className="text-sm font-semibold text-gray-100">Changes</h2>
            <span className="text-xs text-gray-500">{changes.length} files</span>
          </div>
          <div className="flex items-center gap-2">
            {changes.length > 0 && (
              <>
                <button
                  onClick={selectAll}
                  className="text-xs px-2 py-1 text-gray-400 hover:text-white transition-colors"
                >
                  {selected.size === changes.length ? 'Deselect all' : 'Select all'}
                </button>
                <button
                  disabled={selected.size === 0}
                  className="text-xs px-3 py-1 bg-green-600 text-white rounded
                             hover:bg-green-500 disabled:opacity-50 transition-colors"
                >
                  Accept ({selected.size})
                </button>
                <button
                  disabled={selected.size === 0}
                  className="text-xs px-3 py-1 bg-red-600 text-white rounded
                             hover:bg-red-500 disabled:opacity-50 transition-colors"
                >
                  Reject ({selected.size})
                </button>
              </>
            )}
            <button onClick={onClose} className="text-gray-500 hover:text-white text-sm ml-2">
              Esc
            </button>
          </div>
        </div>
        <div className="flex-1 overflow-y-auto">
          {changes.length === 0 ? (
            <div className="flex items-center justify-center h-32 text-sm text-gray-500">
              No file changes to review
            </div>
          ) : (
            <div className="divide-y divide-gray-800">
              {changes.map((change) => (
                <FileChangeRow
                  key={change.path}
                  change={change}
                  isSelected={selected.has(change.path)}
                  onToggle={() => toggleSelect(change.path)}
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
  isSelected,
  onToggle,
}: {
  change: FileChange
  isSelected: boolean
  onToggle: () => void
}) {
  const statusColor =
    change.status === 'added' ? 'text-green-400' :
    change.status === 'deleted' ? 'text-red-400' :
    'text-yellow-400'

  const statusLabel =
    change.status === 'added' ? 'A' :
    change.status === 'deleted' ? 'D' :
    'M'

  return (
    <div
      className={`flex items-center gap-3 px-4 py-2 cursor-pointer hover:bg-gray-800/50 transition-colors ${
        isSelected ? 'bg-gray-800/30' : ''
      }`}
      onClick={onToggle}
    >
      <input
        type="checkbox"
        checked={isSelected}
        onChange={onToggle}
        className="accent-blue-500"
        onClick={(e) => e.stopPropagation()}
      />
      <span className={`text-xs font-mono font-bold ${statusColor} w-4`}>{statusLabel}</span>
      <span className="text-sm text-gray-300 font-mono flex-1 truncate">{change.path}</span>
      <span className="text-xs text-green-400">+{change.additions}</span>
      <span className="text-xs text-red-400">-{change.deletions}</span>
    </div>
  )
}
