import { contextBridge, ipcRenderer } from 'electron'
import type { AgentEvent } from '@shared/events'
import {
  AGENT_MESSAGE,
  AGENT_CANCEL,
  AGENT_NEW_CONVERSATION,
  AGENT_EVENT,
  LSP_STATUS,
  DIALOG_OPEN_DIRECTORY,
  FS_READ_DIRECTORY,
  FS_READ_FILE,
  FS_WRITE_FILE,
  FILE_CHANGED,
  AUTH_DEVICE_FLOW_START,
  AUTH_DEVICE_FLOW_STATUS,
  AUTH_SIGN_OUT,
  SETTINGS_READ,
  SETTINGS_WRITE,
  CONVERSATION_SAVE,
  CONVERSATION_LIST,
  CONVERSATION_LOAD,
  TOOL_CONFIRM_REQUEST,
  TOOL_CONFIRM_RESPONSE,
  GIT_STATUS,
  GIT_BRANCH,
  GIT_BRANCHES,
  GIT_CHECKOUT,
  GIT_COMMIT,
  GIT_PUSH,
  GIT_PULL,
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
    onLspStatus: (cb: (status: { connected: boolean; user?: string }) => void) => {
      const listener = (_: unknown, status: { connected: boolean; user?: string }) => cb(status)
      ipcRenderer.on(LSP_STATUS, listener)
      return () => {
        ipcRenderer.removeListener(LSP_STATUS, listener)
      }
    },
    onFileChanged: (cb: (data: { filePath: string; action: string }) => void) => {
      const listener = (_: unknown, data: { filePath: string; action: string }) => cb(data)
      ipcRenderer.on(FILE_CHANGED, listener)
      return () => {
        ipcRenderer.removeListener(FILE_CHANGED, listener)
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
  auth: {
    startDeviceFlow: (): Promise<{ userCode: string; verificationUri: string } | null> =>
      ipcRenderer.invoke(AUTH_DEVICE_FLOW_START),
    onStatus: (cb: (status: { state: string; user?: string }) => void) => {
      const listener = (_: unknown, status: { state: string; user?: string }) => cb(status)
      ipcRenderer.on(AUTH_DEVICE_FLOW_STATUS, listener)
      return () => {
        ipcRenderer.removeListener(AUTH_DEVICE_FLOW_STATUS, listener)
      }
    },
    signOut: (): Promise<void> => ipcRenderer.invoke(AUTH_SIGN_OUT),
  },
  settings: {
    read: (): Promise<{ config: Record<string, unknown>; theme: Record<string, unknown> } | null> =>
      ipcRenderer.invoke(SETTINGS_READ),
    write: (data: { config?: Record<string, unknown>; theme?: Record<string, unknown> }): Promise<void> =>
      ipcRenderer.invoke(SETTINGS_WRITE, data),
  },
  conversations: {
    save: (data: { id: string; messages: unknown[]; summary: string }): Promise<string> =>
      ipcRenderer.invoke(CONVERSATION_SAVE, data),
    list: (): Promise<Array<{ id: string; date: string; summary: string; path: string }>> =>
      ipcRenderer.invoke(CONVERSATION_LIST),
    load: (id: string): Promise<{ messages: unknown[]; summary: string } | null> =>
      ipcRenderer.invoke(CONVERSATION_LOAD, id),
  },
  tools: {
    onConfirmRequest: (cb: (req: { id: string; name: string; input: Record<string, unknown> }) => void) => {
      const listener = (_: unknown, req: { id: string; name: string; input: Record<string, unknown> }) => cb(req)
      ipcRenderer.on(TOOL_CONFIRM_REQUEST, listener)
      return () => {
        ipcRenderer.removeListener(TOOL_CONFIRM_REQUEST, listener)
      }
    },
    respondConfirm: (id: string, approved: boolean): void => {
      ipcRenderer.send(TOOL_CONFIRM_RESPONSE, { id, approved })
    },
  },
  git: {
    status: (): Promise<Array<{ file: string; status: string }>> =>
      ipcRenderer.invoke(GIT_STATUS),
    branch: (): Promise<string> =>
      ipcRenderer.invoke(GIT_BRANCH),
    branches: (): Promise<string[]> =>
      ipcRenderer.invoke(GIT_BRANCHES),
    checkout: (name: string, create?: boolean): Promise<string> =>
      ipcRenderer.invoke(GIT_CHECKOUT, name, create),
    commit: (message: string): Promise<string> =>
      ipcRenderer.invoke(GIT_COMMIT, message),
    push: (): Promise<string> =>
      ipcRenderer.invoke(GIT_PUSH),
    pull: (): Promise<string> =>
      ipcRenderer.invoke(GIT_PULL),
  },
}

contextBridge.exposeInMainWorld('api', api)

export type ElectronApi = typeof api
