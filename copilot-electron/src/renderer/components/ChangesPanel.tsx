import { useAgentStore, type FileChange } from '../stores/agent-store'

interface ChangesPanelProps {
  onClose: () => void
}

export function ChangesPanel({ onClose }: ChangesPanelProps) {
  const changedFiles = useAgentStore((s) => s.changedFiles)

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={onClose}>
      <div
        className="w-[560px] max-h-[70vh] bg-gray-900 border border-gray-700 rounded-xl shadow-2xl flex flex-col overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-4 py-3 border-b border-gray-800">
          <div className="flex items-center gap-2">
            <h2 className="text-sm font-semibold text-gray-100">Changes</h2>
            <span className="text-xs text-gray-500">{changedFiles.length} files</span>
          </div>
          <div className="flex items-center gap-2">
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
  const statusColor =
    change.action === 'created' ? 'text-green-400' :
    change.action === 'deleted' ? 'text-red-400' :
    'text-yellow-400'

  const statusLabel =
    change.action === 'created' ? 'A' :
    change.action === 'deleted' ? 'D' :
    'M'

  return (
    <div className="flex items-center gap-3 px-4 py-2 hover:bg-gray-800/50 transition-colors">
      <span className={`text-xs font-mono font-bold ${statusColor} w-4`}>{statusLabel}</span>
      <span className="text-sm text-gray-300 font-mono flex-1 truncate">{change.path}</span>
      <button
        onClick={onAccept}
        className="text-xs text-green-400 hover:text-green-300 transition-colors"
        title="Accept change"
      >
        &#10003;
      </button>
      <button
        onClick={onReject}
        className="text-xs text-red-400 hover:text-red-300 transition-colors"
        title="Reject change"
      >
        &#10007;
      </button>
    </div>
  )
}
