import { create } from 'zustand'

interface SettingsState {
  model: string
  proxyUrl: string | null
  setModel: (model: string) => void
  setProxyUrl: (url: string | null) => void
}

export const useSettingsStore = create<SettingsState>((set) => ({
  model: 'gpt-4.1',
  proxyUrl: null,
  setModel: (model) => set({ model }),
  setProxyUrl: (proxyUrl) => set({ proxyUrl }),
}))
