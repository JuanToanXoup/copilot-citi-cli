import { useState, useCallback, useEffect, useRef } from 'react'
import { ReactFlowProvider, type Node } from '@xyflow/react'
import { AgentFlow } from './flow/AgentFlow'
import { ChatPanel } from './components/ChatPanel'
import { ChatInput } from './components/ChatInput'
import { FileTree } from './components/FileTree'
import { StatusBar } from './components/StatusBar'
import { useFlowStore } from './stores/flow-store'
import { useAgentStore } from './stores/agent-store'
import { startDemo } from './demo'

type ViewMode = 'split' | 'chat' | 'graph'

export function App() {
  const [viewMode, setViewMode] = useState<ViewMode>('split')
  const [sidebarOpen, setSidebarOpen] = useState(true)
  const [selectedNode, setSelectedNode] = useState<Node | null>(null)
  const [dividerPosition, setDividerPosition] = useState(50) // percentage
  const isProcessing = useAgentStore((s) => s.isProcessing)
  const dividerRef = useRef<HTMLDivElement>(null)

  // Cycle view modes: Split → Chat → Graph → Split
  const cycleViewMode = useCallback(() => {
    setViewMode((m) => (m === 'split' ? 'chat' : m === 'chat' ? 'graph' : 'split'))
  }, [])

  // Keyboard shortcuts
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const meta = e.metaKey || e.ctrlKey

      if (meta && e.key === '\\') {
        e.preventDefault()
        cycleViewMode()
      } else if (meta && e.key === '/') {
        e.preventDefault()
        setSidebarOpen((s) => !s)
      } else if (e.key === 'Escape') {
        setSelectedNode(null)
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [cycleViewMode])

  // Resizable divider drag
  const handleDividerMouseDown = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault()
      const startX = e.clientX
      const startPos = dividerPosition

      const onMouseMove = (moveEvent: MouseEvent) => {
        const container = dividerRef.current?.parentElement
        if (!container) return
        const rect = container.getBoundingClientRect()
        const delta = ((moveEvent.clientX - startX) / rect.width) * 100
        const newPos = Math.min(80, Math.max(20, startPos + delta))
        setDividerPosition(newPos)
      }

      const onMouseUp = () => {
        document.removeEventListener('mousemove', onMouseMove)
        document.removeEventListener('mouseup', onMouseUp)
      }

      document.addEventListener('mousemove', onMouseMove)
      document.addEventListener('mouseup', onMouseUp)
    },
    [dividerPosition],
  )

  const handleSend = useCallback((text: string) => {
    // Slash commands
    if (text === '/new' || text === '/clear') {
      useFlowStore.getState().onReset()
      useAgentStore.getState().reset()
      setSelectedNode(null)
      return
    }

    if (text.toLowerCase() === 'demo') {
      startDemo()
      return
    }

    useFlowStore.getState().onNewTurn(text)
    useAgentStore.getState().addUserMessage(text)
    setSelectedNode(null)
    // In full app: window.api.agent.sendMessage(text)
  }, [])

  const handleCancel = useCallback(() => {
    // In full app: window.api.agent.cancel()
    useAgentStore.getState().setProcessing(false)
  }, [])

  const handleNewConversation = useCallback(() => {
    useFlowStore.getState().onReset()
    useAgentStore.getState().reset()
    setSelectedNode(null)
  }, [])

  const showChat = viewMode === 'split' || viewMode === 'chat'
  const showGraph = viewMode === 'split' || viewMode === 'graph'

  return (
    <div className="flex flex-col h-screen bg-gray-950 text-gray-100">
      {/* Toolbar */}
      <div className="flex items-center justify-between px-4 py-2 bg-gray-900 border-b border-gray-800 shrink-0">
        <div className="flex items-center gap-3">
          <button
            onClick={() => setSidebarOpen((s) => !s)}
            className="text-xs px-2 py-1 text-gray-400 hover:text-white transition-colors"
            title="Toggle file tree (Cmd+/)"
          >
            {sidebarOpen ? '◀' : '▶'}
          </button>
          <span className="text-sm font-medium text-gray-300">Copilot Desktop</span>
        </div>
        <div className="flex items-center gap-2">
          {/* View mode toggle */}
          <div className="flex items-center bg-gray-800 rounded-lg border border-gray-700 p-0.5">
            <ViewModeButton label="Chat" active={viewMode === 'chat'} onClick={() => setViewMode('chat')} />
            <ViewModeButton label="Split" active={viewMode === 'split'} onClick={() => setViewMode('split')} />
            <ViewModeButton label="Graph" active={viewMode === 'graph'} onClick={() => setViewMode('graph')} />
          </div>
          <button
            onClick={handleNewConversation}
            className="text-xs px-3 py-1.5 bg-gray-800 border border-gray-700 rounded
                       text-gray-400 hover:text-white hover:border-gray-600 transition-colors"
            title="New conversation (Cmd+N)"
          >
            + New
          </button>
        </div>
      </div>

      {/* Main content area */}
      <div className="flex flex-1 overflow-hidden">
        {/* File tree sidebar */}
        {sidebarOpen && (
          <div className="w-56 shrink-0 border-r border-gray-800 bg-gray-900 overflow-y-auto">
            <FileTree />
          </div>
        )}

        {/* Chat + Graph panes */}
        <div className="flex flex-1 overflow-hidden relative">
          {/* Chat panel */}
          {showChat && (
            <div
              className="overflow-hidden flex flex-col"
              style={{ width: viewMode === 'split' ? `${dividerPosition}%` : '100%' }}
            >
              <ChatPanel
                selectedNodeId={selectedNode?.id ?? null}
                onNodeSelect={setSelectedNode}
              />
            </div>
          )}

          {/* Resizable divider */}
          {viewMode === 'split' && (
            <div
              ref={dividerRef}
              onMouseDown={handleDividerMouseDown}
              className="w-1 shrink-0 bg-gray-800 hover:bg-blue-500 cursor-col-resize transition-colors"
            />
          )}

          {/* Graph panel */}
          {showGraph && (
            <div
              className="overflow-hidden"
              style={{ width: viewMode === 'split' ? `${100 - dividerPosition}%` : '100%' }}
            >
              <ReactFlowProvider>
                <AgentFlow selectedNode={selectedNode} onNodeSelect={setSelectedNode} />
              </ReactFlowProvider>
            </div>
          )}
        </div>
      </div>

      {/* Chat input — always visible */}
      <ChatInput
        onSend={handleSend}
        onCancel={handleCancel}
        disabled={isProcessing}
        isProcessing={isProcessing}
        placeholder="Send a message, type 'demo', or use /commands..."
      />

      {/* Status bar */}
      <StatusBar connected={false} viewMode={viewMode} />
    </div>
  )
}

function ViewModeButton({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className={`px-3 py-1 text-xs font-medium rounded transition-colors ${
        active ? 'bg-gray-700 text-white' : 'text-gray-500 hover:text-gray-300'
      }`}
    >
      {label}
    </button>
  )
}
