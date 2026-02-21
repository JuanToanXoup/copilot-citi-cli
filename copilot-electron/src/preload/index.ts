import { contextBridge, ipcRenderer } from 'electron'
import type { AgentEvent } from '@shared/events'
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
  dialog: {
    openDirectory: (): Promise<string | null> =>
      ipcRenderer.invoke(DIALOG_OPEN_DIRECTORY),
  },
  fs: {
    readDirectory: (dirPath: string): Promise<Array<{ name: string; path: string; isDirectory: boolean }>> =>
      ipcRenderer.invoke(FS_READ_DIRECTORY, dirPath),
    readFile: (filePath: string): Promise<string | null> =>
      ipcRenderer.invoke(FS_READ_FILE, filePath),
    writeFile: (filePath: string, content: string): Promise<boolean> =>
      ipcRenderer.invoke(FS_WRITE_FILE, filePath, content),
  },
}

contextBridge.exposeInMainWorld('api', api)

export type ElectronApi = typeof api
