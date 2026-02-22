import { useState, useCallback, useEffect, useRef } from 'react'
import { ReactFlowProvider, type Node } from '@xyflow/react'
import { AgentFlow } from './flow/AgentFlow'
import { ChatPanel } from './components/ChatPanel'
import { ChatInput } from './components/ChatInput'
import { FileTree } from './components/FileTree'
import { StatusBar } from './components/StatusBar'
import { ChangesPanel } from './components/ChangesPanel'
import { ProjectPicker } from './components/ProjectPicker'
import { ActivityBar, type SidePanel } from './components/ActivityBar'
import { FileEditor, type OpenFile, prefetchFile } from './components/FileEditor'
import { AuthDialog } from './components/AuthDialog'
import { useFlowStore } from './stores/flow-store'
import { useAgentStore, type ChatMessage } from './stores/agent-store'
import { useSettingsStore, type ThemeTokens, type ToolDisplayMode, type ColorMode } from './stores/settings-store'
import { startDemo } from './demo'

type ViewMode = 'split' | 'chat' | 'graph'

export function App() {
  const projectPath = useSettingsStore((s) => s.projectPath)

  // Hydrate settings from disk on first load
  useEffect(() => {
    useSettingsStore.getState().hydrateFromDisk()
  }, [])

  // Show project picker if no project is loaded
  if (!projectPath) {
    return <ProjectPicker onSelect={(path) => useSettingsStore.getState().setProjectPath(path)} />
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
        handleNewConversation()
      } else if (meta && e.shiftKey && e.key === 'p') {
        e.preventDefault()
        setSettingsOpen((s) => !s)
      } else if (meta && e.key === 'k') {
        e.preventDefault()
        setPaletteOpen((s) => !s)
      } else if (meta && e.key === 'w') {
        e.preventDefault()
        if (activeFile) handleCloseFile(activeFile)
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
  }, [cycleViewMode, handleNewConversation, activeFile, handleCloseFile])

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
                <VcsPanel changedFiles={changedFiles} />
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
            window.api?.conversations?.load(id).then((data) => {
              if (data) {
                useAgentStore.getState().loadConversation(data.messages as ChatMessage[])
                useFlowStore.getState().loadFromMessages(data.messages as ChatMessage[])
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
    case 'Theme': return <ThemeSettings />
    case 'Chat': return <ChatSettings />
    case 'Keybindings': return <KeybindingsSettings />
    default:
      return <p className="text-sm text-gray-500">{section} settings will be configured here.</p>
  }
}

function ConnectionSettings() {
  const model = useSettingsStore((s) => s.model)
  const proxyUrl = useSettingsStore((s) => s.proxyUrl)

  return (
    <div className="space-y-4">
      <SettingsField label="Model" value={model} onChange={(v) => useSettingsStore.getState().setModel(v)} />
      <SettingsField label="Proxy URL" value={proxyUrl ?? ''} onChange={(v) => useSettingsStore.getState().setProxyUrl(v || null)} placeholder="Auto-detected from system" />
      <SettingsField label="CA Certificate Path" value="" onChange={() => {}} placeholder="/path/to/corporate-ca.pem" />
      <div className="pt-2 border-t border-gray-800">
        <div className="text-xs text-gray-500 mb-2">Authentication</div>
        <p className="text-sm text-gray-400">GitHub Copilot auth will be configured here.</p>
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
    </div>
  )
}

function KeybindingsSettings() {
  const shortcuts = [
    { keys: 'Cmd+K', action: 'Open command palette' },
    { keys: 'Cmd+Enter', action: 'Send message' },
    { keys: 'Cmd+N', action: 'New conversation' },
    { keys: 'Cmd+/', action: 'Toggle sidebar' },
    { keys: 'Cmd+\\', action: 'Cycle view modes' },
    { keys: 'Cmd+Shift+P', action: 'Open settings' },
    { keys: 'Cmd+W', action: 'Close active file tab' },
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

function VcsPanel({ changedFiles }: { changedFiles: Array<{ path: string; action: string }> }) {
  return (
    <div className="p-3 h-full flex flex-col">
      <div className="text-[11px] font-semibold text-gray-500 uppercase tracking-wide mb-2">
        Source Control
      </div>
      {changedFiles.length === 0 ? (
        <div className="flex-1 flex items-center justify-center">
          <p className="text-xs text-gray-600 text-center px-4">
            No changes detected
          </p>
        </div>
      ) : (
        <div className="flex-1 overflow-y-auto space-y-0.5">
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
