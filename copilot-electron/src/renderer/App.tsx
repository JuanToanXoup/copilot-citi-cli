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
import { useFlowStore } from './stores/flow-store'
import { useAgentStore } from './stores/agent-store'
import { useSettingsStore, type ThemeTokens, type ToolDisplayMode, type ColorMode } from './stores/settings-store'
import { startDemo } from './demo'

type ViewMode = 'split' | 'chat' | 'graph'

export function App() {
  const projectPath = useSettingsStore((s) => s.projectPath)

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
  const isProcessing = useAgentStore((s) => s.isProcessing)
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
      } else if (e.key === 'Escape') {
        setSettingsOpen(false)
        setPaletteOpen(false)
        setChangesOpen(false)
        setSelectedNode(null)
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [cycleViewMode, handleNewConversation])

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
    // Git slash commands — stubs until IPC is wired
    if (['/commit', '/push', '/pull', '/branch'].includes(text)) {
      useAgentStore.getState().addStatusMessage(`${text} — git integration coming soon`)
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

  const showChat = viewMode === 'split' || viewMode === 'chat'
  const showGraph = viewMode === 'split' || viewMode === 'graph'

  const sidebarOpen = activePanel !== null

  return (
    <div className="flex flex-col h-screen bg-gray-950 text-gray-100">
      {/* Toolbar */}
      <div className="flex items-center justify-between px-4 py-2 bg-gray-900 border-b border-gray-800 shrink-0">
        <div className="flex items-center gap-3">
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
                <FileTree onFileSelect={handleFileSelect} />
              )}
              {activePanel === 'search' && (
                <SearchPanel />
              )}
              {activePanel === 'vcs' && (
                <VcsPanel />
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
      <StatusBar connected={false} viewMode={viewMode} />

      {/* Command Palette (Cmd+K) */}
      {paletteOpen && (
        <CommandPalette onClose={() => setPaletteOpen(false)} />
      )}

      {/* Settings modal (Cmd+Shift+P) */}
      {settingsOpen && (
        <SettingsModal onClose={() => setSettingsOpen(false)} />
      )}

      {/* Changes panel */}
      {changesOpen && (
        <ChangesPanel onClose={() => setChangesOpen(false)} />
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

function CommandPalette({ onClose }: { onClose: () => void }) {
  const [query, setQuery] = useState('')

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
          <div className="text-xs text-gray-500 px-2 py-1.5 uppercase tracking-wide">
            Conversations
          </div>
          <div className="text-sm text-gray-400 px-2 py-6 text-center">
            No conversations yet
          </div>
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

function VcsPanel() {
  return (
    <div className="p-3 h-full flex flex-col">
      <div className="text-[11px] font-semibold text-gray-500 uppercase tracking-wide mb-2">
        Source Control
      </div>
      <div className="flex-1 flex items-center justify-center">
        <p className="text-xs text-gray-600 text-center px-4">
          Git integration coming soon
        </p>
      </div>
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
