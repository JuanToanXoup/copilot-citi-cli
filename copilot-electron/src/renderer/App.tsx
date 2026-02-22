import { useState, useCallback, useEffect, useRef } from 'react'
import { ReactFlowProvider, type Node } from '@xyflow/react'
import { AgentFlow } from './flow/AgentFlow'
import { ChatPanel } from './components/ChatPanel'
import { ChatInput } from './components/ChatInput'
import { FileTree } from './components/FileTree'
import { StatusBar } from './components/StatusBar'
import { ChangesPanel } from './components/ChangesPanel'
import { ProjectPicker, addRecentProject } from './components/ProjectPicker'
import { ActivityBar, type SidePanel } from './components/ActivityBar'
import { FileEditor, type OpenFile, prefetchFile } from './components/FileEditor'
import { AuthDialog } from './components/AuthDialog'
import { useFlowStore } from './stores/flow-store'
import { useAgentStore, type ChatMessage } from './stores/agent-store'
import { useSettingsStore, type ThemeTokens, type ToolDisplayMode, type ColorMode, type ToolDef } from './stores/settings-store'
import { startDemo } from './demo'
import { create, type StoreApi, type UseBoundStore } from 'zustand'

type ViewMode = 'split' | 'chat' | 'graph'

/* ------------------------------------------------------------------ */
/*  Multi-conversation tab types (Phase 6, Task 2)                     */
/* ------------------------------------------------------------------ */

interface ConversationTab {
  id: string
  label: string
  /** Each tab gets its own zustand stores for isolation */
  agentStoreRef: string // identifier, stores are global singletons for now
}

export function App() {
  const projectPath = useSettingsStore((s) => s.projectPath)

  // Hydrate settings from disk on first load
  useEffect(() => {
    useSettingsStore.getState().hydrateFromDisk()
  }, [])

  // Show project picker if no project is loaded
  if (!projectPath) {
    return <ProjectPicker onSelect={(path) => { addRecentProject(path); useSettingsStore.getState().setProjectPath(path) }} />
  }

  return <MainApp />
}

