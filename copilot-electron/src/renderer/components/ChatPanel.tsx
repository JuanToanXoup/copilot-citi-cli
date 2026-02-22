import { useEffect, useRef, useState, forwardRef } from 'react'
import { useAgentStore, type ChatMessage } from '../stores/agent-store'
import { useSettingsStore } from '../stores/settings-store'

interface ChatPanelProps {
  selectedNodeId: string | null
  onNodeSelect: (node: null) => void
}

export function ChatPanel({ selectedNodeId }: ChatPanelProps) {
  const messages = useAgentStore((s) => s.messages)
  const toolDisplayMode = useSettingsStore((s) => s.toolDisplayMode)
  const scrollRef = useRef<HTMLDivElement>(null)
  const messageRefs = useRef<Map<string, HTMLDivElement>>(new Map())
  const [highlightedMsgId, setHighlightedMsgId] = useState<string | null>(null)
  const [showHiddenTools, setShowHiddenTools] = useState(false)

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages.length])

  // Scroll to selected node's corresponding message, set highlight with fade
  useEffect(() => {
    if (!selectedNodeId) {
      setHighlightedMsgId(null)
      return
    }
    const msg = messages.find((m) => m.nodeId === selectedNodeId)
    if (!msg) return
    setHighlightedMsgId(msg.id)
    const el = messageRefs.current.get(msg.id)
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
    const timer = setTimeout(() => setHighlightedMsgId(null), 3000)
    return () => clearTimeout(timer)
  }, [selectedNodeId, messages])

  // Filter tool messages based on display mode
  const toolCount = messages.filter((m) => m.type === 'tool').length
  const visibleMessages = toolDisplayMode === 'hidden' && !showHiddenTools
    ? messages.filter((m) => m.type !== 'tool')
    : messages

  return (
    <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-4 space-y-3">
      {messages.length === 0 && (
        <div className="flex items-center justify-center h-full text-gray-600 text-sm">
          Send a message or type &quot;demo&quot; to get started
        </div>
      )}
      {toolDisplayMode === 'hidden' && toolCount > 0 && (
        <button
          onClick={() => setShowHiddenTools((s) => !s)}
          className="text-xs text-gray-500 hover:text-gray-300 transition-colors"
        >
          {showHiddenTools ? 'Hide' : 'Show'} {toolCount} tool call{toolCount !== 1 ? 's' : ''}
        </button>
      )}
      {visibleMessages.map((msg) => (
        <MessageBubble
          key={msg.id}
          message={msg}
          isHighlighted={msg.id === highlightedMsgId}
          toolDisplayMode={toolDisplayMode}
          ref={(el) => {
            if (el) messageRefs.current.set(msg.id, el)
            else messageRefs.current.delete(msg.id)
          }}
        />
      ))}
    </div>
  )
}

interface MessageBubbleProps {
  message: ChatMessage
  isHighlighted: boolean
  toolDisplayMode: string
}

