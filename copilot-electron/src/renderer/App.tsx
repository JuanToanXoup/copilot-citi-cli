import { useState, useCallback, useEffect, useMemo } from 'react'
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
import { SettingsModal } from './components/settings'
import { CommandPalette } from './components/CommandPalette'
import { ToolConfirmDialog } from './components/ToolConfirmDialog'
import { SearchPanel } from './components/SearchPanel'
import { VcsPanel } from './components/VcsPanel'
import type { ChatMessage } from './stores/agent-store'
import { useSettingsStore } from './stores/settings-store'
import { TabStoreProvider, useTabAgentStore, useTabStores } from './contexts/TabStoreContext'
import { useGitPolling } from './hooks/useGitPolling'
import { useKeyboardShortcuts } from './hooks/useKeyboardShortcuts'
import { useResizableDivider } from './hooks/useResizableDivider'
import { startDemo } from './demo'

type ViewMode = 'split' | 'chat' | 'graph'

/* ------------------------------------------------------------------ */
/*  Multi-conversation tab types (Phase 6, Task 2)                     */
/* ------------------------------------------------------------------ */

interface ConversationTab {
  id: string
  label: string
}

export function App() {
  const projectPath = useSettingsStore((s) => s.projectPath)

  // Hydrate settings from disk on first load
  useEffect(() => {
    useSettingsStore.getState().hydrateFromDisk()
  }, [])

  // Subscribe to external settings changes (e.g., manual edits to .copilot/config.json)
  useEffect(() => {
    if (!window.api?.settings?.onChanged) return
    return window.api.settings.onChanged((data) => {
      useSettingsStore.getState().applyExternalSettings(data)
    })
  }, [])

  // Show project picker if no project is loaded
  if (!projectPath) {
    return <ProjectPicker onSelect={(path) => { addRecentProject(path); useSettingsStore.getState().setProjectPath(path) }} />
  }

  return <MainApp />
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

function MainApp() {
  // Phase 6, Task 2: Multi-conversation tabs (must be declared before TabStoreProvider)
  const [tabs, setTabs] = useState<ConversationTab[]>([
    { id: 'default', label: 'Chat 1' },
  ])
  const [activeTabId, setActiveTabId] = useState('default')

  return (
    <TabStoreProvider tabId={activeTabId}>
      <MainAppContent
        tabs={tabs}
        setTabs={setTabs}
        activeTabId={activeTabId}
        setActiveTabId={setActiveTabId}
      />
    </TabStoreProvider>
  )
}

function MainAppContent({ tabs, setTabs, activeTabId, setActiveTabId }: {
  tabs: ConversationTab[]
  setTabs: React.Dispatch<React.SetStateAction<ConversationTab[]>>
  activeTabId: string
  setActiveTabId: React.Dispatch<React.SetStateAction<string>>
}) {
  const [viewMode, setViewMode] = useState<ViewMode>('split')
  const [activePanel, setActivePanel] = useState<SidePanel | null>('explorer')
  const [selectedNode, setSelectedNode] = useState<Node | null>(null)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [paletteOpen, setPaletteOpen] = useState(false)
  const [changesOpen, setChangesOpen] = useState(false)
  const [openFiles, setOpenFiles] = useState<OpenFile[]>([])
  const [activeFile, setActiveFile] = useState<string | null>(null)
  const [lspConnected, setLspConnected] = useState(false)
  const [lspConnectionState, setLspConnectionState] = useState<string>('disconnected')
  const [authDialogOpen, setAuthDialogOpen] = useState(false)
  const [toolConfirmReq, setToolConfirmReq] = useState<{ id: string; name: string; input: Record<string, unknown> } | null>(null)
  const [conversations, setConversations] = useState<Array<{ id: string; date: string; summary: string; path: string }>>([])
  const [fileTreeRefreshKey, setFileTreeRefreshKey] = useState(0)

  // Use per-tab stores from context
  const { agentStore, flowStore } = useTabStores()
  const isProcessing = useTabAgentStore((s) => s.isProcessing)
  const changedFiles = useTabAgentStore((s) => s.changedFiles)

  const projectPath = useSettingsStore((s) => s.projectPath)

  // Extracted hooks
  const { gitBranch, gitChangesCount, gitStatus } = useGitPolling()
  const divider = useResizableDivider({ initial: 50, min: 20, max: 80, mode: 'percentage' })
  const sidebar = useResizableDivider({ initial: 260, min: 140, max: 480, mode: 'pixel' })
  const editor = useResizableDivider({ initial: 500, min: 200, max: 1200, mode: 'pixel' })

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
    prefetchFile(path)
    setOpenFiles((prev) => {
      if (prev.some((f) => f.path === path)) return prev
      return [...prev, { path, name }]
    })
    setActiveFile(path)
  }, [])

  const handleCloseFile = useCallback((path: string) => {
    setOpenFiles((prev) => {
      const next = prev.filter((f) => f.path !== path)
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
    window.api.agent.newConversation(activeTabId)
    flowStore.getState().onReset()
    agentStore.getState().reset()
    setSelectedNode(null)
  }, [activeTabId, agentStore, flowStore])

  // Phase 6, Task 2: Create new tab
  const handleNewTab = useCallback(() => {
    const id = `tab-${Date.now()}`
    const label = `Chat ${tabs.length + 1}`
    setTabs((prev) => [...prev, { id, label }])
    setActiveTabId(id)
    // The TabStoreProvider automatically creates fresh stores for the new tabId
    window.api.agent.newConversation(id)
  }, [tabs.length])

  const handleCloseTab = useCallback((tabId: string) => {
    setTabs((prev) => {
      const next = prev.filter((t) => t.id !== tabId)
      if (next.length === 0) {
        return [{ id: 'default', label: 'Chat 1' }]
      }
      if (activeTabId === tabId) {
        setActiveTabId(next[next.length - 1].id)
      }
      return next
    })
  }, [activeTabId])

  // Keyboard shortcuts
  const shortcutHandlers = useMemo(() => ({
    cycleViewMode,
    toggleSidebar: () => setActivePanel((s) => (s ? null : 'explorer')),
    toggleSettings: () => setSettingsOpen((s) => !s),
    togglePalette: () => setPaletteOpen((s) => !s),
    handleNewTab,
    handleCloseTab: () => {
      if (tabs.length > 1) {
        handleCloseTab(activeTabId)
      } else if (activeFile) {
        handleCloseFile(activeFile)
      }
    },
    handleCloseFile: () => activeFile && handleCloseFile(activeFile),
    dismissAll: () => {
      setSettingsOpen(false)
      setPaletteOpen(false)
      setChangesOpen(false)
      setSelectedNode(null)
      setToolConfirmReq(null)
    },
  }), [cycleViewMode, handleNewTab, handleCloseTab, activeTabId, tabs.length, activeFile, handleCloseFile])

  useKeyboardShortcuts(shortcutHandlers)

  // Subscribe to LSP status from main process
  useEffect(() => {
    const unsub = window.api.agent.onLspStatus((status) => {
      setLspConnected(status.connected)
    })
    return unsub
  }, [])

  // Subscribe to LSP connection state (reconnection UI)
  useEffect(() => {
    if (!window.api?.agent?.onConnectionState) return
    const unsub = window.api.agent.onConnectionState((state) => {
      setLspConnectionState(state)
    })
    return unsub
  }, [])

  // Subscribe to agent events from main process (filtered by tabId)
  useEffect(() => {
    const unsub = window.api.agent.onEvent((event: any) => {
      // Only process events for this tab
      if (event.tabId && event.tabId !== activeTabId) return

      const flow = flowStore.getState()
      const agent = agentStore.getState()

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
          if (event.message?.includes('Authentication') || event.message?.includes('auth')) {
            setAuthDialogOpen(true)
          }
          break
        case 'lead:toolcall': {
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
          setFileTreeRefreshKey(k => k + 1)
          if (openFiles.some(f => f.path === event.filePath)) {
            setOpenFiles(prev => [...prev])
          }
          break
        default:
          agent.handleEvent(event)
      }
    })
    return unsub
  }, [activeTabId, openFiles, agentStore, flowStore])

  // Phase 3: Subscribe to file changed events from main process
  useEffect(() => {
    if (!window.api?.agent?.onFileChanged) return
    const unsub = window.api.agent.onFileChanged((data) => {
      agentStore.getState().addFileChange(data.filePath, data.action as 'created' | 'modified' | 'deleted')
      setFileTreeRefreshKey(k => k + 1)
    })
    return unsub
  }, [agentStore])

  // Phase 7: Subscribe to tool confirm requests
  useEffect(() => {
    if (!window.api?.tools?.onConfirmRequest) return
    const unsub = window.api.tools.onConfirmRequest((req) => {
      setToolConfirmReq(req)
    })
    return unsub
  }, [])

  const handleSend = useCallback((text: string) => {
    if (text === '/new' || text === '/clear') {
      flowStore.getState().onReset()
      agentStore.getState().reset()
      setSelectedNode(null)
      return
    }
    if (text === '/settings') { setSettingsOpen(true); return }
    if (text === '/tools') { return }
    if (text === '/changes') { setActivePanel('vcs'); return }

    if (text.startsWith('/commit')) {
      const msg = text.slice(7).trim().replace(/^["']|["']$/g, '') || 'Auto-commit'
      window.api?.git?.commit(msg).then((result) => {
        agentStore.getState().addStatusMessage(result)
      })
      return
    }
    if (text === '/push') {
      window.api?.git?.push().then((result) => {
        agentStore.getState().addStatusMessage(result || 'Pushed successfully')
      })
      return
    }
    if (text === '/pull') {
      window.api?.git?.pull().then((result) => {
        agentStore.getState().addStatusMessage(result || 'Pulled successfully')
      })
      return
    }
    if (text.startsWith('/branch')) {
      const name = text.slice(7).trim()
      if (name) {
        window.api?.git?.checkout(name, true).then((result) => {
          agentStore.getState().addStatusMessage(result || `Switched to branch ${name}`)
        })
      } else {
        window.api?.git?.branches().then((branches) => {
          agentStore.getState().addStatusMessage(`Branches: ${branches.join(', ')}`)
        })
      }
      return
    }

    if (text.toLowerCase() === 'demo') { startDemo(agentStore, flowStore); return }

    flowStore.getState().onNewTurn(text)
    agentStore.getState().addUserMessage(text)
    setSelectedNode(null)
    window.api.agent.sendMessage(text, activeTabId)
  }, [activeTabId, agentStore, flowStore])

  const handleCancel = useCallback(() => {
    window.api.agent.cancel(activeTabId)
    agentStore.getState().setProcessing(false)
  }, [activeTabId, agentStore])

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
              style={{ width: `${sidebar.position}px` }}
            >
              {activePanel === 'explorer' && (
                <FileTree onFileSelect={handleFileSelect} gitStatus={gitStatus} refreshKey={fileTreeRefreshKey} />
              )}
              {activePanel === 'search' && <SearchPanel />}
              {activePanel === 'vcs' && <VcsPanel changedFiles={changedFiles} gitStatus={gitStatus} />}
            </div>
            <div
              ref={sidebar.ref}
              onMouseDown={sidebar.onMouseDown}
              className="w-1 shrink-0 bg-gray-800 hover:bg-blue-500 cursor-col-resize transition-colors"
            />
          </>
        )}

        {/* Editor area */}
        <div className="shrink-0 overflow-hidden" style={{ width: `${editor.position}px` }}>
          <FileEditor
            openFiles={openFiles}
            activeFile={activeFile}
            onSelectFile={setActiveFile}
            onCloseFile={handleCloseFile}
          />
        </div>
        <div
          ref={editor.ref}
          onMouseDown={editor.onMouseDown}
          className="w-1 shrink-0 bg-gray-800 hover:bg-blue-500 cursor-col-resize transition-colors"
        />

        {/* Chat + Graph panes */}
        <div className="flex flex-1 overflow-hidden relative">
          {showChat && (
            <div
              className="overflow-hidden flex flex-col"
              style={{ width: viewMode === 'split' ? `${divider.position}%` : '100%' }}
            >
              <ChatPanel
                selectedNodeId={selectedNode?.id ?? null}
                onNodeSelect={setSelectedNode}
              />
            </div>
          )}

          {viewMode === 'split' && (
            <div
              ref={divider.ref}
              onMouseDown={divider.onMouseDown}
              className="w-1 shrink-0 bg-gray-800 hover:bg-blue-500 cursor-col-resize transition-colors"
            />
          )}

          {showGraph && (
            <div
              className="overflow-hidden"
              style={{ width: viewMode === 'split' ? `${100 - divider.position}%` : '100%' }}
            >
              <ReactFlowProvider>
                <AgentFlow selectedNode={selectedNode} onNodeSelect={setSelectedNode} />
              </ReactFlowProvider>
            </div>
          )}
        </div>
      </div>

      {/* Chat input */}
      <ChatInput
        onSend={handleSend}
        onCancel={handleCancel}
        disabled={isProcessing}
        isProcessing={isProcessing}
        placeholder="Send a message, type 'demo', or use /commands..."
      />

      <StatusBar
        connected={lspConnected}
        connectionState={lspConnectionState}
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
                agentStore.getState().loadConversation(data.messages as ChatMessage[])
                flowStore.getState().loadFromMessages(data.messages as ChatMessage[])
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

      {settingsOpen && <SettingsModal onClose={() => setSettingsOpen(false)} />}
      {changesOpen && <ChangesPanel onClose={() => setChangesOpen(false)} />}
      {authDialogOpen && <AuthDialog onClose={() => setAuthDialogOpen(false)} />}

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

