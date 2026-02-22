import { spawn, ChildProcess } from 'child_process'
import { EventEmitter } from 'events'
import { LspTransport } from './transport'

interface PendingRequest {
  resolve: (result: any) => void
  reject: (error: Error) => void
  timer: ReturnType<typeof setTimeout>
}

/**
 * JSON-RPC client for the copilot-language-server process.
 * Manages request/response matching, progress listener routing,
 * and server-to-client request dispatch.
 */
export class LspClient extends EventEmitter {
  private process: ChildProcess | null = null
  private transport: LspTransport | null = null
  private nextId = 0
  private pendingRequests = new Map<number, PendingRequest>()
  private progressListeners = new Map<string, (value: any) => void>()
  private _featureFlags: Record<string, any> = {}
  private serverRequestHandler: ((method: string, id: number, params: any) => void) | null = null

  get isRunning(): boolean {
    return this.process !== null && this.process.exitCode === null
  }

  get featureFlags(): Record<string, any> {
    return this._featureFlags
  }

  /** Spawn the LSP process and start reading messages. */
  start(binaryPath: string, env: Record<string, string> = {}) {
    this.process = spawn(binaryPath, ['--stdio'], {
      env: { ...process.env, ...env },
      stdio: ['pipe', 'pipe', 'pipe'],
    })

    this.transport = new LspTransport(this.process)
    this.transport.setMessageHandler((msg) => this.dispatchMessage(msg))

    // Drain stderr
    this.process.stderr?.on('data', () => {})

    this.process.on('exit', (code) => {
      this.emit('exit', code)
      this.rejectAllPending(new Error(`LSP process exited with code ${code}`))
    })
  }

  /** Send a request and wait for the response. */
  sendRequest(method: string, params?: any, timeoutMs = 120_000): Promise<any> {
    const id = ++this.nextId

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingRequests.delete(id)
        reject(new Error(`Request ${method} (id=${id}) timed out after ${timeoutMs}ms`))
      }, timeoutMs)

      this.pendingRequests.set(id, { resolve, reject, timer })

      this.transport!.send({
        jsonrpc: '2.0',
        id,
        method,
        params: params ?? {},
      })
    })
  }

  /** Send a notification (no response expected). */
  sendNotification(method: string, params?: any) {
    this.transport!.send({
      jsonrpc: '2.0',
      method,
      params: params ?? {},
    })
  }

  /** Send a response to a server-to-client request. */
  sendResponse(id: number, result: any) {
    this.transport!.send({
      jsonrpc: '2.0',
      id,
      result,
    })
  }

  /** Set the handler for server-to-client requests. */
  onServerRequest(handler: (method: string, id: number, params: any) => void) {
    this.serverRequestHandler = handler
  }

  /** Register a progress listener for a workDoneToken. */
  registerProgressListener(token: string, cb: (value: any) => void) {
    this.progressListeners.set(token, cb)
  }

  /** Remove a progress listener. */
  removeProgressListener(token: string) {
    this.progressListeners.delete(token)
  }

  /** Shut down the LSP process. */
  shutdown() {
    this.rejectAllPending(new Error('LSP client shutting down'))
    this.progressListeners.clear()
    this.process?.kill()
    this.process = null
    this.transport = null
  }

  /** Route an incoming message by its JSON-RPC structure. */
  private dispatchMessage(msg: any) {
    const id = typeof msg.id === 'string' ? parseInt(msg.id, 10) : msg.id
    const method: string | undefined = msg.method

    if (id != null && !method) {
      // Response to a client request
      const pending = this.pendingRequests.get(id)
      if (pending) {
        this.pendingRequests.delete(id)
        clearTimeout(pending.timer)
        if (msg.error) {
          pending.reject(new Error(msg.error.message ?? 'RPC error'))
        } else {
          pending.resolve(msg)
        }
      }
      return
    }

    if (id != null && method) {
      // Server-to-client request
      this.serverRequestHandler?.(method, id, msg.params ?? {})
      return
    }

    // Notification (no id)
    if (method === '$/progress') {
      const token = msg.params?.token
      const value = msg.params?.value
      if (token && value) {
        this.progressListeners.get(String(token))?.(value)
      }
      return
    }

    if (method === 'featureFlagsNotification') {
      this._featureFlags = msg.params ?? {}
      this.emit('featureFlags', this._featureFlags)
      return
    }
  }

  private rejectAllPending(error: Error) {
    for (const [id, pending] of this.pendingRequests) {
      clearTimeout(pending.timer)
      pending.reject(error)
    }
    this.pendingRequests.clear()
  }
}
