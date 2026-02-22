import { create } from 'zustand'

/* ------------------------------------------------------------------ */
/*  Theme tokens                                                       */
/* ------------------------------------------------------------------ */

export interface ThemeTokens {
  primary: string
  secondary: string
  accent: string
  background: string
  surface: string
  text: string
  'text-secondary': string
  border: string
  success: string
  warning: string
  error: string
}

const darkTokens: ThemeTokens = {
  primary: '#2563eb',
  secondary: '#6366f1',
  accent: '#3b82f6',
  background: '#030712',
  surface: '#111827',
  text: '#f3f4f6',
  'text-secondary': '#9ca3af',
  border: '#1f2937',
  success: '#22c55e',
  warning: '#eab308',
  error: '#ef4444',
}

const lightTokens: ThemeTokens = {
  primary: '#2563eb',
  secondary: '#6366f1',
  accent: '#3b82f6',
  background: '#ffffff',
  surface: '#f9fafb',
  text: '#111827',
  'text-secondary': '#6b7280',
  border: '#e5e7eb',
  success: '#16a34a',
  warning: '#ca8a04',
  error: '#dc2626',
}

/* ------------------------------------------------------------------ */
/*  Tool display mode                                                  */
/* ------------------------------------------------------------------ */

export type ToolDisplayMode = 'collapsible' | 'inline' | 'hidden'

/* ------------------------------------------------------------------ */
/*  Tool definition                                                    */
/* ------------------------------------------------------------------ */

export interface ToolDef {
  name: string
  description: string
  source: 'built-in' | string
  status: 'available' | 'disabled' | 'error'
  permission: 'auto' | 'confirm' | 'always-ask'
  usageCount: number
}

const BUILTIN_TOOLS: ToolDef[] = [
  { name: 'read_file', description: 'Read file contents', source: 'built-in', status: 'available', permission: 'auto', usageCount: 0 },
  { name: 'write_file', description: 'Write or create files', source: 'built-in', status: 'available', permission: 'confirm', usageCount: 0 },
  { name: 'grep', description: 'Search file contents', source: 'built-in', status: 'available', permission: 'auto', usageCount: 0 },
  { name: 'glob', description: 'Find files by pattern', source: 'built-in', status: 'available', permission: 'auto', usageCount: 0 },
  { name: 'terminal', description: 'Execute shell commands', source: 'built-in', status: 'available', permission: 'confirm', usageCount: 0 },
  { name: 'git', description: 'Git operations', source: 'built-in', status: 'available', permission: 'confirm', usageCount: 0 },
]

/* ------------------------------------------------------------------ */
/*  Tool permission defaults (Phase 7)                                 */
/* ------------------------------------------------------------------ */

const DEFAULT_TOOL_PERMISSIONS: Record<string, 'auto' | 'confirm' | 'always-ask'> = {
  read_file: 'auto',
  list_dir: 'auto',
  grep_search: 'auto',
  file_search: 'auto',
  create_file: 'confirm',
  insert_edit_into_file: 'confirm',
  create_directory: 'confirm',
  run_in_terminal: 'confirm',
}

/* ------------------------------------------------------------------ */
/*  Color mode                                                         */
/* ------------------------------------------------------------------ */

export type ColorMode = 'system' | 'light' | 'dark'

/* ------------------------------------------------------------------ */
/*  Store                                                              */
/* ------------------------------------------------------------------ */

interface SettingsState {
  // Connection
  model: string
  proxyUrl: string | null
  caCertPath: string | null
  setModel: (model: string) => void
  setProxyUrl: (url: string | null) => void
  setCaCertPath: (path: string | null) => void

  // Theme
  colorMode: ColorMode
  resolvedDark: boolean
  tokens: ThemeTokens
  setColorMode: (mode: ColorMode) => void
  setToken: (key: keyof ThemeTokens, value: string) => void
  resetTokens: () => void

  // Chat
  toolDisplayMode: ToolDisplayMode
  setToolDisplayMode: (mode: ToolDisplayMode) => void

  // Tools
  tools: ToolDef[]
  incrementToolUsage: (toolName: string) => void

  // Tool permissions (Phase 7)
  toolPermissions: Record<string, 'auto' | 'confirm' | 'always-ask'>
  setToolPermission: (name: string, perm: 'auto' | 'confirm' | 'always-ask') => void
  getToolPermission: (name: string) => 'auto' | 'confirm' | 'always-ask'

  // Debug logging (Phase 7)
  debugLogging: boolean
  setDebugLogging: (v: boolean) => void

  // Project
  projectPath: string | null
  setProjectPath: (path: string) => void

  // Persistence (Phase 5)
  hydrated: boolean
  hydrateFromDisk: () => Promise<void>
  persistToDisk: () => void

  // Bidirectional sync (Phase 3)
  applyExternalSettings: (data: { config: Record<string, unknown>; theme: Record<string, unknown> }) => void
}

function resolveIsDark(mode: ColorMode): boolean {
  if (mode === 'dark') return true
  if (mode === 'light') return false
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? true
}

let persistTimer: ReturnType<typeof setTimeout> | null = null

function debouncePersist(state: SettingsState) {
  if (persistTimer) clearTimeout(persistTimer)
  persistTimer = setTimeout(() => state.persistToDisk(), 500)
}

