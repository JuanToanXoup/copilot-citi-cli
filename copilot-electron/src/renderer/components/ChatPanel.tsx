import { useEffect, useRef } from 'react'
import { useAgentStore, type ChatMessage } from '../stores/agent-store'

interface ChatPanelProps {
  selectedNodeId: string | null
  onNodeSelect: (node: null) => void
}

export function ChatPanel({ selectedNodeId }: ChatPanelProps) {
  const messages = useAgentStore((s) => s.messages)
  const scrollRef = useRef<HTMLDivElement>(null)
  const messageRefs = useRef<Map<string, HTMLDivElement>>(new Map())

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages.length])

  // Scroll to selected node's corresponding message
  useEffect(() => {
    if (!selectedNodeId) return
    const msg = messages.find((m) => m.nodeId === selectedNodeId)
    if (!msg) return
    const el = messageRefs.current.get(msg.id)
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }
  }, [selectedNodeId, messages])

  return (
    <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-4 space-y-3">
      {messages.length === 0 && (
        <div className="flex items-center justify-center h-full text-gray-600 text-sm">
          Send a message or type "demo" to get started
        </div>
      )}
      {messages.map((msg) => (
        <MessageBubble
          key={msg.id}
          message={msg}
          isHighlighted={msg.nodeId === selectedNodeId}
          ref={(el) => {
            if (el) messageRefs.current.set(msg.id, el)
            else messageRefs.current.delete(msg.id)
          }}
        />
      ))}
    </div>
  )
}

import { forwardRef } from 'react'

interface MessageBubbleProps {
  message: ChatMessage
  isHighlighted: boolean
}

const MessageBubble = forwardRef<HTMLDivElement, MessageBubbleProps>(
  ({ message, isHighlighted }, ref) => {
    const highlightClass = isHighlighted ? 'border-l-2 border-l-blue-400 bg-blue-950/20' : ''

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
        return (
          <div ref={ref} className={`rounded border border-gray-800 px-3 py-1.5 text-xs text-gray-400 ${highlightClass}`}>
            <span className="flex items-center gap-2">
              <StatusDot status={message.status} />
              <span className="font-mono">{message.meta?.toolName}</span>
            </span>
          </div>
        )

      case 'error':
        return (
          <div ref={ref} className="rounded-lg border border-red-800 bg-red-950/30 px-4 py-2.5 text-sm text-red-300">
            {message.text}
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
