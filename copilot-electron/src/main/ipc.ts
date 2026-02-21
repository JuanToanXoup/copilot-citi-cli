import { ipcMain, BrowserWindow, dialog } from 'electron'
import fs from 'fs'
import path from 'path'
import {
  AGENT_MESSAGE,
  AGENT_CANCEL,
  AGENT_NEW_CONVERSATION,
  AGENT_EVENT,
  DIALOG_OPEN_DIRECTORY,
  FS_READ_DIRECTORY,
  FS_READ_FILE,
  FS_WRITE_FILE,
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

  ipcMain.handle(DIALOG_OPEN_DIRECTORY, async () => {
    const result = await dialog.showOpenDialog({
      properties: ['openDirectory'],
      title: 'Select Project Folder',
    })
    if (result.canceled || result.filePaths.length === 0) return null
    return result.filePaths[0]
  })

  ipcMain.handle(FS_READ_FILE, async (_event, filePath: string) => {
    try {
      return fs.readFileSync(filePath, 'utf-8')
    } catch {
      return null
    }
  })

  ipcMain.handle(FS_WRITE_FILE, async (_event, filePath: string, content: string) => {
    try {
      fs.writeFileSync(filePath, content, 'utf-8')
      return true
    } catch {
      return false
    }
  })

  ipcMain.handle(FS_READ_DIRECTORY, async (_event, dirPath: string) => {
    try {
      const entries = fs.readdirSync(dirPath, { withFileTypes: true })
      return entries
        .filter((e) => !e.name.startsWith('.') || e.name === '.copilot')
        .sort((a, b) => {
          // Directories first, then files, alphabetical within each
          if (a.isDirectory() && !b.isDirectory()) return -1
          if (!a.isDirectory() && b.isDirectory()) return 1
          return a.name.localeCompare(b.name)
        })
        .map((e) => ({
          name: e.name,
          path: path.join(dirPath, e.name),
          isDirectory: e.isDirectory(),
        }))
    } catch {
      return []
    }
  })
}

/** Send an agent event to the renderer process */
export function sendAgentEvent(event: AgentEvent): void {
  const windows = BrowserWindow.getAllWindows()
  for (const win of windows) {
    win.webContents.send(AGENT_EVENT, event)
  }
}
