export function VcsPanel({ changedFiles, gitStatus }: { changedFiles: Array<{ path: string; action: string }>; gitStatus: Map<string, string> }) {
  // Build working tree entries from git status, excluding agent changes
  const agentPaths = new Set(changedFiles.map((f) => f.path))
  const workingTree = Array.from(gitStatus.entries())
    .filter(([file]) => !agentPaths.has(file))
    .map(([file, status]) => ({ file, status }))

  const hasAgent = changedFiles.length > 0
  const hasWorkingTree = workingTree.length > 0
  const isEmpty = !hasAgent && !hasWorkingTree

  return (
    <div className="p-3 h-full flex flex-col">
      <div className="text-[11px] font-semibold text-gray-500 uppercase tracking-wide mb-2">
        Source Control
      </div>
      {isEmpty ? (
        <div className="flex-1 flex items-center justify-center">
          <p className="text-xs text-gray-600 text-center px-4">
            No changes detected
          </p>
        </div>
      ) : (
        <div className="flex-1 overflow-y-auto space-y-3">
          {hasAgent && (
            <div>
              <div className="text-[10px] text-gray-500 uppercase tracking-wide mb-1">Agent Changes</div>
              <div className="space-y-0.5">
                {changedFiles.map((f) => {
                  const color = f.action === 'created' ? 'text-green-400'
                    : f.action === 'deleted' ? 'text-red-400'
                    : 'text-yellow-400'
                  const label = f.action === 'created' ? 'A'
                    : f.action === 'deleted' ? 'D'
                    : 'M'
                  const filename = f.path.split('/').pop() || f.path
                  return (
                    <div key={f.path} className="flex items-center gap-2 text-xs px-1 py-0.5 rounded hover:bg-gray-800">
                      <span className={`font-mono font-bold ${color} w-3`}>{label}</span>
                      <span className="text-gray-400 truncate font-mono">{filename}</span>
                    </div>
                  )
                })}
              </div>
            </div>
          )}
          {hasWorkingTree && (
            <div>
              <div className="text-[10px] text-gray-500 uppercase tracking-wide mb-1">Working Tree</div>
              <div className="space-y-0.5">
                {workingTree.map(({ file, status }) => {
                  const color = status === '??' || status === 'A' ? 'text-green-400'
                    : status === 'D' ? 'text-red-400'
                    : 'text-yellow-400'
                  const label = status === '??' ? '?' : status === 'A' ? 'A' : status === 'D' ? 'D' : 'M'
                  const filename = file.split('/').pop() || file
                  return (
                    <div key={file} className="flex items-center gap-2 text-xs px-1 py-0.5 rounded hover:bg-gray-800">
                      <span className={`font-mono font-bold ${color} w-3`}>{label}</span>
                      <span className="text-gray-400 truncate font-mono">{filename}</span>
                    </div>
                  )
                })}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
