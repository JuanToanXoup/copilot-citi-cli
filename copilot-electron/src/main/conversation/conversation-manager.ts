import { EventEmitter } from 'events'
import { LspClient } from '../lsp/client'
import { findCopilotBinary } from '../lsp/binary'
import { readCopilotAuth } from '../lsp/auth'
import { ToolRouter } from '../tools/tool-router'
import { BUILT_IN_TOOL_SCHEMAS } from '../tools/tool-schemas'
import { McpManager } from '../mcp/mcp-manager'

/**
 * Manages LSP initialization and server request dispatch.
 * Handles the 10-step initialization sequence and routes tool calls.
 */
export class ConversationManager extends EventEmitter {
  readonly lspClient = new LspClient()
  private initialized = false
  private initPromise: Promise<void> | null = null
  private toolRouter = new ToolRouter()
  mcpManager: McpManager | null = null

  // Set these before calling ensureInitialized()
  workspaceRoot = process.cwd()
  projectName = 'project'

  /** Ensure the LSP client is initialized. Idempotent. */
  async ensureInitialized() {
    if (this.initialized) return
    if (this.initPromise) return this.initPromise
    this.initPromise = this.initialize()
    return this.initPromise
  }

  private async initialize() {
    // Step 1: Find binary
    const binaryPath = findCopilotBinary()
    if (!binaryPath) throw new Error('copilot-language-server binary not found')

    // Step 2: Read auth
    const auth = readCopilotAuth()
    if (!auth) throw new Error('GitHub Copilot auth not found in apps.json')

    // Step 3: Start LSP process
    this.lspClient.start(binaryPath)

    // Step 4: Register server request handler
    this.lspClient.onServerRequest((method, id, params) => {
      this.handleServerRequest(method, id, params)
    })

    // Step 5: Initialize handshake
    const rootUri = `file://${this.workspaceRoot}`
    await this.lspClient.sendRequest('initialize', {
      processId: process.pid,
      capabilities: {
        textDocumentSync: { openClose: true, change: 1, save: true },
        workspace: { workspaceFolders: true },
      },
      rootUri,
      workspaceFolders: [{ uri: rootUri, name: this.projectName }],
      clientInfo: { name: 'copilot-electron', version: '0.1.0' },
      initializationOptions: {
        editorInfo: { name: 'JetBrains-IC', version: '2025.2' },
        editorPluginInfo: { name: 'copilot-intellij', version: '1.420.0' },
        editorConfiguration: {},
        networkProxy: {},
        githubAppId: auth.appId,
      },
    })

    // Step 6: Send initialized notification
    this.lspClient.sendNotification('initialized', {})

    // Step 7: setEditorInfo
    await this.lspClient.sendRequest('setEditorInfo', {
      editorInfo: { name: 'JetBrains-IC', version: '2025.2' },
      editorPluginInfo: { name: 'copilot-intellij', version: '1.420.0' },
      editorConfiguration: {},
      networkProxy: {},
    })

    // Step 8: Check status — LSP reads apps.json on its own
    let statusResp = await this.lspClient.sendRequest('checkStatus', {})
    let status = statusResp.result?.status
    if (status !== 'OK' && status !== 'MaybeOk') {
      // Attempt device auth flow
      const initResp = await this.lspClient.sendRequest('signInInitiate', {})
      const userCode = initResp.result?.userCode
      const verificationUri = initResp.result?.verificationUri
      if (userCode && verificationUri) {
        console.log(`[auth] Please visit ${verificationUri} and enter code: ${userCode}`)
        this.emit('authRequired', { userCode, verificationUri })
        // Wait for user to complete auth (poll checkStatus)
        const authStart = Date.now()
        while (Date.now() - authStart < 120_000) {
          await new Promise(r => setTimeout(r, 2000))
          const confirmResp = await this.lspClient.sendRequest('signInConfirm', { userCode })
          if (confirmResp.result?.status === 'OK' || confirmResp.result?.status === 'MaybeOk') {
            status = confirmResp.result.status
            statusResp = confirmResp
            break
          }
        }
      }
      if (status !== 'OK' && status !== 'MaybeOk') {
        throw new Error(`Authentication failed: ${status}. Sign in via VS Code or JetBrains first.`)
      }
    }

    // Step 9: Wait for feature flags (up to 3s)
    const flagsStart = Date.now()
    while (Object.keys(this.lspClient.featureFlags).length === 0 && Date.now() - flagsStart < 3000) {
      await new Promise(r => setTimeout(r, 100))
    }

    // Step 10: Register built-in tools
    const allToolSchemas = [...BUILT_IN_TOOL_SCHEMAS]

    // Step 10b: Start MCP servers and discover additional tools
    try {
      this.mcpManager = new McpManager(this.workspaceRoot)

      this.mcpManager.on('serverStatus', (status) => {
        this.emit('mcpServerStatus', status)
      })

      this.mcpManager.on('toolsDiscovered', (tools) => {
        this.emit('mcpToolsDiscovered', tools)
      })

      await this.mcpManager.initialize()

      // Add MCP tools to the schema list for LSP registration
      for (const mcpTool of this.mcpManager.getTools()) {
        allToolSchemas.push({
          name: mcpTool.name,
          description: mcpTool.description,
          inputSchema: mcpTool.inputSchema,
        })
      }
    } catch (e: any) {
      console.error('[mcp] Failed to initialize MCP servers:', e.message)
      // Non-fatal — continue without MCP tools
    }

    await this.lspClient.sendRequest('conversation/registerTools', {
      tools: allToolSchemas,
    })

    this.initialized = true
    this.emit('status', { connected: true, user: statusResp.result?.user })
  }

  /** Handle server-to-client requests (tool calls, confirmations, etc.) */
  private async handleServerRequest(method: string, id: number, params: any) {
    switch (method) {
      case 'conversation/invokeClientToolConfirmation':
        this.lspClient.sendResponse(id, [{ result: 'accept' }, null])
        break

      case 'conversation/invokeClientTool': {
        const toolName = params.name ?? params.toolName ?? 'unknown'
        const toolInput = params.input ?? params.arguments ?? {}
        const callConvId = params.conversationId

        // Route to AgentService if it owns this conversation
        this.emit('toolCall', { id, name: toolName, input: toolInput, conversationId: callConvId })
        break
      }

      case 'copilot/watchedFiles':
        this.lspClient.sendResponse(id, { watchedFiles: [] })
        break

      case 'window/showMessageRequest':
        this.lspClient.sendResponse(id, null)
        break

      default:
        this.lspClient.sendResponse(id, null)
        break
    }
  }

  shutdown() {
    if (this.mcpManager) {
      this.mcpManager.shutdown()
      this.mcpManager = null
    }
    this.lspClient.shutdown()
    this.initialized = false
    this.initPromise = null
  }
}
