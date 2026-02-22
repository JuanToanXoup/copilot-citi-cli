import { ipcMain, BrowserWindow, dialog, app } from 'electron'
import fs from 'fs'
import path from 'path'
import { execSync } from 'child_process'
import {
  AGENT_MESSAGE,
  AGENT_CANCEL,
  AGENT_NEW_CONVERSATION,
  AGENT_EVENT,
  DIALOG_OPEN_DIRECTORY,
  FS_READ_DIRECTORY,
  FS_READ_FILE,
  FS_WRITE_FILE,
  FILE_CHANGED,
  LSP_STATUS,
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
import type { AgentEvent } from '@shared/events'
import { ConversationManager } from './conversation/conversation-manager'
import { AgentService } from './agent/agent-service'

let conversationManager: ConversationManager | null = null
let agentService: AgentService | null = null

/** Pending tool confirmations: confirmId → resolve(approved) */
const pendingConfirms = new Map<string, (approved: boolean) => void>()
let confirmCounter = 0

function ensureServices() {
  if (conversationManager) return

  conversationManager = new ConversationManager()
  agentService = new AgentService(conversationManager)

  // Forward agent events → renderer
  agentService.on('event', (event: AgentEvent) => {
    sendAgentEvent(event)

    // Phase 11: Dock badge on conversation:done when unfocused
    if (event.type === 'conversation:done') {
      const win = BrowserWindow.getFocusedWindow()
      if (!win && app.dock) {
        app.dock.setBadge('1')
      }
    }
  })

  // Forward LSP status → renderer
  conversationManager.on('status', (status: { connected: boolean; user?: string }) => {
    const windows = BrowserWindow.getAllWindows()
    for (const win of windows) {
      win.webContents.send(LSP_STATUS, status)
    }
  })

  // Forward auth required event → renderer (Phase 4)
  conversationManager.on('authRequired', (data: { userCode: string; verificationUri: string }) => {
    const windows = BrowserWindow.getAllWindows()
    for (const win of windows) {
      win.webContents.send(AUTH_DEVICE_FLOW_STATUS, { state: 'waiting', ...data })
    }
  })

  // Forward MCP tool discovery → renderer (Phase 10)
  conversationManager.on('mcpToolsDiscovered', (tools: Array<{ name: string; description: string; serverName: string }>) => {
    // Broadcast discovered tools to renderer for settings-store update
    sendAgentEvent({ type: 'lead:delta', text: '' } as any) // noop, tools go through settings IPC
  })

  // Route tool calls from conversationManager → agentService with filter check
  conversationManager.on('toolCall', async ({ id, name, input, conversationId }) => {
    if (!agentService!.ownsConversation(conversationId)) {
      // Not owned by agent service — execute directly via tool router
      conversationManager!.lspClient.sendResponse(id, [
        { content: [{ value: `Tool ${name} not routed` }], status: 'error' },
        null,
      ])
      return
    }

    // Check tool filter
    if (!agentService!.isToolAllowedForConversation(conversationId, name)) {
      conversationManager!.lspClient.sendResponse(id, [
        { content: [{ value: `Tool ${name} is not allowed for this agent` }], status: 'error' },
        null,
      ])
      return
    }

    await agentService!.handleToolCall(id, name, input, conversationId)
  })
}

/** Get the workspace root from conversationManager or fallback to cwd */
function getWorkspaceRoot(): string {
  return conversationManager?.workspaceRoot ?? process.cwd()
}

/** Get the .copilot directory path */
function getCopilotDir(): string {
  const dir = path.join(getWorkspaceRoot(), '.copilot')
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })

  // Ensure .gitignore exists with logs/ entry
  const gitignorePath = path.join(dir, '.gitignore')
  if (!fs.existsSync(gitignorePath)) {
    fs.writeFileSync(gitignorePath, 'logs/\n', 'utf-8')
  }

  return dir
}

