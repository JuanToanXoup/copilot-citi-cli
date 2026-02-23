import type { Node } from '@xyflow/react'

interface NodeDetailProps {
  node: Node | null
  onClose: () => void
}

function getTitle(node: Node): string {
  switch (node.type) {
    case 'user': return 'User Message'
    case 'agent': return 'Agent Response'
    case 'subagent': return node.data.agentType as string
    case 'subagentResult': return `${node.data.agentType} Result`
    case 'tool': return `Tool: ${node.data.name}`
    case 'toolResult': return `${node.data.name} Result`
    case 'terminal': return 'Terminal'
    case 'terminalResult': return 'Terminal Result'
    default: return 'Node'
  }
}

export function NodeDetail({ node, onClose }: NodeDetailProps) {
  if (!node) return null

  return (
    <div className="absolute right-4 top-4 w-80 max-h-[calc(100%-2rem)] overflow-y-auto
                    bg-gray-900 border border-gray-700 rounded-lg shadow-xl z-10">
      <div className="flex items-center justify-between px-4 py-3 border-b border-gray-700">
        <span className="text-sm font-semibold text-gray-200">{getTitle(node)}</span>
        <button
          onClick={onClose}
          className="text-gray-500 hover:text-gray-300 text-lg leading-none"
        >
          &times;
        </button>
      </div>
      <div className="p-4 text-sm text-gray-400 space-y-2">
        {node.type === 'user' && (
          <p className="whitespace-pre-wrap">{node.data.message as string}</p>
        )}

        {node.type === 'agent' && (
          <>
            <p><strong className="text-gray-300">Status:</strong> {node.data.status as string}</p>
            {node.data.text && (
              <p className="whitespace-pre-wrap text-gray-300">{node.data.text as string}</p>
            )}
          </>
        )}

        {node.type === 'subagent' && (
          <>
            <p><strong className="text-gray-300">Description:</strong> {node.data.description as string}</p>
            <p><strong className="text-gray-300">Status:</strong> {node.data.status as string}</p>
            {node.data.prompt && (
              <>
                <p><strong className="text-gray-300">Prompt:</strong></p>
                <pre className="text-xs bg-gray-800 rounded p-2 whitespace-pre-wrap max-h-40 overflow-y-auto">
                  {node.data.prompt as string}
                </pre>
              </>
            )}
            {node.data.textPreview && (
              <>
                <p><strong className="text-gray-300">Reply:</strong></p>
                <pre className="text-xs bg-gray-800 rounded p-2 whitespace-pre-wrap max-h-60 overflow-y-auto">
                  {node.data.textPreview as string}
                </pre>
              </>
            )}
          </>
        )}

        {node.type === 'subagentResult' && (
          <>
            <p><strong className="text-gray-300">Status:</strong> {node.data.status as string}</p>
            {node.data.text && (
              <pre className="text-xs bg-gray-800 rounded p-2 whitespace-pre-wrap">
                {node.data.text as string}
              </pre>
            )}
          </>
        )}

        {node.type === 'tool' && (
          <>
            <p><strong className="text-gray-300">Tool:</strong> {node.data.name as string}</p>
            <p><strong className="text-gray-300">Status:</strong> {node.data.status as string}</p>
            {node.data.input && Object.keys(node.data.input as object).length > 0 && (
              <pre className="text-xs bg-gray-800 rounded p-2 whitespace-pre-wrap">
                {JSON.stringify(node.data.input, null, 2)}
              </pre>
            )}
          </>
        )}

        {node.type === 'toolResult' && (
          <>
            <p><strong className="text-gray-300">Status:</strong> {node.data.status as string}</p>
            {node.data.output && (
              <pre className="text-xs bg-gray-800 rounded p-2 whitespace-pre-wrap max-h-60 overflow-y-auto">
                {node.data.output as string}
              </pre>
            )}
          </>
        )}

        {node.type === 'terminal' && (
          <>
            <p><strong className="text-gray-300">Command:</strong></p>
            <pre className="text-xs bg-gray-800 rounded p-2 font-mono whitespace-pre-wrap">
              {node.data.command as string}
            </pre>
            <p><strong className="text-gray-300">Status:</strong> {node.data.status as string}</p>
          </>
        )}

        {node.type === 'terminalResult' && (
          <>
            <p><strong className="text-gray-300">Status:</strong> {node.data.status as string}</p>
            {node.data.output && (
              <pre className="text-xs bg-gray-800 rounded p-2 font-mono whitespace-pre-wrap max-h-60 overflow-y-auto">
                {node.data.output as string}
              </pre>
            )}
          </>
        )}
      </div>
    </div>
  )
}
