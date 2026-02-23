import { useState, useEffect } from 'react'

export function CommandPalette({ onClose, conversations, onLoadConversations, onSelectConversation }: {
  onClose: () => void
  conversations: Array<{ id: string; date: string; summary: string }>
  onLoadConversations: () => void
  onSelectConversation: (id: string) => void
}) {
  const [query, setQuery] = useState('')

  useEffect(() => {
    onLoadConversations()
  }, [])

  const filtered = conversations.filter(c =>
    !query || c.summary.toLowerCase().includes(query.toLowerCase())
  )

  // Group by date
  const grouped = filtered.reduce<Record<string, typeof filtered>>((acc, c) => {
    const key = c.date
    if (!acc[key]) acc[key] = []
    acc[key].push(c)
    return acc
  }, {})

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center pt-[20vh]" onClick={onClose}>
      <div
        className="w-[480px] bg-gray-900 border border-gray-700 rounded-xl shadow-2xl overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        <input
          autoFocus
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search conversations..."
          className="w-full px-4 py-3 bg-transparent text-sm text-gray-100 placeholder-gray-500
                     border-b border-gray-800 outline-none"
          onKeyDown={(e) => e.key === 'Escape' && onClose()}
        />
        <div className="max-h-64 overflow-y-auto p-2">
          {Object.keys(grouped).length === 0 ? (
            <>
              <div className="text-xs text-gray-500 px-2 py-1.5 uppercase tracking-wide">
                Conversations
              </div>
              <div className="text-sm text-gray-400 px-2 py-6 text-center">
                No conversations yet
              </div>
            </>
          ) : (
            Object.entries(grouped).map(([date, convs]) => (
              <div key={date}>
                <div className="text-xs text-gray-500 px-2 py-1.5 uppercase tracking-wide">
                  {date}
                </div>
                {convs.map((c) => (
                  <button
                    key={c.id}
                    onClick={() => onSelectConversation(c.id)}
                    className="w-full text-left px-2 py-1.5 text-sm text-gray-300 rounded
                               hover:bg-gray-800 transition-colors truncate"
                  >
                    {c.summary}
                  </button>
                ))}
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  )
}
