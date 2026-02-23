import { useEffect, useRef, useState, forwardRef } from 'react'
import type { ChatMessage } from '../stores/agent-store'
import { useTabAgentStore, useTabStores } from '../contexts/TabStoreContext'
import { useSettingsStore } from '../stores/settings-store'

interface ChatPanelProps {
  selectedNodeId: string | null
  onNodeSelect: (node: null) => void
}

export function ChatPanel({ selectedNodeId }: ChatPanelProps) {
  const { agentStore } = useTabStores()
  const messages = useTabAgentStore((s) => s.messages)
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
        <div className="flex items-center justify-center h-full text-token-text-secondary text-sm">
          Send a message or type &quot;demo&quot; to get started
        </div>
      )}
      {toolDisplayMode === 'hidden' && toolCount > 0 && (
        <button
          onClick={() => setShowHiddenTools((s) => !s)}
          className="text-xs text-token-text-secondary hover:text-token-text transition-colors"
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
    const { agentStore } = useTabStores()
    const [toolExpanded, setToolExpanded] = useState(false)
    const highlightClass = isHighlighted
      ? 'border-l-2 border-l-token-accent bg-token-primary/10 transition-all duration-300'
      : 'border-l-2 border-l-transparent transition-all duration-700'

    switch (message.type) {
      case 'user':
        return (
          <div ref={ref} className={`flex justify-end ${highlightClass}`}>
            <div className="max-w-[80%] rounded-lg bg-token-primary px-4 py-2.5 text-sm text-white">
              {message.text}
            </div>
          </div>
        )

      case 'agent':
        return (
          <div ref={ref} className={`max-w-[85%] rounded-lg bg-token-surface px-4 py-3 text-sm text-token-text ${highlightClass}`}>
            <pre className="whitespace-pre-wrap font-sans">{message.text}</pre>
            {message.status === 'running' && (
              <span className="inline-block w-2 h-2 rounded-full bg-token-accent animate-pulse mt-2" />
            )}
          </div>
        )

      case 'subagent':
        return <SubagentBubble ref={ref} message={message} highlightClass={highlightClass} />

      case 'tool':
        // Collapsible blocks mode (default)
        if (toolDisplayMode === 'collapsible') {
          return (
            <div
              ref={ref}
              className={`rounded border border-token-border text-xs text-token-text-secondary cursor-pointer hover:border-token-text-secondary/40 transition-colors ${highlightClass}`}
              onClick={() => setToolExpanded((s) => !s)}
            >
              <div className="flex items-center gap-2 px-3 py-1.5">
                <StatusDot status={message.status} />
                <span className="font-mono">{message.meta?.toolName}</span>
                {message.meta?.duration != null && (
                  <span className="text-token-text-secondary/50 text-[10px]">{message.meta.duration < 1000 ? `${message.meta.duration}ms` : `${(message.meta.duration / 1000).toFixed(1)}s`}</span>
                )}
                <span className="text-token-text-secondary/50 ml-auto">{toolExpanded ? '\u25BE' : '\u25B8'}</span>
              </div>
              {toolExpanded && (
                <div className="px-3 py-2 border-t border-token-border text-token-text-secondary font-mono text-[11px] space-y-1.5">
                  {message.meta?.input && Object.keys(message.meta.input).length > 0 ? (
                    <div>
                      <span className="text-token-text-secondary/60">Input:</span>
                      <pre className="mt-0.5 text-token-text-secondary whitespace-pre-wrap max-h-32 overflow-y-auto bg-token-bg/50 rounded p-1.5">
                        {JSON.stringify(message.meta.input, null, 2)}
                      </pre>
                    </div>
                  ) : (
                    <p className="text-token-text-secondary/60">Input: (none)</p>
                  )}
                  {message.meta?.output ? (
                    <div>
                      <span className="text-token-text-secondary/60">Output:</span>
                      <pre className="mt-0.5 text-token-text-secondary whitespace-pre-wrap max-h-40 overflow-y-auto bg-token-bg/50 rounded p-1.5">
                        {message.meta.output}
                      </pre>
                    </div>
                  ) : (
                    <p className="text-token-text-secondary/60">Output: {message.status === 'running' ? '(pending)' : '(none)'}</p>
                  )}
                  <p>Status: <span className={message.status === 'success' ? 'text-token-success' : message.status === 'error' ? 'text-token-error' : 'text-token-accent'}>{message.status}</span></p>
                </div>
              )}
            </div>
          )
        }
        // Minimal inline mode
        return (
          <div ref={ref} className={`text-xs text-token-text-secondary py-0.5 ${highlightClass}`}>
            <span className="flex items-center gap-1.5">
              <StatusDot status={message.status} />
              <span className="font-mono">{message.meta?.toolName}</span>
            </span>
          </div>
        )

      case 'terminal':
        return (
          <div ref={ref} className={`rounded border border-token-border bg-token-bg text-xs ${highlightClass}`}>
            <div className="flex items-center gap-2 px-3 py-1.5 border-b border-token-border">
              <span className="text-token-secondary font-mono text-[10px]">$</span>
              <StatusDot status={message.status} />
              <span className="text-token-text-secondary font-medium">Terminal</span>
            </div>
            <div className="px-3 py-2">
              <pre className="text-token-text font-mono text-[11px] whitespace-pre-wrap">{message.meta?.command ?? message.text}</pre>
              {message.meta?.output && (
                <pre className="mt-1.5 text-token-text-secondary font-mono text-[11px] whitespace-pre-wrap max-h-32 overflow-y-auto border-t border-token-border pt-1.5">
                  {message.meta.output}
                </pre>
              )}
            </div>
          </div>
        )

      case 'error':
        return (
          <div ref={ref} className="rounded-lg border border-token-error/40 bg-token-error/10 px-4 py-2.5 text-sm text-token-error">
            <p>{message.text}</p>
            <button
              onClick={() => {
                agentStore.getState().retryLast()
              }}
              className="mt-2 text-xs px-3 py-1 rounded bg-token-error/20 border border-token-error/40
                         text-token-error hover:bg-token-error/30 transition-colors"
            >
              Retry
            </button>
          </div>
        )

      case 'status':
        return (
          <div ref={ref} className="text-center text-xs text-token-text-secondary/60 py-1">
            {message.text}
          </div>
        )

      default:
        return null
    }
  },
)