export function registerIpc(): void {
  // Phase 11: Clear dock badge on window focus
  app.on('browser-window-focus', () => {
    if (app.dock) app.dock.setBadge('')
  })

  ipcMain.on(AGENT_MESSAGE, (_event, payload: { text: string; model?: string }) => {
    ensureServices()
    agentService!.sendMessage(payload.text, payload.model)
  })

  ipcMain.on(AGENT_CANCEL, () => {
    if (agentService) agentService.cancel()
  })

  ipcMain.on(AGENT_NEW_CONVERSATION, () => {
    if (agentService) agentService.newConversation()
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

  /* ------------------------------------------------------------------ */
  /*  Phase 4: Auth device flow                                          */
  /* ------------------------------------------------------------------ */

  ipcMain.handle(AUTH_DEVICE_FLOW_START, async () => {
    try {
      ensureServices()
      await conversationManager!.ensureInitialized()
      return null // Already authenticated if ensureInitialized succeeds
    } catch {
      // Auth failed — conversationManager emits authRequired with userCode/verificationUri
      // Return null; the auth dialog is triggered by the lead:error event
      return null
    }
  })

  ipcMain.handle(AUTH_SIGN_OUT, async () => {
    // Clear the cached auth by removing apps.json entry (not implemented for safety)
    // Instead, just shutdown and re-init
    if (conversationManager) {
      conversationManager.shutdown()
      conversationManager = null
      agentService = null
    }
  })

  /* ------------------------------------------------------------------ */
  /*  Phase 5: Settings persistence                                      */
  /* ------------------------------------------------------------------ */

  ipcMain.handle(SETTINGS_READ, async () => {
    try {
      const copilotDir = getCopilotDir()
      const configPath = path.join(copilotDir, 'config.json')
      const themePath = path.join(copilotDir, 'theme.json')

      let config: Record<string, unknown> = {}
      let theme: Record<string, unknown> = {}

      if (fs.existsSync(configPath)) {
        config = JSON.parse(fs.readFileSync(configPath, 'utf-8'))
      }
      if (fs.existsSync(themePath)) {
        theme = JSON.parse(fs.readFileSync(themePath, 'utf-8'))
      }

      return { config, theme }
    } catch {
      return null
    }
  })

  ipcMain.handle(SETTINGS_WRITE, async (_event, data: { config?: Record<string, unknown>; theme?: Record<string, unknown> }) => {
    try {
      const copilotDir = getCopilotDir()
      if (data.config) {
        fs.writeFileSync(path.join(copilotDir, 'config.json'), JSON.stringify(data.config, null, 2), 'utf-8')
      }
      if (data.theme) {
        fs.writeFileSync(path.join(copilotDir, 'theme.json'), JSON.stringify(data.theme, null, 2), 'utf-8')
      }
    } catch {
      // Silently fail
    }
  })

  /* ------------------------------------------------------------------ */
  /*  Phase 6: Conversation persistence                                  */
  /* ------------------------------------------------------------------ */

  ipcMain.handle(CONVERSATION_SAVE, async (_event, data: { id: string; messages: unknown[]; summary: string }) => {
    try {
      const convDir = path.join(getCopilotDir(), 'conversations')
      if (!fs.existsSync(convDir)) fs.mkdirSync(convDir, { recursive: true })

      const date = new Date().toISOString().slice(0, 10)
      const slug = data.summary.slice(0, 40).replace(/[^a-zA-Z0-9]+/g, '-').toLowerCase()
      const filename = `${date}_${slug}.json`
      const filePath = path.join(convDir, filename)

      fs.writeFileSync(filePath, JSON.stringify({
        id: data.id,
        summary: data.summary,
        date,
        messages: data.messages,
        savedAt: new Date().toISOString(),
      }, null, 2), 'utf-8')

      return filePath
    } catch {
      return ''
    }
  })

  ipcMain.handle(CONVERSATION_LIST, async () => {
    try {
      const convDir = path.join(getCopilotDir(), 'conversations')
      if (!fs.existsSync(convDir)) return []

      const files = fs.readdirSync(convDir)
        .filter(f => f.endsWith('.json'))
        .sort()
        .reverse()

      return files.map(f => {
        try {
          const raw = JSON.parse(fs.readFileSync(path.join(convDir, f), 'utf-8'))
          return {
            id: raw.id ?? f,
            date: raw.date ?? f.slice(0, 10),
            summary: raw.summary ?? 'Untitled',
            path: path.join(convDir, f),
          }
        } catch {
          return { id: f, date: f.slice(0, 10), summary: 'Untitled', path: path.join(convDir, f) }
        }
      })
    } catch {
      return []
    }
  })

  ipcMain.handle(CONVERSATION_LOAD, async (_event, id: string) => {
    try {
      const convDir = path.join(getCopilotDir(), 'conversations')
      // id could be a filename or a conversation ID
      const files = fs.readdirSync(convDir).filter(f => f.endsWith('.json'))
      for (const f of files) {
        const filePath = path.join(convDir, f)
        const raw = JSON.parse(fs.readFileSync(filePath, 'utf-8'))
        if (raw.id === id || f === id) {
          return { messages: raw.messages ?? [], summary: raw.summary ?? 'Untitled' }
        }
      }
      return null
    } catch {
      return null
    }
  })

  /* ------------------------------------------------------------------ */
  /*  Phase 7: Tool confirmation                                         */
  /* ------------------------------------------------------------------ */

  ipcMain.on(TOOL_CONFIRM_RESPONSE, (_event, payload: { id: string; approved: boolean }) => {
    const resolve = pendingConfirms.get(payload.id)
    if (resolve) {
      pendingConfirms.delete(payload.id)
      resolve(payload.approved)
    }
  })

  /* ------------------------------------------------------------------ */
  /*  Phase 9: Git integration                                           */
  /* ------------------------------------------------------------------ */

  ipcMain.handle(GIT_STATUS, async () => {
    try {
      const cwd = getWorkspaceRoot()
      const output = execSync('git status --porcelain', { cwd, encoding: 'utf-8' }).trim()
      if (!output) return []
      return output.split('\n').map(line => ({
        status: line.slice(0, 2).trim(),
        file: line.slice(3),
      }))
    } catch {
      return []
    }
  })

  ipcMain.handle(GIT_BRANCH, async () => {
    try {
      const cwd = getWorkspaceRoot()
      return execSync('git rev-parse --abbrev-ref HEAD', { cwd, encoding: 'utf-8' }).trim()
    } catch {
      return ''
    }
  })

  ipcMain.handle(GIT_BRANCHES, async () => {
    try {
      const cwd = getWorkspaceRoot()
      const output = execSync('git branch --list', { cwd, encoding: 'utf-8' }).trim()
      return output.split('\n').map(b => b.replace(/^\*?\s+/, '').trim()).filter(Boolean)
    } catch {
      return []
    }
  })

  ipcMain.handle(GIT_CHECKOUT, async (_event, name: string, create?: boolean) => {
    try {
      const cwd = getWorkspaceRoot()
      const cmd = create ? `git checkout -b ${name}` : `git checkout ${name}`
      return execSync(cmd, { cwd, encoding: 'utf-8' }).trim()
    } catch (e: any) {
      return `Error: ${e.message}`
    }
  })

  ipcMain.handle(GIT_COMMIT, async (_event, message: string) => {
    try {
      const cwd = getWorkspaceRoot()
      execSync('git add -A', { cwd, encoding: 'utf-8' })
      return execSync(`git commit -m "${message.replace(/"/g, '\\"')}"`, { cwd, encoding: 'utf-8' }).trim()
    } catch (e: any) {
      return `Error: ${e.stderr ?? e.message}`
    }
  })

  ipcMain.handle(GIT_PUSH, async () => {
    try {
      const cwd = getWorkspaceRoot()
      return execSync('git push', { cwd, encoding: 'utf-8' }).trim()
    } catch (e: any) {
      return `Error: ${e.stderr ?? e.message}`
    }
  })

  ipcMain.handle(GIT_PULL, async () => {
    try {
      const cwd = getWorkspaceRoot()
      return execSync('git pull', { cwd, encoding: 'utf-8' }).trim()
    } catch (e: any) {
      return `Error: ${e.stderr ?? e.message}`
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

/** Send file changed event to renderer (Phase 3) */
export function sendFileChanged(filePath: string, action: 'created' | 'modified' | 'deleted'): void {
  const windows = BrowserWindow.getAllWindows()
  for (const win of windows) {
    win.webContents.send(FILE_CHANGED, { filePath, action })
  }
}

/** Request tool confirmation from renderer (Phase 7) */
export function requestToolConfirm(name: string, input: Record<string, unknown>): Promise<boolean> {
  return new Promise((resolve) => {
    const id = `confirm-${++confirmCounter}`
    pendingConfirms.set(id, resolve)
    const windows = BrowserWindow.getAllWindows()
    for (const win of windows) {
      win.webContents.send(TOOL_CONFIRM_REQUEST, { id, name, input })
    }
    // Auto-approve after 60s timeout
    setTimeout(() => {
      if (pendingConfirms.has(id)) {
        pendingConfirms.delete(id)
        resolve(true)
      }
    }, 60_000)
  })
}

/** Append to debug log (Phase 7) */
export function appendToolLog(name: string, input: unknown, output: unknown, status: string): void {
  try {
    const root = conversationManager?.workspaceRoot ?? process.cwd()
    const logDir = path.join(root, '.copilot', 'logs')
    if (!fs.existsSync(logDir)) fs.mkdirSync(logDir, { recursive: true })

    const date = new Date().toISOString().slice(0, 10)
    const logPath = path.join(logDir, `${date}.log`)
    const entry = JSON.stringify({
      timestamp: new Date().toISOString(),
      tool: name,
      input,
      output: typeof output === 'string' ? output.slice(0, 500) : output,
      status,
    }) + '\n'

    fs.appendFileSync(logPath, entry, 'utf-8')
  } catch {
    // Silently fail
  }
}
