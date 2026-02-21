import type { Node } from '@xyflow/react'

interface NodeDetailProps {
  node: Node | null
  onClose: () => void
}

export function NodeDetail({ node, onClose }: NodeDetailProps) {
  if (!node) return null

  return (
    <div className="absolute right-4 top-4 w-80 max-h-[calc(100%-2rem)] overflow-y-auto
                    bg-gray-900 border border-gray-700 rounded-lg shadow-xl z-10">
      <div className="flex items-center justify-between px-4 py-3 border-b border-gray-700">
        <span className="text-sm font-semibold text-gray-200">
          {node.type === 'user' ? 'User Message' :
           node.type === 'lead' ? 'Lead Agent' :
           node.type === 'subagent' ? (node.data.agentType as string) :
           node.type === 'tool' ? `Tool: ${node.data.name}` : 'Node'}
        </span>
        <button
          onClick={onClose}
          className="text-gray-500 hover:text-gray-300 text-lg leading-none"
        >
          &times;
        </button>
      </div>
      <div className="p-4 text-sm text-gray-400 space-y-2">
        {node.type === 'user' && <p>{node.data.message as string}</p>}
        {node.type === 'subagent' && (
          <>
            <p><strong className="text-gray-300">Description:</strong> {node.data.description as string}</p>
            <p><strong className="text-gray-300">Status:</strong> {node.data.status as string}</p>
            {node.data.textPreview && (
              <pre className="text-xs bg-gray-800 rounded p-2 whitespace-pre-wrap">
                {node.data.textPreview as string}
              </pre>
            )}
          </>
        )}
        {node.type === 'lead' && (
          <>
            <p><strong className="text-gray-300">Status:</strong> {node.data.status as string}</p>
            <p><strong className="text-gray-300">Turn:</strong> {node.data.round as number}</p>
          </>
        )}
        {node.type === 'tool' && (
          <>
            <p><strong className="text-gray-300">Tool:</strong> {node.data.name as string}</p>
            <p><strong className="text-gray-300">Status:</strong> {node.data.status as string}</p>
          </>
        )}
      </div>
    </div>
  )
}
