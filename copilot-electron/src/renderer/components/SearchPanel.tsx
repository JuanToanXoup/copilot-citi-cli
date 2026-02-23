import { useState } from 'react'

export function SearchPanel() {
  const [query, setQuery] = useState('')
  return (
    <div className="p-3 h-full flex flex-col">
      <div className="text-[11px] font-semibold text-gray-500 uppercase tracking-wide mb-2">
        Search
      </div>
      <input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="Search files..."
        className="w-full px-2 py-1.5 text-xs bg-gray-800 border border-gray-700 rounded
                   text-gray-100 placeholder-gray-600 focus:border-blue-500 outline-none mb-2"
      />
      <div className="flex-1 flex items-center justify-center">
        <p className="text-xs text-gray-600 text-center px-4">
          {query ? `Search results for "${query}" coming soon` : 'Type to search across files'}
        </p>
      </div>
    </div>
  )
}
