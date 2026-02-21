import { create } from 'zustand'
import type { AgentEvent } from '@shared/events'

interface AgentState {
  /** Current user prompt text */
  userPrompt: string
  /** Whether the agent is currently processing */
  isProcessing: boolean
  /** Accumulated lead agent text */
  leadText: string
  /** Error message if any */
  error: string | null

  // Actions
  setUserPrompt: (text: string) => void
  handleEvent: (event: AgentEvent) => void
  reset: () => void
}

export const useAgentStore = create<AgentState>((set) => ({
  userPrompt: '',
  isProcessing: false,
  leadText: '',
  error: null,

  setUserPrompt: (text) => set({ userPrompt: text }),

  handleEvent: (event) => {
    switch (event.type) {
      case 'lead:started':
        set({ isProcessing: true, leadText: '', error: null })
        break
      case 'lead:delta':
        set((s) => ({ leadText: s.leadText + event.text }))
        break
      case 'lead:done':
        set({ isProcessing: false, leadText: event.text })
        break
      case 'lead:error':
        set({ isProcessing: false, error: event.message })
        break
      case 'conversation:done':
        set({ isProcessing: false })
        break
    }
  },

  reset: () =>
    set({
      userPrompt: '',
      isProcessing: false,
      leadText: '',
      error: null,
    }),
}))
