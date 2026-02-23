export function ToolConfirmDialog({ name, input, onApprove, onDeny }: {
  name: string
  input: Record<string, unknown>
  onApprove: () => void
  onDeny: () => void
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="w-[440px] bg-gray-900 border border-gray-700 rounded-xl shadow-2xl overflow-hidden">
        <div className="px-4 py-3 border-b border-gray-800">
          <h3 className="text-sm font-semibold text-gray-100">Tool Confirmation Required</h3>
          <p className="text-xs text-gray-400 mt-1">
            The agent wants to execute <span className="font-mono text-yellow-400">{name}</span>
          </p>
        </div>
        <div className="px-4 py-3 max-h-48 overflow-y-auto">
          <pre className="text-xs text-gray-400 font-mono whitespace-pre-wrap bg-gray-950 rounded p-2">
            {JSON.stringify(input, null, 2)}
          </pre>
        </div>
        <div className="flex items-center justify-end gap-2 px-4 py-3 border-t border-gray-800">
          <button
            onClick={onDeny}
            className="text-xs px-3 py-1.5 bg-gray-800 border border-gray-700 rounded
                       text-gray-400 hover:text-white transition-colors"
          >
            Deny
          </button>
          <button
            onClick={onApprove}
            className="text-xs px-3 py-1.5 bg-green-600 border border-green-500 rounded
                       text-white hover:bg-green-500 transition-colors"
          >
            Approve
          </button>
        </div>
      </div>
    </div>
  )
}
