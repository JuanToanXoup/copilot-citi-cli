import { create } from 'zustand'
import type { AgentEvent } from '@shared/events'

/* ------------------------------------------------------------------ */
/*  Message types for the chat panel                                   */
/* ------------------------------------------------------------------ */

export interface ChatMessage {
  id: string
  type: 'user' | 'agent' | 'tool' | 'subagent' | 'terminal' | 'error' | 'status'
  /** Corresponding node ID in the flow graph (for sync) */
  nodeId?: string
  text: string
  timestamp: number
  status?: 'running' | 'success' | 'error'
  /** For tool/subagent messages */
  meta?: {
    agentType?: string
    toolName?: string
    command?: string
  }
}

/* ------------------------------------------------------------------ */
/*  Store                                                              */
/* ------------------------------------------------------------------ */

interface AgentState {
  messages: ChatMessage[]
  isProcessing: boolean
  error: string | null

  // Actions
  addUserMessage: (text: string) => void
  addAgentDelta: (text: string) => void
  addSubagentMessage: (agentId: string, agentType: string, description: string) => void
  updateSubagentStatus: (agentId: string, status: 'success' | 'error') => void
  addSubagentDelta: (agentId: string, text: string) => void
  addToolMessage: (name: string) => void
  updateToolStatus: (name: string, status: 'success' | 'error') => void
  addStatusMessage: (text: string) => void
  addErrorMessage: (text: string) => void
  setProcessing: (v: boolean) => void
  handleEvent: (event: AgentEvent) => void
  reset: () => void
}

let msgCounter = 0
function nextId(): string {
  return `msg-${++msgCounter}`
}

export const useAgentStore = create<AgentState>((set, get) => ({
  messages: [],
  isProcessing: false,
  error: null,

  addUserMessage: (text) => {
    const turn = (get().messages.filter((m) => m.type === 'user').length + 1) + 1 // +1 for next turn
    set((s) => ({
      messages: [
        ...s.messages,
        {
          id: nextId(),
          type: 'user',
          nodeId: `user-${turn}`,
          text,
          timestamp: Date.now(),
        },
      ],
    }))
  },

  addAgentDelta: (text) => {
    set((s) => {
      const msgs = [...s.messages]
      const lastAgent = msgs.findLast((m) => m.type === 'agent' && m.status === 'running')
      if (lastAgent) {
        lastAgent.text += text
        return { messages: msgs }
      }
      // Create new agent message
      return {
        messages: [
          ...msgs,
          {
            id: nextId(),
            type: 'agent',
            text,
            timestamp: Date.now(),
            status: 'running',
          },
        ],
      }
    })
  },

  addSubagentMessage: (agentId, agentType, description) => {
    set((s) => ({
      messages: [
        ...s.messages,
        {
          id: nextId(),
          type: 'subagent',
          nodeId: `subagent-${agentId}`,
          text: description,
          timestamp: Date.now(),
          status: 'running',
          meta: { agentType },
        },
      ],
    }))
  },

  updateSubagentStatus: (agentId, status) => {
    set((s) => ({
      messages: s.messages.map((m) =>
        m.nodeId === `subagent-${agentId}` ? { ...m, status } : m,
      ),
    }))
  },

  addSubagentDelta: (agentId, text) => {
    set((s) => ({
      messages: s.messages.map((m) =>
        m.nodeId === `subagent-${agentId}` ? { ...m, text: m.text + '\n' + text } : m,
      ),
    }))
  },

  addToolMessage: (name) => {
    set((s) => ({
      messages: [
        ...s.messages,
        {
          id: nextId(),
          type: 'tool',
          text: name,
          timestamp: Date.now(),
          status: 'running',
          meta: { toolName: name },
        },
      ],
    }))
  },

  updateToolStatus: (name, status) => {
    set((s) => {
      const msgs = [...s.messages]
      const tool = msgs.findLast((m) => m.type === 'tool' && m.meta?.toolName === name && m.status === 'running')
      if (tool) tool.status = status
      return { messages: msgs }
    })
  },

  addStatusMessage: (text) => {
    set((s) => ({
      messages: [
        ...s.messages,
        { id: nextId(), type: 'status', text, timestamp: Date.now() },
      ],
    }))
  },

  addErrorMessage: (text) => {
    set((s) => ({
      messages: [
        ...s.messages,
        { id: nextId(), type: 'error', text, timestamp: Date.now() },
      ],
      error: text,
    }))
  },

  setProcessing: (v) => set({ isProcessing: v }),

  handleEvent: (event) => {
    const state = get()
    switch (event.type) {
      case 'lead:started':
        set({ isProcessing: true, error: null })
        break
      case 'lead:delta':
        state.addAgentDelta(event.text)
        break
      case 'lead:done':
        set((s) => ({
          isProcessing: false,
          messages: s.messages.map((m) =>
            m.type === 'agent' && m.status === 'running' ? { ...m, status: 'success' as const } : m,
          ),
        }))
        break
      case 'lead:error':
        state.addErrorMessage(event.message)
        set({ isProcessing: false })
        break
      case 'subagent:spawned':
        state.addSubagentMessage(event.agentId, event.agentType, event.description)
        break
      case 'subagent:delta':
        state.addSubagentDelta(event.agentId, event.text)
        break
      case 'subagent:completed':
        state.updateSubagentStatus(event.agentId, event.status)
        break
      case 'conversation:done':
        set({ isProcessing: false })
        break
    }
  },

  reset: () => {
    msgCounter = 0
    set({ messages: [], isProcessing: false, error: null })
  },
}))