MessageBubble.displayName = 'MessageBubble'

/** Expandable subagent card — extracted to its own component for hooks compliance. */
const SubagentBubble = forwardRef<HTMLDivElement, { message: ChatMessage; highlightClass: string }>(
  ({ message, highlightClass }, ref) => {
    const [expanded, setExpanded] = useState(false)
    const outputRef = useRef<HTMLPreElement>(null)

    // Auto-scroll streaming output while running
    useEffect(() => {
      if (message.status === 'running' && expanded && outputRef.current) {
        outputRef.current.scrollTop = outputRef.current.scrollHeight
      }
    }, [message.meta?.output, message.status, expanded])

    return (
      <div
        ref={ref}
        className={`rounded-lg border text-xs cursor-pointer transition-colors ${highlightClass} ${
          message.status === 'running' ? 'border-token-accent/40 bg-token-primary/10 hover:border-token-accent/60' :
          message.status === 'success' ? 'border-token-success/40 bg-token-success/10 hover:border-token-success/60' :
          'border-token-error/40 bg-token-error/10 hover:border-token-error/60'
        }`}
        onClick={() => setExpanded((s) => !s)}
      >
        <div className="flex items-center gap-2 text-token-text-secondary px-3 py-2">
          <StatusDot status={message.status} />
          <span className="font-medium">{message.meta?.agentType}</span>
          <span className="text-token-text truncate flex-1">{message.text}</span>
          <span className="text-token-text-secondary/50 ml-auto">{expanded ? '\u25BE' : '\u25B8'}</span>
        </div>
        {expanded && (
          <div className="px-3 py-2 border-t border-token-border space-y-2" onClick={(e) => e.stopPropagation()}>
            {message.meta?.prompt && (
              <div>
                <span className="text-token-text-secondary/60 text-[10px] uppercase tracking-wide">Prompt</span>
                <pre className="mt-0.5 text-token-text whitespace-pre-wrap font-mono text-[11px] max-h-40 overflow-y-auto bg-token-bg/50 rounded p-1.5">
                  {message.meta.prompt}
                </pre>
              </div>
            )}
            <div>
              <span className="text-token-text-secondary/60 text-[10px] uppercase tracking-wide">
                {message.status === 'running' ? 'Reply (streaming)' : 'Reply'}
              </span>
              <pre
                ref={outputRef}
                className="mt-0.5 text-token-text-secondary whitespace-pre-wrap font-mono text-[11px] max-h-60 overflow-y-auto bg-token-bg/50 rounded p-1.5"
              >
                {message.meta?.output || (message.status === 'running' ? '(waiting...)' : '(no output)')}
              </pre>
            </div>
          </div>
        )}
      </div>
    )
  },
)

SubagentBubble.displayName = 'SubagentBubble'

function StatusDot({ status }: { status?: string }) {
  if (status === 'running') return <span className="inline-block w-1.5 h-1.5 rounded-full bg-token-accent animate-pulse" />
  if (status === 'success') return <span className="text-token-success">&#10003;</span>
  if (status === 'error') return <span className="text-token-error">&#10007;</span>
  return <span className="inline-block w-1.5 h-1.5 rounded-full bg-token-text-secondary/40" />
}
