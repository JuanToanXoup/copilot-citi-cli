import { EventEmitter } from 'events'
import { existsSync, readFileSync } from 'fs'
import { join } from 'path'
import { StdioTransport } from './stdio-transport'

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

interface McpServerState {
  name: string
  status: 'connecting' | 'connected' | 'disconnected' | 'error'
  transport: StdioTransport | null
  tools: McpTool[]
  requestId: number
  pendingRequests: Map<number, { resolve: (v: any) => void; reject: (e: Error) => void }>
}

/**
 * Manages MCP server connections.
 * Reads .copilot/tools.json for server configs, starts stdio servers,
 * discovers tools, and forwards tool calls.
 */
export class McpManager extends EventEmitter {
  private servers = new Map<string, McpServerState>()
  private allTools: McpTool[] = []

  constructor(private workspaceRoot: string) {
    super()
  }

  /** Load server configs from .copilot/tools.json and start them */
  async initialize(): Promise<void> {
    const configPath = join(this.workspaceRoot, '.copilot', 'tools.json')
    if (!existsSync(configPath)) return

    try {
      const raw = JSON.parse(readFileSync(configPath, 'utf-8'))
      const servers: McpServerConfig[] = Array.isArray(raw.servers) ? raw.servers : []

      for (const config of servers) {
        if (config.type === 'stdio' && config.command) {
          await this.startStdioServer(config)
        }
        // SSE servers would be handled here
      }
    } catch (e: any) {
      this.emit('error', `Failed to load MCP config: ${e.message}`)
    }
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

  /** Shutdown all servers */
  shutdown(): void {
    for (const [, server] of this.servers) {
      server.transport?.stop()
    }
    this.servers.clear()
    this.allTools = []
  }

  private async startStdioServer(config: McpServerConfig): Promise<void> {
    const state: McpServerState = {
      name: config.name,
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
      this.emit('serverStatus', { name: config.name, status: 'error', error: err })
    })

    state.transport!.on('close', () => {
      state.status = 'disconnected'
      this.emit('serverStatus', { name: config.name, status: 'disconnected' })
    })

    state.transport!.start()

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
      this.emit('serverStatus', { name: config.name, status: 'connected', tools: state.tools })
      this.emit('toolsDiscovered', state.tools)
    } catch (e: any) {
      state.status = 'error'
      this.emit('serverStatus', { name: config.name, status: 'error', error: e.message })
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