export const useSettingsStore = create<SettingsState>((set, get) => ({
  model: 'gpt-4.1',
  proxyUrl: null,
  caCertPath: null,
  setModel: (model) => { set({ model }); debouncePersist(get()) },
  setProxyUrl: (proxyUrl) => { set({ proxyUrl }); debouncePersist(get()) },
  setCaCertPath: (caCertPath) => { set({ caCertPath }); debouncePersist(get()) },

  colorMode: 'system',
  resolvedDark: resolveIsDark('system'),
  tokens: { ...darkTokens },

  setColorMode: (mode) => {
    const isDark = resolveIsDark(mode)
    const baseTokens = isDark ? darkTokens : lightTokens
    set({ colorMode: mode, resolvedDark: isDark, tokens: { ...baseTokens } })
    applyTokens(baseTokens)
    debouncePersist(get())
  },

  setToken: (key, value) => {
    const tokens = { ...get().tokens, [key]: value }
    set({ tokens })
    applyTokens(tokens)
    debouncePersist(get())
  },

  resetTokens: () => {
    const baseTokens = get().resolvedDark ? darkTokens : lightTokens
    set({ tokens: { ...baseTokens } })
    applyTokens(baseTokens)
    debouncePersist(get())
  },

  toolDisplayMode: 'collapsible',
  setToolDisplayMode: (mode) => { set({ toolDisplayMode: mode }); debouncePersist(get()) },

  tools: [...BUILTIN_TOOLS],

  /** Increment usage counter for a tool by name (Phase 5, Task 7) */
  incrementToolUsage: (toolName) => {
    set((s) => ({
      tools: s.tools.map((t) =>
        t.name === toolName ? { ...t, usageCount: t.usageCount + 1 } : t,
      ),
    }))
  },

  toolPermissions: { ...DEFAULT_TOOL_PERMISSIONS },
  setToolPermission: (name, perm) => {
    set((s) => ({ toolPermissions: { ...s.toolPermissions, [name]: perm } }))
    debouncePersist(get())
  },
  getToolPermission: (name) => get().toolPermissions[name] ?? 'auto',

  debugLogging: false,
  setDebugLogging: (v) => { set({ debugLogging: v }); debouncePersist(get()) },

  projectPath: null,
  setProjectPath: (path) => { set({ projectPath: path }); debouncePersist(get()) },

  hydrated: false,

  hydrateFromDisk: async () => {
    if (get().hydrated) return
    try {
      const data = await window.api?.settings?.read()
      if (!data) { set({ hydrated: true }); return }

      const { config, theme } = data
      const updates: Partial<SettingsState> = { hydrated: true }

      if (config.model) updates.model = config.model as string
      if (config.proxyUrl !== undefined) updates.proxyUrl = config.proxyUrl as string | null
      if (config.caCertPath !== undefined) updates.caCertPath = config.caCertPath as string | null
      if (config.colorMode) updates.colorMode = config.colorMode as ColorMode
      if (config.toolDisplayMode) updates.toolDisplayMode = config.toolDisplayMode as ToolDisplayMode
      if (config.projectPath) updates.projectPath = config.projectPath as string
      if (config.debugLogging !== undefined) updates.debugLogging = config.debugLogging as boolean
      if (config.toolPermissions) updates.toolPermissions = config.toolPermissions as Record<string, 'auto' | 'confirm' | 'always-ask'>

      if (theme && Object.keys(theme).length > 0) {
        updates.tokens = { ...darkTokens, ...theme } as ThemeTokens
      }

      set(updates as any)

      // Resolve dark mode and apply tokens
      if (updates.colorMode) {
        const isDark = resolveIsDark(updates.colorMode)
        set({ resolvedDark: isDark })
      }
      applyTokens(get().tokens)
    } catch {
      set({ hydrated: true })
    }
  },

  persistToDisk: () => {
    const s = get()
    const config = {
      model: s.model,
      proxyUrl: s.proxyUrl,
      caCertPath: s.caCertPath,
      colorMode: s.colorMode,
      toolDisplayMode: s.toolDisplayMode,
      projectPath: s.projectPath,
      debugLogging: s.debugLogging,
      toolPermissions: s.toolPermissions,
    }
    const theme = { ...s.tokens } as Record<string, unknown>
    window.api?.settings?.write({ config, theme }).catch(() => {})
  },

  /* ---------------------------------------------------------------- */
  /*  Phase 3: Apply settings pushed from main process (fs.watch)      */
  /* ---------------------------------------------------------------- */

  applyExternalSettings: (data) => {
    const { config, theme } = data
    const updates: Partial<SettingsState> = {}

    if (config.model) updates.model = config.model as string
    if (config.proxyUrl !== undefined) updates.proxyUrl = config.proxyUrl as string | null
    if (config.caCertPath !== undefined) updates.caCertPath = config.caCertPath as string | null
    if (config.colorMode) {
      updates.colorMode = config.colorMode as ColorMode
      updates.resolvedDark = resolveIsDark(config.colorMode as ColorMode)
    }
    if (config.toolDisplayMode) updates.toolDisplayMode = config.toolDisplayMode as ToolDisplayMode
    if (config.debugLogging !== undefined) updates.debugLogging = config.debugLogging as boolean
    if (config.toolPermissions) updates.toolPermissions = config.toolPermissions as Record<string, 'auto' | 'confirm' | 'always-ask'>

    if (theme && Object.keys(theme).length > 0) {
      updates.tokens = { ...darkTokens, ...theme } as ThemeTokens
    }

    set(updates as any)
    applyTokens(get().tokens)
  },
}))

/* ------------------------------------------------------------------ */
/*  Apply tokens as CSS custom properties                              */
/* ------------------------------------------------------------------ */

function applyTokens(tokens: ThemeTokens): void {
  const root = document.documentElement
  for (const [key, value] of Object.entries(tokens)) {
    root.style.setProperty(`--color-${key}`, value)
  }
}

// Apply default tokens on load
applyTokens(darkTokens)

// Listen for OS color scheme changes
if (typeof window !== 'undefined') {
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    const store = useSettingsStore.getState()
    if (store.colorMode === 'system') {
      store.setColorMode('system')
    }
  })
}