function MainApp() {
  const [viewMode, setViewMode] = useState<ViewMode>('split')
  const [activePanel, setActivePanel] = useState<SidePanel | null>('explorer')
  const [selectedNode, setSelectedNode] = useState<Node | null>(null)
  const [dividerPosition, setDividerPosition] = useState(50)
  const [sidebarWidth, setSidebarWidth] = useState(260)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [paletteOpen, setPaletteOpen] = useState(false)
  const [changesOpen, setChangesOpen] = useState(false)
  const [openFiles, setOpenFiles] = useState<OpenFile[]>([])
  const [activeFile, setActiveFile] = useState<string | null>(null)
  const [editorWidth, setEditorWidth] = useState(500)
  const [lspConnected, setLspConnected] = useState(false)
  const [authDialogOpen, setAuthDialogOpen] = useState(false)
  const [toolConfirmReq, setToolConfirmReq] = useState<{ id: string; name: string; input: Record<string, unknown> } | null>(null)
  const [gitBranch, setGitBranch] = useState('')
  const [gitChangesCount, setGitChangesCount] = useState(0)
  const [gitStatus, setGitStatus] = useState<Map<string, string>>(new Map())
  const [conversations, setConversations] = useState<Array<{ id: string; date: string; summary: string; path: string }>>([])
  const [fileTreeRefreshKey, setFileTreeRefreshKey] = useState(0)
  const isProcessing = useAgentStore((s) => s.isProcessing)
  const changedFiles = useAgentStore((s) => s.changedFiles)
  const dividerRef = useRef<HTMLDivElement>(null)
  const sidebarDividerRef = useRef<HTMLDivElement>(null)
  const editorDividerRef = useRef<HTMLDivElement>(null)

  // Phase 6, Task 2: Multi-conversation tabs
  const [tabs, setTabs] = useState<ConversationTab[]>([
    { id: 'default', label: 'Chat 1', agentStoreRef: 'default' },
  ])
  const [activeTabId, setActiveTabId] = useState('default')

  const projectPath = useSettingsStore((s) => s.projectPath)

  // Phase 6, Task 1: Update window title on projectPath change
  useEffect(() => {
    if (!projectPath) return
    const folderName = projectPath.split('/').pop() || projectPath
    const title = `${folderName} - Copilot Desktop`
    document.title = title
    window.api?.window?.setTitle(title)
  }, [projectPath])

  const handlePanelToggle = useCallback((panel: SidePanel) => {
    setActivePanel((prev) => (prev === panel ? null : panel))
  }, [])

  const handleFileSelect = useCallback((path: string, name: string) => {
    prefetchFile(path) // start loading immediately, before React re-renders
    setOpenFiles((prev) => {
      if (prev.some((f) => f.path === path)) return prev
      return [...prev, { path, name }]
    })
    setActiveFile(path)
  }, [])

  const handleCloseFile = useCallback((path: string) => {
    setOpenFiles((prev) => {
      const next = prev.filter((f) => f.path !== path)
      // If closing the active file, switch to the last remaining tab or null
      setActiveFile((current) =>
        current === path ? (next.length > 0 ? next[next.length - 1].path : null) : current,
      )
      return next
    })
  }, [])

  // Cycle view modes: Split → Chat → Graph → Split
  const cycleViewMode = useCallback(() => {
    setViewMode((m) => (m === 'split' ? 'chat' : m === 'chat' ? 'graph' : 'split'))
  }, [])

  const handleNewConversation = useCallback(() => {
    window.api.agent.newConversation()
    useFlowStore.getState().onReset()
    useAgentStore.getState().reset()
    setSelectedNode(null)
  }, [])

  // Phase 6, Task 2: Create new tab
  const handleNewTab = useCallback(() => {
    const id = `tab-${Date.now()}`
    const label = `Chat ${tabs.length + 1}`
    setTabs((prev) => [...prev, { id, label, agentStoreRef: id }])
    setActiveTabId(id)
    // Reset stores for the new tab context
    handleNewConversation()
  }, [tabs.length, handleNewConversation])

  const handleCloseTab = useCallback((tabId: string) => {
    setTabs((prev) => {
      const next = prev.filter((t) => t.id !== tabId)
      if (next.length === 0) {
        // Always keep at least one tab
        return [{ id: 'default', label: 'Chat 1', agentStoreRef: 'default' }]
      }
      if (activeTabId === tabId) {
        setActiveTabId(next[next.length - 1].id)
      }
      return next
    })
  }, [activeTabId])

  // Keyboard shortcuts
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const meta = e.metaKey || e.ctrlKey

      if (meta && e.key === '\\') {
        e.preventDefault()
        cycleViewMode()
      } else if (meta && e.key === '/') {
        e.preventDefault()
        setActivePanel((s) => (s ? null : 'explorer'))
      } else if (meta && e.key === 'n' && !e.shiftKey) {
        e.preventDefault()
        handleNewTab()
      } else if (meta && e.shiftKey && e.key === 'p') {
        e.preventDefault()
        setSettingsOpen((s) => !s)
      } else if (meta && e.key === 'k') {
        e.preventDefault()
        setPaletteOpen((s) => !s)
      } else if (meta && e.key === 'w') {
        e.preventDefault()
        // Close conversation tab if more than one, otherwise close active file
        if (tabs.length > 1) {
          handleCloseTab(activeTabId)
        } else if (activeFile) {
          handleCloseFile(activeFile)
        }
      } else if (meta && e.key === 't') {
        e.preventDefault()
        handleNewTab()
      } else if (e.key === 'Escape') {
        setSettingsOpen(false)
        setPaletteOpen(false)
        setChangesOpen(false)
        setSelectedNode(null)
        setToolConfirmReq(null)
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [cycleViewMode, handleNewConversation, activeFile, handleCloseFile, handleNewTab])

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

  // Resizable sidebar drag
  const handleSidebarDividerMouseDown = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault()
      const startX = e.clientX
      const startWidth = sidebarWidth

      const onMouseMove = (moveEvent: MouseEvent) => {
        const delta = moveEvent.clientX - startX
        const newWidth = Math.min(480, Math.max(140, startWidth + delta))
        setSidebarWidth(newWidth)
      }

      const onMouseUp = () => {
        document.removeEventListener('mousemove', onMouseMove)
        document.removeEventListener('mouseup', onMouseUp)
      }

      document.addEventListener('mousemove', onMouseMove)
      document.addEventListener('mouseup', onMouseUp)
    },
    [sidebarWidth],
  )

  // Resizable editor drag
  const handleEditorDividerMouseDown = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault()
      const startX = e.clientX
      const startWidth = editorWidth

      const onMouseMove = (moveEvent: MouseEvent) => {
        const delta = moveEvent.clientX - startX
        const newWidth = Math.min(1200, Math.max(200, startWidth + delta))
        setEditorWidth(newWidth)
      }

      const onMouseUp = () => {
        document.removeEventListener('mousemove', onMouseMove)
        document.removeEventListener('mouseup', onMouseUp)
      }

      document.addEventListener('mousemove', onMouseMove)
      document.addEventListener('mouseup', onMouseUp)
    },
    [editorWidth],
  )

  // Subscribe to LSP status from main process
  useEffect(() => {
    const unsub = window.api.agent.onLspStatus((status) => {
      setLspConnected(status.connected)
    })
    return unsub
  }, [])

  // Subscribe to agent events from main process — Phase 1: Graph↔Chat sync with nodeIds
  useEffect(() => {
    const unsub = window.api.agent.onEvent((event) => {
      const flow = useFlowStore.getState()
      const agent = useAgentStore.getState()

      switch (event.type) {
        case 'lead:started':
          flow.onLeadStatus('running')
          agent.handleEvent(event)
          break
        case 'lead:delta':
          agent.handleEvent(event)
          break
        case 'lead:done':
          flow.onLeadStatus('done')
          agent.handleEvent(event)
          break
        case 'lead:error':
          flow.onLeadStatus('error')
          agent.handleEvent(event)
          // Show auth dialog if auth error
          if (event.message?.includes('Authentication') || event.message?.includes('auth')) {
            setAuthDialogOpen(true)
          }
          break
        case 'lead:toolcall': {
          // Phase 1: Capture nodeId from flow store and pass to agent store
          if (event.name === 'run_in_terminal') {
            const nodeId = flow.onTerminalCommand(event.input?.command as string ?? event.name)
            agent.addTerminalMessage(event.input?.command as string ?? event.name, nodeId)
          } else {
            const nodeId = flow.onLeadToolCall(event.name)
            agent.addToolMessage(event.name, nodeId, event.input)
          }
          break
        }
        case 'lead:toolresult': {
          if (event.name === 'run_in_terminal') {
            // Find the running terminal message to get its nodeId + command
            const termMsg = agent.messages.findLast(
              (m) => m.type === 'terminal' && m.status === 'running',
            )
            if (termMsg?.nodeId) {
              const cmd = termMsg.meta?.command ?? ''
              flow.onTerminalResult(cmd, event.status)
              agent.updateTerminalStatus(termMsg.nodeId, event.status)
            }
          } else {
            const nodeId = flow.onLeadToolResult(event.name, event.status)
            agent.updateToolStatus(event.name, event.status, nodeId, event.output)
          }
          break
        }
        case 'subagent:spawned':
          flow.onSubagentSpawned(event.agentId, event.agentType, event.description)
          agent.handleEvent(event)
          break
        case 'subagent:delta':
          flow.onSubagentDelta(event.agentId, event.text)
          agent.handleEvent(event)
          break
        case 'subagent:completed':
          flow.onSubagentCompleted(event.agentId, event.status)
          agent.handleEvent(event)
          break
        case 'status':
          agent.handleEvent(event)
          break
        case 'file:changed':
          agent.handleEvent(event)
          // Refresh file tree
          setFileTreeRefreshKey(k => k + 1)
          // Reload file if open in editor
          if (openFiles.some(f => f.path === event.filePath)) {
            // Trigger re-read by forcing a state update
            setOpenFiles(prev => [...prev])
          }
          break
        default:
          agent.handleEvent(event)
      }
    })
    return unsub
  }, [openFiles])

  // Phase 3: Subscribe to file changed events from main process
  useEffect(() => {
    if (!window.api?.agent?.onFileChanged) return
    const unsub = window.api.agent.onFileChanged((data) => {
      useAgentStore.getState().addFileChange(data.filePath, data.action as 'created' | 'modified' | 'deleted')
      setFileTreeRefreshKey(k => k + 1)
    })
    return unsub
  }, [])

  // Phase 7: Subscribe to tool confirm requests
  useEffect(() => {
    if (!window.api?.tools?.onConfirmRequest) return
    const unsub = window.api.tools.onConfirmRequest((req) => {
      setToolConfirmReq(req)
    })
    return unsub
  }, [])

  // Phase 9: Poll git status
  useEffect(() => {
    if (!window.api?.git) return

    const poll = async () => {
      try {
        const [branch, status] = await Promise.all([
          window.api.git.branch(),
          window.api.git.status(),
        ])
        setGitBranch(branch)
        setGitChangesCount(status.length)
        const statusMap = new Map<string, string>()
        for (const s of status) {
          statusMap.set(s.file, s.status)
        }
        setGitStatus(statusMap)
      } catch {
        // Git not available
      }
    }

    poll()
    const interval = setInterval(poll, 5000)
    return () => clearInterval(interval)
  }, [])

  const handleSend = useCallback((text: string) => {
    // Slash commands
    if (text === '/new' || text === '/clear') {
      useFlowStore.getState().onReset()
      useAgentStore.getState().reset()
      setSelectedNode(null)
      return
    }
    if (text === '/settings') {
      setSettingsOpen(true)
      return
    }
    if (text === '/tools') {
      // Handled by ChatInput tool popover toggle
      return
    }
    if (text === '/changes') {
      setActivePanel('vcs')
      return
    }

    // Phase 9: Git slash commands
    if (text.startsWith('/commit')) {
      const msg = text.slice(7).trim().replace(/^["']|["']$/g, '') || 'Auto-commit'
      window.api?.git?.commit(msg).then((result) => {
        useAgentStore.getState().addStatusMessage(result)
      })
      return
    }
    if (text === '/push') {
      window.api?.git?.push().then((result) => {
        useAgentStore.getState().addStatusMessage(result || 'Pushed successfully')
      })
      return
    }
    if (text === '/pull') {
      window.api?.git?.pull().then((result) => {
        useAgentStore.getState().addStatusMessage(result || 'Pulled successfully')
      })
      return
    }
    if (text.startsWith('/branch')) {
      const name = text.slice(7).trim()
      if (name) {
        window.api?.git?.checkout(name, true).then((result) => {
          useAgentStore.getState().addStatusMessage(result || `Switched to branch ${name}`)
        })
      } else {
        window.api?.git?.branches().then((branches) => {
          useAgentStore.getState().addStatusMessage(`Branches: ${branches.join(', ')}`)
        })
      }
      return
    }

    if (text.toLowerCase() === 'demo') {
      startDemo()
      return
    }

    useFlowStore.getState().onNewTurn(text)
    useAgentStore.getState().addUserMessage(text)
    setSelectedNode(null)
    window.api.agent.sendMessage(text)
  }, [])

  const handleCancel = useCallback(() => {
    window.api.agent.cancel()
    useAgentStore.getState().setProcessing(false)
  }, [])

  // Phase 7: Tool confirmation handlers
  const handleToolConfirmApprove = useCallback(() => {
    if (toolConfirmReq) {
      window.api?.tools?.respondConfirm(toolConfirmReq.id, true)
      setToolConfirmReq(null)
    }
  }, [toolConfirmReq])

  const handleToolConfirmDeny = useCallback(() => {
    if (toolConfirmReq) {
      window.api?.tools?.respondConfirm(toolConfirmReq.id, false)
      setToolConfirmReq(null)
    }
  }, [toolConfirmReq])

  const showChat = viewMode === 'split' || viewMode === 'chat'
  const showGraph = viewMode === 'split' || viewMode === 'graph'

  const sidebarOpen = activePanel !== null

  return (
    <div className="flex flex-col h-screen bg-gray-950 text-gray-100">
      {/* Toolbar */}
      <div className="flex items-center justify-end gap-3 px-4 py-2 bg-gray-900 border-b border-gray-800 shrink-0">
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
        <span className="text-sm font-medium text-gray-300">Copilot Desktop</span>
      </div>

      {/* Phase 6, Task 2: Conversation tab bar */}
      {tabs.length > 1 && (
        <div className="flex items-center bg-gray-900 border-b border-gray-800 px-1 shrink-0">
          {tabs.map((tab) => (
            <div
              key={tab.id}
              className={`flex items-center gap-1 px-3 py-1.5 text-xs cursor-pointer border-b-2 transition-colors ${
                activeTabId === tab.id
                  ? 'border-blue-500 text-white'
                  : 'border-transparent text-gray-500 hover:text-gray-300'
              }`}
              onClick={() => setActiveTabId(tab.id)}
            >
              <span>{tab.label}</span>
              {tabs.length > 1 && (
                <button
                  onClick={(e) => { e.stopPropagation(); handleCloseTab(tab.id) }}
                  className="ml-1 text-gray-600 hover:text-white text-[10px]"
                >
                  x
                </button>
              )}
            </div>
          ))}
          <button
            onClick={handleNewTab}
            className="px-2 py-1.5 text-xs text-gray-600 hover:text-white transition-colors"
            title="New tab (Cmd+T)"
          >
            +
          </button>
        </div>
      )}

      {/* Main content area */}
      <div className="flex flex-1 overflow-hidden">
        {/* Activity bar */}
        <ActivityBar
          activePanel={activePanel}
          onPanelToggle={handlePanelToggle}
          onSettingsOpen={() => setSettingsOpen(true)}
        />

        {/* Side panel (explorer / search / vcs) */}
        {sidebarOpen && (
          <>
            <div
              className="shrink-0 bg-gray-900 overflow-y-auto"
              style={{ width: `${sidebarWidth}px` }}
            >
              {activePanel === 'explorer' && (
                <FileTree onFileSelect={handleFileSelect} gitStatus={gitStatus} refreshKey={fileTreeRefreshKey} />
              )}
              {activePanel === 'search' && (
                <SearchPanel />
              )}
              {activePanel === 'vcs' && (
                <VcsPanel changedFiles={changedFiles} gitStatus={gitStatus} />
              )}
            </div>
            <div
              ref={sidebarDividerRef}
              onMouseDown={handleSidebarDividerMouseDown}
              className="w-1 shrink-0 bg-gray-800 hover:bg-blue-500 cursor-col-resize transition-colors"
            />
          </>
        )}

        {/* Editor area */}
        <div className="shrink-0 overflow-hidden" style={{ width: `${editorWidth}px` }}>
          <FileEditor
            openFiles={openFiles}
            activeFile={activeFile}
            onSelectFile={setActiveFile}
            onCloseFile={handleCloseFile}
          />
        </div>
        <div
          ref={editorDividerRef}
          onMouseDown={handleEditorDividerMouseDown}
          className="w-1 shrink-0 bg-gray-800 hover:bg-blue-500 cursor-col-resize transition-colors"
        />

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
      <StatusBar
        connected={lspConnected}
        viewMode={viewMode}
        branch={gitBranch || undefined}
        changesCount={gitChangesCount || undefined}
      />

      {/* Command Palette (Cmd+K) */}
      {paletteOpen && (
        <CommandPalette
          onClose={() => setPaletteOpen(false)}
          conversations={conversations}
          onLoadConversations={() => {
            window.api?.conversations?.list().then(setConversations).catch(() => {})
          }}
          onSelectConversation={(id) => {
            const conv = conversations.find(c => c.id === id)
            window.api?.conversations?.load(id).then((data) => {
              if (data) {
                useAgentStore.getState().loadConversation(data.messages as ChatMessage[])
                useFlowStore.getState().loadFromMessages(data.messages as ChatMessage[])
                // Update tab label with conversation summary
                if (conv?.summary) {
                  setTabs((prev) => prev.map((t) =>
                    t.id === activeTabId ? { ...t, label: conv.summary.slice(0, 30) } : t
                  ))
                }
              }
            }).catch(() => {})
            setPaletteOpen(false)
          }}
        />
      )}

      {/* Settings modal (Cmd+Shift+P) */}
      {settingsOpen && (
        <SettingsModal onClose={() => setSettingsOpen(false)} />
      )}

      {/* Changes panel */}
      {changesOpen && (
        <ChangesPanel onClose={() => setChangesOpen(false)} />
      )}

      {/* Auth dialog (Phase 4) */}
      {authDialogOpen && (
        <AuthDialog onClose={() => setAuthDialogOpen(false)} />
      )}

      {/* Tool confirmation dialog (Phase 7) */}
      {toolConfirmReq && (
        <ToolConfirmDialog
          name={toolConfirmReq.name}
          input={toolConfirmReq.input}
          onApprove={handleToolConfirmApprove}
          onDeny={handleToolConfirmDeny}
        />
      )}
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

/* ------------------------------------------------------------------ */
/*  Tool Confirmation Dialog (Phase 7)                                 */
/* ------------------------------------------------------------------ */

function ToolConfirmDialog({ name, input, onApprove, onDeny }: {
  name: string
  input: Record<string, unknown>
  onApprove: () => void
  onDeny: () => void
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="w-[440px] bg-gray-900 border border-gray-700 rounded-xl shadow-2xl overflow-hidden">
        <div className="px-4 py-3 border-b border-gray-800">
          <h3 className="text-sm font-semibold text-gray-100">Tool Confirmation Required</h3>
          <p className="text-xs text-gray-400 mt-1">
            The agent wants to execute <span className="font-mono text-yellow-400">{name}</span>
          </p>
        </div>
        <div className="px-4 py-3 max-h-48 overflow-y-auto">
          <pre className="text-xs text-gray-400 font-mono whitespace-pre-wrap bg-gray-950 rounded p-2">
            {JSON.stringify(input, null, 2)}
          </pre>
        </div>
        <div className="flex items-center justify-end gap-2 px-4 py-3 border-t border-gray-800">
          <button
            onClick={onDeny}
            className="text-xs px-3 py-1.5 bg-gray-800 border border-gray-700 rounded
                       text-gray-400 hover:text-white transition-colors"
          >
            Deny
          </button>
          <button
            onClick={onApprove}
            className="text-xs px-3 py-1.5 bg-green-600 border border-green-500 rounded
                       text-white hover:bg-green-500 transition-colors"
          >
            Approve
          </button>
        </div>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  Command Palette with conversation history (Phase 6)                */
/* ------------------------------------------------------------------ */

function CommandPalette({ onClose, conversations, onLoadConversations, onSelectConversation }: {
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

/* ------------------------------------------------------------------ */
/*  Settings Modal with real section content                           */
/* ------------------------------------------------------------------ */

function SettingsModal({ onClose }: { onClose: () => void }) {
  const sections = ['Connection', 'Agents', 'Tools', 'Theme', 'Chat', 'Keybindings'] as const
  const [activeSection, setActiveSection] = useState<string>(sections[0])

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={onClose}>
      <div
        className="w-[720px] h-[520px] bg-gray-900 border border-gray-700 rounded-xl shadow-2xl flex overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Sidebar */}
        <div className="w-44 border-r border-gray-800 p-3 space-y-0.5">
          <div className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2 px-2">
            Settings
          </div>
          {sections.map((s) => (
            <button
              key={s}
              onClick={() => setActiveSection(s)}
              className={`w-full text-left px-2 py-1.5 text-sm rounded transition-colors ${
                activeSection === s
                  ? 'bg-gray-800 text-white'
                  : 'text-gray-400 hover:text-gray-200 hover:bg-gray-800/50'
              }`}
            >
              {s}
            </button>
          ))}
        </div>
        {/* Content */}
        <div className="flex-1 p-6 overflow-y-auto">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold text-gray-100">{activeSection}</h2>
            <button onClick={onClose} className="text-gray-500 hover:text-white text-sm">
              Esc
            </button>
          </div>
          <SettingsContent section={activeSection} />
        </div>
      </div>
    </div>
  )
}

function SettingsContent({ section }: { section: string }) {
  switch (section) {
    case 'Connection': return <ConnectionSettings />
    case 'Agents': return <AgentsSettings />
    case 'Tools': return <ToolsSettings />
    case 'Theme': return <ThemeSettings />
    case 'Chat': return <ChatSettings />
    case 'Keybindings': return <KeybindingsSettings />
    default:
      return <p className="text-sm text-gray-500">{section} settings will be configured here.</p>
  }
}

function AgentsSettings() {
  const [agents, setAgents] = useState<Array<{ agentType: string; whenToUse: string; model: string; source: string; toolCount: number | null; maxTurns: number }>>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!window.api?.agents?.list) { setLoading(false); return }
    window.api.agents.list().then((list) => {
      setAgents(list)
      setLoading(false)
    }).catch(() => setLoading(false))
  }, [])

  if (loading) {
    return <p className="text-sm text-gray-500">Loading agents...</p>
  }

  if (agents.length === 0) {
    return (
      <div className="space-y-3">
        <p className="text-sm text-gray-400">
          No custom agents defined. Add <code className="text-xs bg-gray-800 px-1 py-0.5 rounded font-mono">.md</code> files to <code className="text-xs bg-gray-800 px-1 py-0.5 rounded font-mono">.copilot/agents/</code> to create agents.
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-2">
      {agents.map((agent) => (
        <div key={agent.agentType} className="px-3 py-2.5 bg-gray-800 rounded-lg">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium text-gray-200">{agent.agentType}</span>
            <span className="text-[10px] px-1.5 py-0.5 bg-gray-700 rounded text-gray-400">{agent.source}</span>
          </div>
          <div className="text-xs text-gray-500 mt-1">{agent.whenToUse}</div>
          <div className="flex items-center gap-3 mt-1.5 text-[11px] text-gray-600">
            <span>Model: {agent.model}</span>
            {agent.toolCount != null && <span>{agent.toolCount} tools</span>}
            <span>Max {agent.maxTurns} turns</span>
          </div>
        </div>
      ))}
    </div>
  )
}

function ConnectionSettings() {
  const model = useSettingsStore((s) => s.model)
  const proxyUrl = useSettingsStore((s) => s.proxyUrl)
  const caCertPath = useSettingsStore((s) => s.caCertPath)
  const [signingOut, setSigningOut] = useState(false)

  const handleSignOut = async () => {
    setSigningOut(true)
    try {
      await window.api?.auth?.signOut()
    } catch { /* ignore */ }
    setSigningOut(false)
  }

  return (
    <div className="space-y-4">
      <SettingsField label="Model" value={model} onChange={(v) => useSettingsStore.getState().setModel(v)} />
      <SettingsField label="Proxy URL" value={proxyUrl ?? ''} onChange={(v) => useSettingsStore.getState().setProxyUrl(v || null)} placeholder="Auto-detected from system" />
      <SettingsField label="CA Certificate Path" value={caCertPath ?? ''} onChange={(v) => useSettingsStore.getState().setCaCertPath(v || null)} placeholder="/path/to/corporate-ca.pem" />
      <div className="pt-2 border-t border-gray-800">
        <div className="text-xs text-gray-500 mb-2">Authentication</div>
        <div className="flex items-center gap-3">
          <button
            onClick={handleSignOut}
            disabled={signingOut}
            className="text-xs px-3 py-1.5 bg-gray-800 border border-gray-700 rounded
                       text-red-400 hover:text-red-300 hover:border-red-500/50 transition-colors disabled:opacity-50"
          >
            {signingOut ? 'Signing out...' : 'Sign Out'}
          </button>
          <span className="text-xs text-gray-500">Clear stored tokens and re-authenticate</span>
        </div>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  Phase 5, Tasks 1-2: Tools Settings with MCP server management      */
/* ------------------------------------------------------------------ */

function ToolsSettings() {
  const tools = useSettingsStore((s) => s.tools)
  const [servers, setServers] = useState<Array<{
    name: string; type: 'stdio' | 'sse'; status: string; toolCount: number; error?: string
  }>>([])
  const [showAddWizard, setShowAddWizard] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)

  // Load server list
  useEffect(() => {
    if (!window.api?.mcp?.listServers) return
    window.api.mcp.listServers().then(setServers).catch(() => {})
  }, [refreshKey])

  const handleRemoveServer = async (name: string) => {
    if (!window.api?.mcp?.removeServer) return
    await window.api.mcp.removeServer(name)
    setRefreshKey(k => k + 1)
  }

  // Group tools by source
  const grouped = tools.reduce<Record<string, ToolDef[]>>((acc, tool) => {
    const key = tool.source
    if (!acc[key]) acc[key] = []
    acc[key].push(tool)
    return acc
  }, {})

  return (
    <div className="space-y-4">
      {/* MCP Servers section */}
      <div>
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-semibold text-gray-400 uppercase tracking-wide">MCP Servers</span>
          <button
            onClick={() => setShowAddWizard(true)}
            className="text-xs px-2 py-1 bg-blue-600 text-white rounded hover:bg-blue-500 transition-colors"
          >
            + Add Server
          </button>
        </div>

        {servers.length === 0 ? (
          <p className="text-sm text-gray-500">No MCP servers configured. Add one to extend available tools.</p>
        ) : (
          <div className="space-y-2">
            {servers.map((server) => {
              const statusColor =
                server.status === 'connected' ? 'bg-green-500' :
                server.status === 'error' ? 'bg-red-500' :
                server.status === 'connecting' ? 'bg-yellow-500' :
                'bg-gray-600'
              return (
                <div key={server.name} className="flex items-center gap-3 px-3 py-2 bg-gray-800 rounded-lg">
                  <span className={`inline-block w-2 h-2 rounded-full ${statusColor} shrink-0`} />
                  <div className="flex-1 min-w-0">
                    <div className="text-sm text-gray-200 font-medium">{server.name}</div>
                    <div className="text-[11px] text-gray-500">
                      {server.type.toUpperCase()} &middot; {server.toolCount} tool{server.toolCount !== 1 ? 's' : ''}
                      {server.error && <span className="text-red-400 ml-2">{server.error}</span>}
                    </div>
                  </div>
                  <button
                    onClick={() => handleRemoveServer(server.name)}
                    className="text-xs px-2 py-1 text-red-400 hover:text-red-300 hover:bg-gray-700 rounded transition-colors"
                  >
                    Remove
                  </button>
                </div>
              )
            })}
          </div>
        )}
      </div>

      {/* Built-in tools section */}
      <div className="border-t border-gray-800 pt-4">
        <span className="text-xs font-semibold text-gray-400 uppercase tracking-wide">Built-in Tools</span>
        <div className="mt-2 space-y-1">
          {(grouped['built-in'] ?? []).map((tool) => (
            <BuiltInToolRow key={tool.name} tool={tool} />
          ))}
        </div>
      </div>

      {/* MCP tool lists per server */}
      {Object.entries(grouped)
        .filter(([source]) => source !== 'built-in')
        .map(([source, sourceTools]) => (
          <div key={source} className="border-t border-gray-800 pt-4">
            <span className="text-xs font-semibold text-gray-400 uppercase tracking-wide">{source} Tools</span>
            <div className="mt-2 space-y-1">
              {sourceTools.map((tool) => (
                <div key={tool.name} className="flex items-center gap-2 px-2 py-1 text-sm text-gray-300">
                  <span className="font-mono text-gray-200">{tool.name}</span>
                  <span className="text-xs text-gray-500">{tool.description}</span>
                  {tool.usageCount > 0 && (
                    <span className="text-[10px] text-gray-600 ml-auto">{tool.usageCount}x</span>
                  )}
                </div>
              ))}
            </div>
          </div>
        ))}

      {/* Add Server Wizard modal */}
      {showAddWizard && (
        <AddServerWizard
          onClose={() => setShowAddWizard(false)}
          onAdded={() => { setShowAddWizard(false); setRefreshKey(k => k + 1) }}
        />
      )}
    </div>
  )
}

function BuiltInToolRow({ tool }: { tool: ToolDef }) {
  const setToolPermission = useSettingsStore((s) => s.setToolPermission)
  const permissions = useSettingsStore((s) => s.toolPermissions)
  const currentPerm = permissions[tool.name] ?? tool.permission

  return (
    <div className="flex items-center gap-2 px-2 py-1.5 rounded hover:bg-gray-800/50 transition-colors">
      <div className="flex-1 min-w-0">
        <div className="text-sm text-gray-200 font-mono">{tool.name}</div>
        <div className="text-[11px] text-gray-500">{tool.description}</div>
      </div>
      <select
        value={currentPerm}
        onChange={(e) => setToolPermission(tool.name, e.target.value as 'auto' | 'confirm' | 'always-ask')}
        className="text-[11px] bg-gray-800 border border-gray-700 rounded px-1.5 py-0.5 text-gray-300 outline-none"
      >
        <option value="auto">Auto</option>
        <option value="confirm">Confirm</option>
        <option value="always-ask">Always Ask</option>
      </select>
      {tool.usageCount > 0 && (
        <span className="text-[10px] text-gray-600">{tool.usageCount}x</span>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  Phase 5, Task 2: Add Server Wizard                                 */
/* ------------------------------------------------------------------ */

function AddServerWizard({ onClose, onAdded }: { onClose: () => void; onAdded: () => void }) {
  const [name, setName] = useState('')
  const [type, setType] = useState<'stdio' | 'sse'>('stdio')
  const [command, setCommand] = useState('')
  const [url, setUrl] = useState('')
  const [envVars, setEnvVars] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = async () => {
    if (!name.trim()) { setError('Name is required'); return }
    if (type === 'stdio' && !command.trim()) { setError('Command is required for stdio servers'); return }
    if (type === 'sse' && !url.trim()) { setError('URL is required for SSE servers'); return }
    if (!window.api?.mcp?.addServer) { setError('MCP API not available'); return }

    // Parse env vars (KEY=VALUE per line)
    const env: Record<string, string> = {}
    for (const line of envVars.split('\n')) {
      const trimmed = line.trim()
      if (!trimmed || trimmed.startsWith('#')) continue
      const eqIdx = trimmed.indexOf('=')
      if (eqIdx > 0) {
        env[trimmed.slice(0, eqIdx).trim()] = trimmed.slice(eqIdx + 1).trim()
      }
    }

    // Parse command into command + args
    const parts = command.trim().split(/\s+/)
    const cmd = parts[0] ?? ''
    const args = parts.slice(1)

    setSubmitting(true)
    setError('')
    try {
      const result = await window.api.mcp.addServer({
        name: name.trim(),
        type,
        command: type === 'stdio' ? cmd : undefined,
        args: type === 'stdio' ? args : undefined,
        url: type === 'sse' ? url.trim() : undefined,
        env: Object.keys(env).length > 0 ? env : undefined,
      })
      if (result.success) {
        onAdded()
      } else {
        setError(result.error ?? 'Failed to add server')
      }
    } catch (e: any) {
      setError(e.message ?? 'Unknown error')
    }
    setSubmitting(false)
  }

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50" onClick={onClose}>
      <div
        className="w-[440px] bg-gray-900 border border-gray-700 rounded-xl shadow-2xl overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="px-4 py-3 border-b border-gray-800">
          <h3 className="text-sm font-semibold text-gray-100">Add MCP Server</h3>
        </div>
        <div className="px-4 py-3 space-y-3">
          {/* Name */}
          <div>
            <label className="text-xs text-gray-400 mb-1 block">Server Name</label>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="my-mcp-server"
              className="w-full px-3 py-1.5 text-sm bg-gray-800 border border-gray-700 rounded
                         text-gray-100 placeholder-gray-600 focus:border-blue-500 outline-none"
            />
          </div>

          {/* Type selector */}
          <div>
            <label className="text-xs text-gray-400 mb-1 block">Transport Type</label>
            <div className="flex gap-1">
              <button
                onClick={() => setType('stdio')}
                className={`px-3 py-1.5 text-xs rounded transition-colors ${
                  type === 'stdio' ? 'bg-blue-600 text-white' : 'bg-gray-800 text-gray-400 hover:text-white'
                }`}
              >
                stdio
              </button>
              <button
                onClick={() => setType('sse')}
                className={`px-3 py-1.5 text-xs rounded transition-colors ${
                  type === 'sse' ? 'bg-blue-600 text-white' : 'bg-gray-800 text-gray-400 hover:text-white'
                }`}
              >
                SSE
              </button>
            </div>
          </div>

          {/* Command (stdio) or URL (sse) */}
          {type === 'stdio' ? (
            <div>
              <label className="text-xs text-gray-400 mb-1 block">Command</label>
              <input
                value={command}
                onChange={(e) => setCommand(e.target.value)}
                placeholder="npx -y @modelcontextprotocol/server-filesystem /tmp"
                className="w-full px-3 py-1.5 text-sm bg-gray-800 border border-gray-700 rounded
                           text-gray-100 placeholder-gray-600 focus:border-blue-500 outline-none font-mono"
              />
            </div>
          ) : (
            <div>
              <label className="text-xs text-gray-400 mb-1 block">Server URL</label>
              <input
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                placeholder="http://localhost:3001/sse"
                className="w-full px-3 py-1.5 text-sm bg-gray-800 border border-gray-700 rounded
                           text-gray-100 placeholder-gray-600 focus:border-blue-500 outline-none font-mono"
              />
            </div>
          )}

          {/* Env vars */}
          <div>
            <label className="text-xs text-gray-400 mb-1 block">Environment Variables (one per line, KEY=VALUE)</label>
            <textarea
              value={envVars}
              onChange={(e) => setEnvVars(e.target.value)}
              placeholder="API_KEY=sk-..."
              rows={3}
              className="w-full px-3 py-1.5 text-sm bg-gray-800 border border-gray-700 rounded
                         text-gray-100 placeholder-gray-600 focus:border-blue-500 outline-none font-mono resize-none"
            />
          </div>

          {error && (
            <div className="text-xs text-red-400">{error}</div>
          )}
        </div>
        <div className="flex items-center justify-end gap-2 px-4 py-3 border-t border-gray-800">
          <button
            onClick={onClose}
            className="text-xs px-3 py-1.5 bg-gray-800 border border-gray-700 rounded
                       text-gray-400 hover:text-white transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={submitting}
            className="text-xs px-3 py-1.5 bg-blue-600 border border-blue-500 rounded
                       text-white hover:bg-blue-500 transition-colors disabled:opacity-50"
          >
            {submitting ? 'Adding...' : 'Add Server'}
          </button>
        </div>
      </div>
    </div>
  )
}

function ThemeSettings() {
  const colorMode = useSettingsStore((s) => s.colorMode)
  const tokens = useSettingsStore((s) => s.tokens)

  return (
    <div className="space-y-4">
      <div>
        <label className="text-xs text-gray-400 mb-1 block">Color Mode</label>
        <div className="flex gap-1">
          {(['system', 'light', 'dark'] as ColorMode[]).map((mode) => (
            <button
              key={mode}
              onClick={() => useSettingsStore.getState().setColorMode(mode)}
              className={`px-3 py-1.5 text-xs rounded transition-colors ${
                colorMode === mode ? 'bg-blue-600 text-white' : 'bg-gray-800 text-gray-400 hover:text-white'
              }`}
            >
              {mode.charAt(0).toUpperCase() + mode.slice(1)}
            </button>
          ))}
        </div>
      </div>
      <div className="border-t border-gray-800 pt-3">
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs text-gray-400">Theme Tokens</span>
          <button
            onClick={() => useSettingsStore.getState().resetTokens()}
            className="text-[10px] text-gray-500 hover:text-white transition-colors"
          >
            Reset to defaults
          </button>
        </div>
        <div className="grid grid-cols-2 gap-2">
          {(Object.keys(tokens) as (keyof ThemeTokens)[]).map((key) => (
            <TokenEditor key={key} tokenKey={key} value={tokens[key]} />
          ))}
        </div>
      </div>
    </div>
  )
}

function TokenEditor({ tokenKey, value }: { tokenKey: keyof ThemeTokens; value: string }) {
  return (
    <div className="flex items-center gap-2 bg-gray-800/50 rounded px-2 py-1.5">
      <input
        type="color"
        value={value}
        onChange={(e) => useSettingsStore.getState().setToken(tokenKey, e.target.value)}
        className="w-5 h-5 rounded border-0 cursor-pointer bg-transparent"
      />
      <div className="flex-1 min-w-0">
        <div className="text-[11px] text-gray-300 truncate">{tokenKey}</div>
        <div className="text-[10px] text-gray-500 font-mono">{value}</div>
      </div>
    </div>
  )
}

function ChatSettings() {
  const toolDisplayMode = useSettingsStore((s) => s.toolDisplayMode)
  const debugLogging = useSettingsStore((s) => s.debugLogging)

  const modes: { value: ToolDisplayMode; label: string; desc: string }[] = [
    { value: 'collapsible', label: 'Collapsible blocks', desc: 'Compact blocks that expand on click' },
    { value: 'inline', label: 'Minimal inline', desc: 'Small gray labels between messages' },
    { value: 'hidden', label: 'Hidden', desc: 'Show N tool calls toggle' },
  ]

  return (
    <div className="space-y-4">
      <div>
        <label className="text-xs text-gray-400 mb-2 block">Tool Call Display</label>
        <div className="space-y-1.5">
          {modes.map((mode) => (
            <button
              key={mode.value}
              onClick={() => useSettingsStore.getState().setToolDisplayMode(mode.value)}
              className={`w-full text-left px-3 py-2 rounded text-sm transition-colors ${
                toolDisplayMode === mode.value
                  ? 'bg-blue-600/20 border border-blue-500 text-white'
                  : 'bg-gray-800 border border-gray-700 text-gray-400 hover:text-white'
              }`}
            >
              <div className="font-medium">{mode.label}</div>
              <div className="text-xs text-gray-500 mt-0.5">{mode.desc}</div>
            </button>
          ))}
        </div>
      </div>
      <div className="border-t border-gray-800 pt-4">
        <div className="flex items-center justify-between">
          <div>
            <div className="text-sm text-gray-300">Debug Logging</div>
            <div className="text-xs text-gray-500 mt-0.5">Write verbose logs to .copilot/logs/</div>
          </div>
          <button
            onClick={() => useSettingsStore.getState().setDebugLogging(!debugLogging)}
            className={`relative w-9 h-5 rounded-full transition-colors ${
              debugLogging ? 'bg-blue-600' : 'bg-gray-700'
            }`}
          >
            <span
              className={`absolute top-0.5 w-4 h-4 bg-white rounded-full transition-transform ${
                debugLogging ? 'translate-x-4' : 'translate-x-0.5'
              }`}
            />
          </button>
        </div>
      </div>
    </div>
  )
}

function KeybindingsSettings() {
  const shortcuts = [
    { keys: 'Cmd+K', action: 'Open command palette' },
    { keys: 'Cmd+Enter', action: 'Send message' },
    { keys: 'Cmd+N', action: 'New conversation tab' },
    { keys: 'Cmd+/', action: 'Toggle sidebar' },
    { keys: 'Cmd+\\', action: 'Cycle view modes' },
    { keys: 'Cmd+Shift+P', action: 'Open settings' },
    { keys: 'Cmd+W', action: 'Close current tab' },
    { keys: 'Escape', action: 'Close panels / deselect' },
  ]

  return (
    <div className="space-y-1">
      {shortcuts.map((s) => (
        <div key={s.keys} className="flex items-center justify-between py-1.5">
          <span className="text-sm text-gray-300">{s.action}</span>
          <kbd className="text-xs font-mono bg-gray-800 border border-gray-700 px-2 py-0.5 rounded text-gray-400">
            {s.keys}
          </kbd>
        </div>
      ))}
    </div>
  )
}

function SearchPanel() {
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

function VcsPanel({ changedFiles, gitStatus }: { changedFiles: Array<{ path: string; action: string }>; gitStatus: Map<string, string> }) {
  // Build working tree entries from git status, excluding agent changes
  const agentPaths = new Set(changedFiles.map((f) => f.path))
  const workingTree = Array.from(gitStatus.entries())
    .filter(([file]) => !agentPaths.has(file))
    .map(([file, status]) => ({ file, status }))

  const hasAgent = changedFiles.length > 0
  const hasWorkingTree = workingTree.length > 0
  const isEmpty = !hasAgent && !hasWorkingTree

  return (
    <div className="p-3 h-full flex flex-col">
      <div className="text-[11px] font-semibold text-gray-500 uppercase tracking-wide mb-2">
        Source Control
      </div>
      {isEmpty ? (
        <div className="flex-1 flex items-center justify-center">
          <p className="text-xs text-gray-600 text-center px-4">
            No changes detected
          </p>
        </div>
      ) : (
        <div className="flex-1 overflow-y-auto space-y-3">
          {hasAgent && (
            <div>
              <div className="text-[10px] text-gray-500 uppercase tracking-wide mb-1">Agent Changes</div>
              <div className="space-y-0.5">
                {changedFiles.map((f) => {
                  const color = f.action === 'created' ? 'text-green-400'
                    : f.action === 'deleted' ? 'text-red-400'
                    : 'text-yellow-400'
                  const label = f.action === 'created' ? 'A'
                    : f.action === 'deleted' ? 'D'
                    : 'M'
                  const filename = f.path.split('/').pop() || f.path
                  return (
                    <div key={f.path} className="flex items-center gap-2 text-xs px-1 py-0.5 rounded hover:bg-gray-800">
                      <span className={`font-mono font-bold ${color} w-3`}>{label}</span>
                      <span className="text-gray-400 truncate font-mono">{filename}</span>
                    </div>
                  )
                })}
              </div>
            </div>
          )}
          {hasWorkingTree && (
            <div>
              <div className="text-[10px] text-gray-500 uppercase tracking-wide mb-1">Working Tree</div>
              <div className="space-y-0.5">
                {workingTree.map(({ file, status }) => {
                  const color = status === '??' || status === 'A' ? 'text-green-400'
                    : status === 'D' ? 'text-red-400'
                    : 'text-yellow-400'
                  const label = status === '??' ? '?' : status === 'A' ? 'A' : status === 'D' ? 'D' : 'M'
                  const filename = file.split('/').pop() || file
                  return (
                    <div key={file} className="flex items-center gap-2 text-xs px-1 py-0.5 rounded hover:bg-gray-800">
                      <span className={`font-mono font-bold ${color} w-3`}>{label}</span>
                      <span className="text-gray-400 truncate font-mono">{filename}</span>
                    </div>
                  )
                })}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function SettingsField({
  label,
  value,
  onChange,
  placeholder,
}: {
  label: string
  value: string
  onChange: (v: string) => void
  placeholder?: string
}) {
  return (
    <div>
      <label className="text-xs text-gray-400 mb-1 block">{label}</label>
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="w-full px-3 py-1.5 text-sm bg-gray-800 border border-gray-700 rounded
                   text-gray-100 placeholder-gray-600 focus:border-blue-500 outline-none"
      />
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  Phase 6, Task 2: Factory functions for store isolation              */
/* ------------------------------------------------------------------ */

/**
 * Factory to create an isolated agent store for a conversation tab.
 * Each tab can call createAgentStore() to get its own state.
 */
export function createAgentStore() {
  let counter = 0
  const nextId = () => `msg-${++counter}`

  return create<{
    messages: ChatMessage[]
    isProcessing: boolean
    addUserMessage: (text: string) => void
    addAgentDelta: (text: string) => void
    reset: () => void
  }>((set, get) => ({
    messages: [],
    isProcessing: false,
    addUserMessage: (text) => {
      set((s) => ({
        messages: [...s.messages, { id: nextId(), type: 'user', text, timestamp: Date.now() }],
      }))
    },
    addAgentDelta: (text) => {
      set((s) => {
        const msgs = [...s.messages]
        const last = msgs.findLast((m) => m.type === 'agent' && m.status === 'running')
        if (last) { last.text += text; return { messages: msgs } }
        return { messages: [...msgs, { id: nextId(), type: 'agent', text, timestamp: Date.now(), status: 'running' as const }] }
      })
    },
    reset: () => { counter = 0; set({ messages: [], isProcessing: false }) },
  }))
}

/**
 * Factory to create an isolated flow store for a conversation tab.
 */
export function createFlowStore() {
  return create<{
    nodes: import('@xyflow/react').Node[]
    edges: import('@xyflow/react').Edge[]
    reset: () => void
  }>(() => ({
    nodes: [],
    edges: [],
    reset: () => {},
  }))
}
