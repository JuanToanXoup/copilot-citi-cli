import { EventEmitter } from 'events'
import { existsSync, readFileSync, writeFileSync, watch, type FSWatcher } from 'fs'
import { join } from 'path'
import { StdioTransport } from './stdio-transport'
import { SseTransport } from './sse-transport'

interface McpServerConfig {
  name: string
  type: 'stdio' | 'sse'
  command?: string
  args?: string[]
  env?: Record<string, string>
  url?: string
}

interface McpTool {
  name: string
  description: string
  inputSchema: Record<string, unknown>
  serverName: string
}

export interface McpServerInfo {
  name: string
  type: 'stdio' | 'sse'
  status: 'connecting' | 'connected' | 'disconnected' | 'error'
  toolCount: number
  error?: string
}

interface McpServerState {
  name: string
  type: 'stdio' | 'sse'
  config: McpServerConfig
  status: 'connecting' | 'connected' | 'disconnected' | 'error'
  transport: StdioTransport | SseTransport | null
  tools: McpTool[]
  requestId: number
  pendingRequests: Map<number, { resolve: (v: any) => void; reject: (e: Error) => void }>
  error?: string
}

/**
 * Manages MCP server connections.
 * Reads .copilot/tools.json for server configs, starts stdio/sse servers,
 * discovers tools, and forwards tool calls.
 */
export class McpManager extends EventEmitter {
  private servers = new Map<string, McpServerState>()
  private allTools: McpTool[] = []
  private configWatcher: FSWatcher | null = null
  private reloadTimer: ReturnType<typeof setTimeout> | null = null

  constructor(private workspaceRoot: string) {
    super()
  }

  /** Load server configs from .copilot/tools.json and start them */
  async initialize(): Promise<void> {
    const configPath = this.getConfigPath()
    if (!existsSync(configPath)) return

    try {
      const raw = JSON.parse(readFileSync(configPath, 'utf-8'))
      const servers: McpServerConfig[] = Array.isArray(raw.servers) ? raw.servers : []

      for (const config of servers) {
        if (config.type === 'stdio' && config.command) {
          await this.startStdioServer(config)
        } else if (config.type === 'sse' && config.url) {
          await this.startSseServer(config)
        }
      }
    } catch (e: any) {
      this.emit('error', `Failed to load MCP config: ${e.message}`)
    }

    // Watch tools.json for changes
    this.watchConfig()
  }

  /** Get all discovered tools across all servers */
  getTools(): McpTool[] {
    return this.allTools
  }

  /** Execute a tool call on the appropriate server */
  async callTool(toolName: string, input: Record<string, unknown>): Promise<string> {
    const tool = this.allTools.find(t => t.name === toolName)
    if (!tool) throw new Error(`MCP tool not found: ${toolName}`)

    const server = this.servers.get(tool.serverName)
    if (!server || !server.transport) throw new Error(`MCP server not connected: ${tool.serverName}`)

    const result = await this.sendRequest(server, 'tools/call', {
      name: toolName,
      arguments: input,
    })

    // Extract text content from result
    if (Array.isArray(result.content)) {
      return result.content
        .filter((c: any) => c.type === 'text')
        .map((c: any) => c.text)
        .join('\n')
    }

    return JSON.stringify(result)
  }

  /* ------------------------------------------------------------------ */
  /*  Public management methods (Phase 5)                                */
  /* ------------------------------------------------------------------ */

  /** Add a new MCP server, persist to tools.json, and start it */
  async addServer(config: McpServerConfig): Promise<void> {
    // Persist to tools.json
    const configPath = this.getConfigPath()
    let raw: { servers: McpServerConfig[] } = { servers: [] }
    if (existsSync(configPath)) {
      try {
        raw = JSON.parse(readFileSync(configPath, 'utf-8'))
        if (!Array.isArray(raw.servers)) raw.servers = []
      } catch {
        raw = { servers: [] }
      }
    }

    // Remove existing server with same name
    raw.servers = raw.servers.filter(s => s.name !== config.name)
    raw.servers.push(config)
    this.writeConfig(raw)

    // Start the server
    if (config.type === 'stdio' && config.command) {
      await this.startStdioServer(config)
    } else if (config.type === 'sse' && config.url) {
      await this.startSseServer(config)
    }
  }

  /** Remove an MCP server by name, disconnect it, and update tools.json */
  async removeServer(name: string): Promise<void> {
    // Disconnect if running
    this.disconnectServerInternal(name)

    // Remove tools from this server
    this.allTools = this.allTools.filter(t => t.serverName !== name)
    this.servers.delete(name)

    // Remove from config
    const configPath = this.getConfigPath()
    if (existsSync(configPath)) {
      try {
        const raw = JSON.parse(readFileSync(configPath, 'utf-8'))
        if (Array.isArray(raw.servers)) {
          raw.servers = raw.servers.filter((s: McpServerConfig) => s.name !== name)
          this.writeConfig(raw)
        }
      } catch {
        // Config is corrupt, ignore
      }
    }

    this.emit('serverStatus', { name, status: 'disconnected' })
    this.emit('toolsChanged', this.allTools)
  }