const MessageBubble = forwardRef<HTMLDivElement, MessageBubbleProps>(
  ({ message, isHighlighted, toolDisplayMode }, ref) => {
    const [toolExpanded, setToolExpanded] = useState(false)
    const highlightClass = isHighlighted
      ? 'border-l-2 border-l-blue-400 bg-blue-950/20 transition-all duration-300'
      : 'border-l-2 border-l-transparent transition-all duration-700'

    switch (message.type) {
      case 'user':
        return (
          <div ref={ref} className={`flex justify-end ${highlightClass}`}>
            <div className="max-w-[80%] rounded-lg bg-blue-600 px-4 py-2.5 text-sm text-white">
              {message.text}
            </div>
          </div>
        )

      case 'agent':
        return (
          <div ref={ref} className={`max-w-[85%] rounded-lg bg-gray-800 px-4 py-3 text-sm text-gray-200 ${highlightClass}`}>
            <pre className="whitespace-pre-wrap font-sans">{message.text}</pre>
            {message.status === 'running' && (
              <span className="inline-block w-2 h-2 rounded-full bg-blue-400 animate-pulse mt-2" />
            )}
          </div>
        )

      case 'subagent':
        return (
          <div ref={ref} className={`rounded-lg border px-3 py-2 text-xs ${highlightClass} ${
            message.status === 'running' ? 'border-blue-800 bg-blue-950/30' :
            message.status === 'success' ? 'border-green-800 bg-green-950/30' :
            'border-red-800 bg-red-950/30'
          }`}>
            <div className="flex items-center gap-2 text-gray-400">
              <StatusDot status={message.status} />
              <span className="font-medium">{message.meta?.agentType}</span>
            </div>
            <p className="text-gray-300 mt-1 line-clamp-3">{message.text}</p>
          </div>
        )

      case 'tool':
        // Collapsible blocks mode (default)
        if (toolDisplayMode === 'collapsible') {
          return (
            <div
              ref={ref}
              className={`rounded border border-gray-800 text-xs text-gray-400 cursor-pointer hover:border-gray-700 transition-colors ${highlightClass}`}
              onClick={() => setToolExpanded((s) => !s)}
            >
              <div className="flex items-center gap-2 px-3 py-1.5">
                <StatusDot status={message.status} />
                <span className="font-mono">{message.meta?.toolName}</span>
                <span className="text-gray-600 ml-auto">{toolExpanded ? '\u25BE' : '\u25B8'}</span>
              </div>
              {toolExpanded && (
                <div className="px-3 py-2 border-t border-gray-800 text-gray-500 font-mono text-[11px] space-y-1.5">
                  {message.meta?.input && Object.keys(message.meta.input).length > 0 ? (
                    <div>
                      <span className="text-gray-600">Input:</span>
                      <pre className="mt-0.5 text-gray-400 whitespace-pre-wrap max-h-32 overflow-y-auto bg-gray-900/50 rounded p-1.5">
                        {JSON.stringify(message.meta.input, null, 2)}
                      </pre>
                    </div>
                  ) : (
                    <p className="text-gray-600">Input: (none)</p>
                  )}
                  {message.meta?.output ? (
                    <div>
                      <span className="text-gray-600">Output:</span>
                      <pre className="mt-0.5 text-gray-400 whitespace-pre-wrap max-h-40 overflow-y-auto bg-gray-900/50 rounded p-1.5">
                        {message.meta.output}
                      </pre>
                    </div>
                  ) : (
                    <p className="text-gray-600">Output: {message.status === 'running' ? '(pending)' : '(none)'}</p>
                  )}
                  <p>Status: <span className={message.status === 'success' ? 'text-green-400' : message.status === 'error' ? 'text-red-400' : 'text-blue-400'}>{message.status}</span></p>
                </div>
              )}
            </div>
          )
        }
        // Minimal inline mode
        return (
          <div ref={ref} className={`text-xs text-gray-500 py-0.5 ${highlightClass}`}>
            <span className="flex items-center gap-1.5">
              <StatusDot status={message.status} />
              <span className="font-mono">{message.meta?.toolName}</span>
            </span>
          </div>
        )

      case 'terminal':
        return (
          <div ref={ref} className={`rounded border border-gray-800 bg-gray-950 text-xs ${highlightClass}`}>
            <div className="flex items-center gap-2 px-3 py-1.5 border-b border-gray-800">
              <span className="text-purple-400 font-mono text-[10px]">$</span>
              <StatusDot status={message.status} />
              <span className="text-gray-400 font-medium">Terminal</span>
            </div>
            <div className="px-3 py-2">
              <pre className="text-gray-300 font-mono text-[11px] whitespace-pre-wrap">{message.meta?.command ?? message.text}</pre>
              {message.meta?.output && (
                <pre className="mt-1.5 text-gray-500 font-mono text-[11px] whitespace-pre-wrap max-h-32 overflow-y-auto border-t border-gray-800 pt-1.5">
                  {message.meta.output}
                </pre>
              )}
            </div>
          </div>
        )

      case 'error':
        return (
          <div ref={ref} className="rounded-lg border border-red-800 bg-red-950/30 px-4 py-2.5 text-sm text-red-300">
            <p>{message.text}</p>
            <button
              onClick={() => {
                useAgentStore.getState().retryLast()
              }}
              className="mt-2 text-xs px-3 py-1 rounded bg-red-900/50 border border-red-700
                         text-red-300 hover:bg-red-800/50 transition-colors"
            >
              Retry
            </button>
          </div>
        )

      case 'status':
        return (
          <div ref={ref} className="text-center text-xs text-gray-600 py-1">
            {message.text}
          </div>
        )

      default:
        return null
    }
  },
)

MessageBubble.displayName = 'MessageBubble'

function StatusDot({ status }: { status?: string }) {
  if (status === 'running') return <span className="inline-block w-1.5 h-1.5 rounded-full bg-blue-400 animate-pulse" />
  if (status === 'success') return <span className="text-green-400">&#10003;</span>
  if (status === 'error') return <span className="text-red-400">&#10007;</span>
  return <span className="inline-block w-1.5 h-1.5 rounded-full bg-gray-600" />
}
