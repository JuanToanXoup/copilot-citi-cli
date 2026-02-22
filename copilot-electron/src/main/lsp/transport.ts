import { ChildProcess } from 'child_process'

/**
 * Content-Length framed JSON-RPC transport over stdin/stdout.
 * Implements the LSP base protocol.
 */
export class LspTransport {
  private buffer = Buffer.alloc(0)
  private onMessage: ((msg: any) => void) | null = null

  constructor(private process: ChildProcess) {
    process.stdout!.on('data', (chunk: Buffer) => {
      this.buffer = Buffer.concat([this.buffer, chunk])
      this.drain()
    })
  }

  /** Register handler for incoming JSON-RPC messages. */
  setMessageHandler(handler: (msg: any) => void) {
    this.onMessage = handler
  }

  /** Send a JSON-RPC message with Content-Length framing. */
  send(message: any) {
    const body = Buffer.from(JSON.stringify(message), 'utf-8')
    const header = `Content-Length: ${body.length}\r\n\r\n`
    this.process.stdin!.write(header, 'ascii')
    this.process.stdin!.write(body)
  }

  /** Parse buffered bytes for complete messages. */
  private drain() {
    while (true) {
      const headerEnd = this.buffer.indexOf('\r\n\r\n')
      if (headerEnd === -1) break

      const header = this.buffer.subarray(0, headerEnd).toString('ascii')
      const match = header.match(/Content-Length:\s*(\d+)/i)
      if (!match) {
        // Malformed header — skip past it
        this.buffer = this.buffer.subarray(headerEnd + 4)
        continue
      }

      const contentLength = parseInt(match[1], 10)
      const bodyStart = headerEnd + 4
      const bodyEnd = bodyStart + contentLength

      if (this.buffer.length < bodyEnd) break // Incomplete body

      const body = this.buffer.subarray(bodyStart, bodyEnd).toString('utf-8')
      this.buffer = this.buffer.subarray(bodyEnd)

      try {
        const msg = JSON.parse(body)
        this.onMessage?.(msg)
      } catch {
        // Malformed JSON — skip
      }
    }
  }
}
