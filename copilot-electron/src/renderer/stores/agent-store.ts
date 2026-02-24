import { create } from 'zustand'
import type { AgentEvent } from '@shared/events'
import { useSettingsStore } from './settings-store'

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
    input?: Record<string, unknown>
    output?: string
    prompt?: string
    startTime?: number
    duration?: number
  }
}

/* ------------------------------------------------------------------ */
/*  File change tracking (Phase 3)                                     */
/* ------------------------------------------------------------------ */

export interface FileChange {
  path: string
  action: 'created' | 'modified' | 'deleted'
}

/* ------------------------------------------------------------------ */
/*  Store                                                              */
/* ------------------------------------------------------------------ */

interface AgentState {
  messages: ChatMessage[]
  isProcessing: boolean
  error: string | null
  lastUserMessage: string | null
  changedFiles: FileChange[]

  // Actions
  addUserMessage: (text: string) => void
  addAgentDelta: (text: string) => void
  addSubagentMessage: (agentId: string, agentType: string, description: string, prompt?: string) => void
  updateSubagentStatus: (agentId: string, status: 'success' | 'error', result?: string) => void
  addSubagentDelta: (agentId: string, text: string) => void
  addToolMessage: (name: string, nodeId?: string, input?: Record<string, unknown>) => void
  updateToolStatus: (name: string, status: 'success' | 'error', nodeId?: string, output?: string) => void
  addTerminalMessage: (command: string, nodeId?: string) => void
  updateTerminalStatus: (nodeId: string, status: 'success' | 'error') => void
  addStatusMessage: (text: string) => void
  addErrorMessage: (text: string) => void
  setProcessing: (v: boolean) => void
  handleEvent: (event: AgentEvent) => void
  retryLast: () => void
  addFileChange: (filePath: string, action: 'created' | 'modified' | 'deleted') => void
  clearChanges: () => void
  acceptChange: (filePath: string) => void
  rejectChange: (filePath: string) => void
  loadConversation: (messages: ChatMessage[]) => void
  getSerializableMessages: () => ChatMessage[]
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
  lastUserMessage: null,
  changedFiles: [],

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
      lastUserMessage: text,
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

  addSubagentMessage: (agentId, agentType, description, prompt?) => {
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
          meta: { agentType, prompt, output: '' },
        },
      ],
    }))
  },

  updateSubagentStatus: (agentId, status, result?) => {
    set((s) => ({
      messages: s.messages.map((m) =>
        m.nodeId === `subagent-${agentId}`
          ? {
              ...m,
              status,
              ...(result != null && { meta: { ...m.meta, output: result } }),
            }
          : m,
      ),
    }))
  },

  addSubagentDelta: (agentId, text) => {
    set((s) => ({
      messages: s.messages.map((m) =>
        m.nodeId === `subagent-${agentId}`
          ? { ...m, meta: { ...m.meta, output: (m.meta?.output ?? '') + text } }
          : m,
      ),
    }))
  },

  addToolMessage: (name, nodeId?, input?) => {
    set((s) => ({
      messages: [
        ...s.messages,
        {
          id: nextId(),
          type: 'tool',
          nodeId,
          text: name,
          timestamp: Date.now(),
          status: 'running',
          meta: { toolName: name, input, startTime: Date.now() },
        },
      ],
    }))
  },

  updateToolStatus: (name, status, nodeId?, output?) => {
    set((s) => {
      const msgs = [...s.messages]
      // Match by nodeId first (more reliable), fall back to name
      const tool = nodeId
        ? msgs.find((m) => m.nodeId === nodeId && m.status === 'running')
        : msgs.findLast((m) => m.type === 'tool' && m.meta?.toolName === name && m.status === 'running')
      if (tool) {
        tool.status = status
        if (output && tool.meta) tool.meta.output = output
        if (tool.meta?.startTime) {
          tool.meta.duration = Date.now() - tool.meta.startTime
        }
      }

      // Phase 5, Task 7: Increment tool usage count on success
      if (status === 'success') {
        useSettingsStore.getState().incrementToolUsage(name)
      }

      return { messages: msgs }
    })
  },

  addTerminalMessage: (command, nodeId?) => {
    set((s) => ({
      messages: [
        ...s.messages,
        {
          id: nextId(),
          type: 'terminal',
          nodeId,
          text: command,
          timestamp: Date.now(),
          status: 'running',
          meta: { command, startTime: Date.now() },
        },
      ],
    }))
  },

  updateTerminalStatus: (nodeId, status) => {
    set((s) => ({
      messages: s.messages.map((m) =>
        m.nodeId === nodeId && m.type === 'terminal' ? { ...m, status } : m,
      ),
    }))
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
        state.addSubagentMessage(event.agentId, event.agentType, event.description, event.prompt)
        break
      case 'subagent:delta':
        state.addSubagentDelta(event.agentId, event.text)
        break
      case 'subagent:completed':
        state.updateSubagentStatus(event.agentId, event.status, event.result)
        break
      case 'subagent:retrying':
        state.addStatusMessage(`Retrying subagent ${event.agentId}...`)
        break
      case 'conversation:done': {
        set({ isProcessing: false })
        // Auto-save conversation (Phase 6)
        const msgs = get().messages
        if (msgs.length > 0 && window.api?.conversations) {
          const firstUser = msgs.find(m => m.type === 'user')
          const summary = firstUser?.text.slice(0, 80) ?? 'Conversation'
          const convId = `conv-${Date.now()}`
          window.api.conversations.save({ id: convId, messages: msgs, summary }).catch(() => {})
        }
        break
      }
      case 'file:changed':
        state.addFileChange(event.filePath, event.action)
        break
      case 'status':
        state.addStatusMessage(event.text)
        break
    }
  },

  retryLast: () => {
    const msg = get().lastUserMessage
    if (msg) {
      window.api?.agent.sendMessage(msg, 'default')
      set({ isProcessing: true, error: null })
    }
  },

  addFileChange: (filePath, action) => {
    set((s) => {
      const existing = s.changedFiles.findIndex(f => f.path === filePath)
      if (existing >= 0) {
        const next = [...s.changedFiles]
        next[existing] = { path: filePath, action }
        return { changedFiles: next }
      }
      return { changedFiles: [...s.changedFiles, { path: filePath, action }] }
    })
  },

  clearChanges: () => set({ changedFiles: [] }),

  acceptChange: (filePath) => {
    set((s) => ({
      changedFiles: s.changedFiles.filter(f => f.path !== filePath),
    }))
  },

  rejectChange: (filePath) => {
    // TODO: revert file using git checkout
    set((s) => ({
      changedFiles: s.changedFiles.filter(f => f.path !== filePath),
    }))
  },

  loadConversation: (messages) => {
    msgCounter = messages.length
    set({ messages, isProcessing: false, error: null })
  },

  getSerializableMessages: () => get().messages,

  reset: () => {
    msgCounter = 0
    set({ messages: [], isProcessing: false, error: null, lastUserMessage: null, changedFiles: [] })
  },
}))
