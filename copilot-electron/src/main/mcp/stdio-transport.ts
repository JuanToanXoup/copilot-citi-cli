import { ChildProcess, spawn } from 'child_process'
import { EventEmitter } from 'events'

/**
 * Content-Length framed JSON-RPC over stdin/stdout transport.
 * Reuses the same framing as LSP.
 */
export class StdioTransport extends EventEmitter {
  private process: ChildProcess | null = null
  private buffer = ''
  private contentLength = -1

  constructor(
    private command: string,
    private args: string[] = [],
    private env: Record<string, string> = {},
  ) {
    super()
  }

  start(): void {
    this.process = spawn(this.command, this.args, {
      stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, ...this.env },
    })

    this.process.stdout?.on('data', (chunk: Buffer) => {
      this.buffer += chunk.toString('utf-8')
      this.parseMessages()
    })

    this.process.stderr?.on('data', (chunk: Buffer) => {
      this.emit('error', chunk.toString('utf-8'))
    })

    this.process.on('exit', (code) => {
      this.emit('close', code)
    })

    this.process.on('error', (err) => {
      this.emit('error', err.message)
    })
  }

  send(message: Record<string, unknown>): void {
    if (!this.process?.stdin?.writable) return
    const json = JSON.stringify(message)
    const header = `Content-Length: ${Buffer.byteLength(json)}\r\n\r\n`
    this.process.stdin.write(header + json)
  }

  stop(): void {
    if (this.process) {
      this.process.kill()
      this.process = null
    }
  }

  get isRunning(): boolean {
    return this.process !== null && !this.process.killed
  }

  private parseMessages(): void {
    while (true) {
      if (this.contentLength === -1) {
        const headerEnd = this.buffer.indexOf('\r\n\r\n')
        if (headerEnd === -1) return

        const header = this.buffer.slice(0, headerEnd)
        const match = header.match(/Content-Length:\s*(\d+)/i)
        if (!match) {
          this.buffer = this.buffer.slice(headerEnd + 4)
          continue
        }

        this.contentLength = parseInt(match[1], 10)
        this.buffer = this.buffer.slice(headerEnd + 4)
      }

      if (this.buffer.length < this.contentLength) return

      const body = this.buffer.slice(0, this.contentLength)
      this.buffer = this.buffer.slice(this.contentLength)
      this.contentLength = -1

      try {
        const message = JSON.parse(body)
        this.emit('message', message)
      } catch {
        this.emit('error', `Failed to parse JSON-RPC message: ${body.slice(0, 100)}`)
      }
    }
  }
}