  /** Disconnect a server (keep config, stop transport) */
  async disconnectServer(name: string): Promise<void> {
    this.disconnectServerInternal(name)
    this.emit('serverStatus', { name, status: 'disconnected' })
  }

  /** Reconnect a previously disconnected server */
  async reconnectServer(name: string): Promise<void> {
    const server = this.servers.get(name)
    if (!server) throw new Error(`Unknown server: ${name}`)

    // Stop existing transport if any
    server.transport?.stop()
    server.tools = []
    this.allTools = this.allTools.filter(t => t.serverName !== name)

    // Re-start based on config
    if (server.config.type === 'stdio' && server.config.command) {
      // Remove old state first
      this.servers.delete(name)
      await this.startStdioServer(server.config)
    } else if (server.config.type === 'sse' && server.config.url) {
      this.servers.delete(name)
      await this.startSseServer(server.config)
    }
  }

  /** Get list of all servers with their status */
  getServerList(): McpServerInfo[] {
    const list: McpServerInfo[] = []
    for (const [, server] of this.servers) {
      list.push({
        name: server.name,
        type: server.type,
        status: server.status,
        toolCount: server.tools.length,
        error: server.error,
      })
    }
    return list
  }

  /** Shutdown all servers */
  shutdown(): void {
    if (this.configWatcher) {
      this.configWatcher.close()
      this.configWatcher = null
    }
    if (this.reloadTimer) {
      clearTimeout(this.reloadTimer)
      this.reloadTimer = null
    }
    for (const [, server] of this.servers) {
      server.transport?.stop()
    }
    this.servers.clear()
    this.allTools = []
  }

  /* ------------------------------------------------------------------ */
  /*  Private: server startup                                            */
  /* ------------------------------------------------------------------ */

  private async startStdioServer(config: McpServerConfig): Promise<void> {
    const state: McpServerState = {
      name: config.name,
      type: 'stdio',
      config,
      status: 'connecting',
      transport: new StdioTransport(config.command!, config.args ?? [], config.env ?? {}),
      tools: [],
      requestId: 0,
      pendingRequests: new Map(),
    }

    this.servers.set(config.name, state)

    state.transport!.on('message', (msg: any) => {
      this.handleMessage(config.name, msg)
    })

    state.transport!.on('error', (err: string) => {
      state.status = 'error'
      state.error = err
      this.emit('serverStatus', { name: config.name, status: 'error', error: err })
    })

    state.transport!.on('close', () => {
      state.status = 'disconnected'
      this.emit('serverStatus', { name: config.name, status: 'disconnected' })
    })

    state.transport!.start()
    await this.performHandshake(state, config)
  }

  /** Start an SSE-based MCP server */
  private async startSseServer(config: McpServerConfig): Promise<void> {
    const state: McpServerState = {
      name: config.name,
      type: 'sse',
      config,
      status: 'connecting',
      transport: new SseTransport(config.url!),
      tools: [],
      requestId: 0,
      pendingRequests: new Map(),
    }

    this.servers.set(config.name, state)

    state.transport!.on('message', (msg: any) => {
      this.handleMessage(config.name, msg)
    })

    state.transport!.on('error', (err: string) => {
      state.status = 'error'
      state.error = err
      this.emit('serverStatus', { name: config.name, status: 'error', error: err })
    })

    state.transport!.on('close', () => {
      state.status = 'disconnected'
      this.emit('serverStatus', { name: config.name, status: 'disconnected' })
    })

    ;(state.transport as SseTransport).start()
    await this.performHandshake(state, config)
  }

  /** Common MCP handshake: initialize, send initialized, discover tools */
  private async performHandshake(state: McpServerState, config: McpServerConfig): Promise<void> {
    try {
      // Initialize handshake
      await this.sendRequest(state, 'initialize', {
        protocolVersion: '2024-11-05',
        capabilities: {},
        clientInfo: { name: 'copilot-electron', version: '0.1.0' },
      })

      // Send initialized notification
      state.transport!.send({ jsonrpc: '2.0', method: 'notifications/initialized' })

      // Discover tools
      const toolsResult = await this.sendRequest(state, 'tools/list', {})
      if (Array.isArray(toolsResult.tools)) {
        state.tools = toolsResult.tools.map((t: any) => ({
          name: t.name,
          description: t.description ?? '',
          inputSchema: t.inputSchema ?? {},
          serverName: config.name,
        }))
        this.allTools.push(...state.tools)
      }

      state.status = 'connected'
      state.error = undefined
      this.emit('serverStatus', { name: config.name, status: 'connected', tools: state.tools })
      this.emit('toolsDiscovered', state.tools)
    } catch (e: any) {
      state.status = 'error'
      state.error = e.message
      this.emit('serverStatus', { name: config.name, status: 'error', error: e.message })
    }
  }

