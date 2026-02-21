import { useState, useCallback } from 'react'
import { AgentView } from './views/AgentView'
import { ChatView } from './views/ChatView'

type Tab = 'agent' | 'chat'

export function App() {
  const [activeTab, setActiveTab] = useState<Tab>('agent')

  return (
    <div className="flex flex-col h-screen">
      {/* Tab bar */}
      <div className="flex items-center gap-1 px-4 pt-2 pb-0 bg-gray-900 border-b border-gray-800">
        <TabButton
          label="Agent"
          active={activeTab === 'agent'}
          onClick={() => setActiveTab('agent')}
        />
        <TabButton
          label="Chat"
          active={activeTab === 'chat'}
          onClick={() => setActiveTab('chat')}
        />
      </div>

      {/* Tab content */}
      <div className="flex-1 overflow-hidden">
        {activeTab === 'agent' ? <AgentView /> : <ChatView />}
      </div>
    </div>
  )
}

function TabButton({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className={`px-4 py-2 text-sm font-medium rounded-t-lg transition-colors ${
        active
          ? 'bg-gray-950 text-white border border-gray-800 border-b-gray-950'
          : 'text-gray-400 hover:text-gray-200'
      }`}
    >
      {label}
    </button>
  )
}
