import { ipcMain, BrowserWindow } from 'electron'
import {
  AGENT_MESSAGE,
  AGENT_CANCEL,
  AGENT_NEW_CONVERSATION,
  AGENT_EVENT,
} from '@shared/ipc-channels'
import type { AgentEvent } from '@shared/events'

export function registerIpc(): void {
  ipcMain.on(AGENT_MESSAGE, (_event, payload: { text: string; model?: string }) => {
    // TODO: wire to agent-service.sendMessage()
    console.log('[ipc] agent:message', payload.text)
  })

  ipcMain.on(AGENT_CANCEL, () => {
    // TODO: wire to agent-service.cancel()
    console.log('[ipc] agent:cancel')
  })

  ipcMain.on(AGENT_NEW_CONVERSATION, () => {
    // TODO: wire to agent-service.newConversation()
    console.log('[ipc] agent:new-conversation')
  })
}

/** Send an agent event to the renderer process */
export function sendAgentEvent(event: AgentEvent): void {
  const windows = BrowserWindow.getAllWindows()
  for (const win of windows) {
    win.webContents.send(AGENT_EVENT, event)
  }
}