  /* ------------------------------------------------------------------ */
  /*  Private: config file watching (Phase 5, Task 6)                    */
  /* ------------------------------------------------------------------ */

  private getConfigPath(): string {
    return join(this.workspaceRoot, '.copilot', 'tools.json')
  }

  private writeConfig(raw: { servers: McpServerConfig[] }): void {
    const configPath = this.getConfigPath()
    const dir = join(this.workspaceRoot, '.copilot')
    const { existsSync: exists, mkdirSync } = require('fs')
    if (!exists(dir)) mkdirSync(dir, { recursive: true })
    writeFileSync(configPath, JSON.stringify(raw, null, 2), 'utf-8')
  }

  /** Watch .copilot/tools.json for external changes with debounced reload */
  private watchConfig(): void {
    const configPath = this.getConfigPath()
    if (!existsSync(configPath)) return

    try {
      this.configWatcher = watch(configPath, () => {
        // Debounce: wait 500ms after last change before reloading
        if (this.reloadTimer) clearTimeout(this.reloadTimer)
        this.reloadTimer = setTimeout(() => {
          this.reloadConfig().catch((e) => {
            this.emit('error', `Failed to reload MCP config: ${e.message}`)
          })
        }, 500)
      })
    } catch {
      // Watching not supported on this FS
    }
  }

  /** Reload config: stop removed servers, start new ones */
  private async reloadConfig(): Promise<void> {
    const configPath = this.getConfigPath()
    if (!existsSync(configPath)) return

    try {
      const raw = JSON.parse(readFileSync(configPath, 'utf-8'))
      const configs: McpServerConfig[] = Array.isArray(raw.servers) ? raw.servers : []
      const configNames = new Set(configs.map(c => c.name))

      // Stop servers that were removed from config
      for (const [name] of this.servers) {
        if (!configNames.has(name)) {
          this.disconnectServerInternal(name)
          this.allTools = this.allTools.filter(t => t.serverName !== name)
          this.servers.delete(name)
          this.emit('serverStatus', { name, status: 'disconnected' })
        }
      }

      // Start new servers that aren't running yet
      for (const config of configs) {
        if (!this.servers.has(config.name)) {
          if (config.type === 'stdio' && config.command) {
            await this.startStdioServer(config)
          } else if (config.type === 'sse' && config.url) {
            await this.startSseServer(config)
          }
        }
      }

      this.emit('toolsChanged', this.allTools)
    } catch (e: any) {
      this.emit('error', `Failed to reload MCP config: ${e.message}`)
    }
  }

  /* ------------------------------------------------------------------ */
  /*  Private: messaging                                                 */
  /* ------------------------------------------------------------------ */

  private disconnectServerInternal(name: string): void {
    const server = this.servers.get(name)
    if (server?.transport) {
      server.transport.stop()
      server.status = 'disconnected'
      // Reject pending requests
      for (const [, pending] of server.pendingRequests) {
        pending.reject(new Error('Server disconnected'))
      }
      server.pendingRequests.clear()
    }
  }

  private sendRequest(server: McpServerState, method: string, params: Record<string, unknown>): Promise<any> {
    return new Promise((resolve, reject) => {
      const id = ++server.requestId
      server.pendingRequests.set(id, { resolve, reject })
      server.transport!.send({ jsonrpc: '2.0', id, method, params })

      // Timeout after 30s
      setTimeout(() => {
        if (server.pendingRequests.has(id)) {
          server.pendingRequests.delete(id)
          reject(new Error(`MCP request timeout: ${method}`))
        }
      }, 30_000)
    })
  }

  private handleMessage(serverName: string, msg: any): void {
    const server = this.servers.get(serverName)
    if (!server) return

    // Response to a request
    if (msg.id != null && server.pendingRequests.has(msg.id)) {
      const pending = server.pendingRequests.get(msg.id)!
      server.pendingRequests.delete(msg.id)
      if (msg.error) {
        pending.reject(new Error(msg.error.message ?? 'MCP error'))
      } else {
        pending.resolve(msg.result ?? {})
      }
      return
    }

    // Server notification
    if (msg.method) {
      this.emit('notification', { serverName, method: msg.method, params: msg.params })
    }
  }
}
