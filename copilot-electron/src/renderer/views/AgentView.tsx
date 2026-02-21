import { useState, useCallback } from 'react'
import { ReactFlowProvider, type Node } from '@xyflow/react'
import { AgentFlow } from '../flow/AgentFlow'
import { ChatInput } from '../components/ChatInput'
import { useFlowStore } from '../stores/flow-store'
import { useAgentStore } from '../stores/agent-store'
import { startDemo } from '../demo'

export function AgentView() {
  const [selectedNode, setSelectedNode] = useState<Node | null>(null)
  const isProcessing = useAgentStore((s) => s.isProcessing)

  const handleSend = useCallback((text: string) => {
    if (text.toLowerCase() === 'demo') {
      startDemo()
    } else {
      useFlowStore.getState().onNewTurn(text)
      useAgentStore.getState().setUserPrompt(text)
      setSelectedNode(null)
      // In full app, this would call: window.api.agent.sendMessage(text)
    }
  }, [])

  const handleNewConversation = useCallback(() => {
    useFlowStore.getState().onReset()
    useAgentStore.getState().reset()
    setSelectedNode(null)
  }, [])

  return (
    <div className="flex flex-col h-full">
      {/* Toolbar */}
      <div className="flex items-center justify-between px-4 py-2 bg-gray-900 border-b border-gray-800">
        <span className="text-sm font-medium text-gray-300">Agent Flow</span>
        <button
          onClick={handleNewConversation}
          className="text-xs px-3 py-1.5 bg-gray-800 border border-gray-700 rounded
                     text-gray-400 hover:text-white hover:border-gray-600 transition-colors"
          title="New conversation"
        >
          + New
        </button>
      </div>

      {/* Flow canvas */}
      <div className="flex-1 overflow-hidden">
        <ReactFlowProvider>
          <AgentFlow selectedNode={selectedNode} onNodeSelect={setSelectedNode} />
        </ReactFlowProvider>
      </div>

      {/* Chat input */}
      <ChatInput
        onSend={handleSend}
        disabled={isProcessing}
        placeholder="Send a message or type 'demo'..."
      />
    </div>
  )
}
