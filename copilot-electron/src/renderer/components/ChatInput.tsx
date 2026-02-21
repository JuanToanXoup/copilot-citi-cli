import { useState, useCallback, useRef, type KeyboardEvent } from 'react'

const SLASH_COMMANDS = [
  { command: '/new', description: 'New conversation' },
  { command: '/clear', description: 'Clear current conversation' },
  { command: '/settings', description: 'Open settings' },
  { command: '/tools', description: 'Open tool popover' },
] as const

interface ChatInputProps {
  onSend: (text: string) => void
  onCancel: () => void
  disabled?: boolean
  isProcessing?: boolean
  placeholder?: string
}

export function ChatInput({
  onSend,
  onCancel,
  disabled = false,
  isProcessing = false,
  placeholder = 'Send a message...',
}: ChatInputProps) {
  const [text, setText] = useState('')
  const [showSlashMenu, setShowSlashMenu] = useState(false)
  const [slashIndex, setSlashIndex] = useState(0)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  const filteredCommands = text.startsWith('/')
    ? SLASH_COMMANDS.filter((c) => c.command.startsWith(text.toLowerCase()))
    : []

  const handleSubmit = useCallback(() => {
    const trimmed = text.trim()
    if (!trimmed || disabled) return
    setShowSlashMenu(false)
    onSend(trimmed)
    setText('')
  }, [text, disabled, onSend])

  const selectCommand = useCallback(
    (cmd: string) => {
      setShowSlashMenu(false)
      onSend(cmd)
      setText('')
      textareaRef.current?.focus()
    },
    [onSend],
  )

  const handleChange = useCallback((value: string) => {
    setText(value)
    if (value.startsWith('/') && value.length >= 1) {
      setShowSlashMenu(true)
      setSlashIndex(0)
    } else {
      setShowSlashMenu(false)
    }
  }, [])

  const handleKeyDown = useCallback(
    (e: KeyboardEvent<HTMLTextAreaElement>) => {
      // Slash menu navigation
      if (showSlashMenu && filteredCommands.length > 0) {
        if (e.key === 'ArrowDown') {
          e.preventDefault()
          setSlashIndex((i) => (i + 1) % filteredCommands.length)
          return
        }
        if (e.key === 'ArrowUp') {
          e.preventDefault()
          setSlashIndex((i) => (i - 1 + filteredCommands.length) % filteredCommands.length)
          return
        }
        if (e.key === 'Tab' || (e.key === 'Enter' && !e.metaKey && !e.ctrlKey)) {
          e.preventDefault()
          selectCommand(filteredCommands[slashIndex].command)
          return
        }
        if (e.key === 'Escape') {
          e.preventDefault()
          setShowSlashMenu(false)
          return
        }
      }

      // Enter or Cmd+Enter to send
      if (e.key === 'Enter' && (!e.shiftKey || e.metaKey || e.ctrlKey)) {
        e.preventDefault()
        handleSubmit()
      }
    },
    [handleSubmit, showSlashMenu, filteredCommands, slashIndex, selectCommand],
  )

  return (
    <div className="relative border-t border-gray-800 bg-gray-900 shrink-0">
      {/* Slash command autocomplete */}
      {showSlashMenu && filteredCommands.length > 0 && (
        <div className="absolute bottom-full left-4 right-4 mb-1 bg-gray-800 border border-gray-700 rounded-lg overflow-hidden shadow-xl">
          {filteredCommands.map((cmd, i) => (
            <button
              key={cmd.command}
              onClick={() => selectCommand(cmd.command)}
              className={`w-full flex items-center gap-3 px-3 py-2 text-left text-sm transition-colors ${
                i === slashIndex ? 'bg-gray-700 text-white' : 'text-gray-300 hover:bg-gray-700/50'
              }`}
            >
              <span className="font-mono text-blue-400">{cmd.command}</span>
              <span className="text-gray-500">{cmd.description}</span>
            </button>
          ))}
        </div>
      )}

      <div className="flex items-end gap-2 p-4">
        <textarea
          ref={textareaRef}
          value={text}
          onChange={(e) => handleChange(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={placeholder}
          disabled={disabled}
          rows={1}
          className="flex-1 resize-none bg-gray-800 text-gray-100 rounded-lg px-4 py-2.5 text-sm
                     placeholder-gray-500 border border-gray-700 focus:border-blue-500 focus:outline-none
                     disabled:opacity-50"
        />
        {isProcessing ? (
          <button
            onClick={onCancel}
            className="px-4 py-2.5 text-sm font-medium bg-red-600 text-white rounded-lg
                       hover:bg-red-500 transition-colors"
          >
            Stop
          </button>
        ) : (
          <button
            onClick={handleSubmit}
            disabled={!text.trim()}
            className="px-4 py-2.5 text-sm font-medium bg-blue-600 text-white rounded-lg
                       hover:bg-blue-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            Send
          </button>
        )}
      </div>
    </div>
  )
}
