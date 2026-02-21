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
  setModel: (model: string) => void
  setProxyUrl: (url: string | null) => void

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

  // Project
  projectPath: string | null
  setProjectPath: (path: string) => void
}

function resolveIsDark(mode: ColorMode): boolean {
  if (mode === 'dark') return true
  if (mode === 'light') return false
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? true
}

export const useSettingsStore = create<SettingsState>((set, get) => ({
  model: 'gpt-4.1',
  proxyUrl: null,
  setModel: (model) => set({ model }),
  setProxyUrl: (proxyUrl) => set({ proxyUrl }),

  colorMode: 'system',
  resolvedDark: resolveIsDark('system'),
  tokens: { ...darkTokens },

  setColorMode: (mode) => {
    const isDark = resolveIsDark(mode)
    const baseTokens = isDark ? darkTokens : lightTokens
    set({ colorMode: mode, resolvedDark: isDark, tokens: { ...baseTokens } })
    applyTokens(baseTokens)
  },

  setToken: (key, value) => {
    const tokens = { ...get().tokens, [key]: value }
    set({ tokens })
    applyTokens(tokens)
  },

  resetTokens: () => {
    const baseTokens = get().resolvedDark ? darkTokens : lightTokens
    set({ tokens: { ...baseTokens } })
    applyTokens(baseTokens)
  },

  toolDisplayMode: 'collapsible',
  setToolDisplayMode: (mode) => set({ toolDisplayMode: mode }),

  tools: [...BUILTIN_TOOLS],

  projectPath: null,
  setProjectPath: (path) => set({ projectPath: path }),
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
