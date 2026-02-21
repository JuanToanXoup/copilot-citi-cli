import { contextBridge, ipcRenderer } from 'electron'
import type { AgentEvent } from '@shared/events'
import {
  AGENT_MESSAGE,
  AGENT_CANCEL,
  AGENT_NEW_CONVERSATION,
  AGENT_EVENT,
} from '@shared/ipc-channels'

const api = {
  agent: {
    sendMessage: (text: string, model?: string) =>
      ipcRenderer.send(AGENT_MESSAGE, { text, model }),
    cancel: () => ipcRenderer.send(AGENT_CANCEL),
    newConversation: () => ipcRenderer.send(AGENT_NEW_CONVERSATION),
    onEvent: (cb: (event: AgentEvent) => void) => {
      const listener = (_: unknown, event: AgentEvent) => cb(event)
      ipcRenderer.on(AGENT_EVENT, listener)
      return () => {
        ipcRenderer.removeListener(AGENT_EVENT, listener)
      }
    },
  },
}

contextBridge.exposeInMainWorld('api', api)

export type ElectronApi = typeof api
