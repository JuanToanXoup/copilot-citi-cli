import { EventEmitter } from 'events'
import http from 'http'
import https from 'https'

/**
 * SSE (Server-Sent Events) transport for MCP servers.
 * Connects to an HTTP/HTTPS endpoint, reads SSE events for JSON-RPC messages,
 * and POSTs JSON-RPC requests back to the server's message endpoint.
 */
export class SseTransport extends EventEmitter {
  private eventSource: http.ClientRequest | null = null
  private messageEndpoint: string | null = null
  private buffer = ''
  private _running = false

  constructor(private url: string) {
    super()
  }

  start(): void {
    this._running = true
    const parsedUrl = new URL(this.url)
    const transport = parsedUrl.protocol === 'https:' ? https : http

    const req = transport.get(this.url, {
      headers: {
        Accept: 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive',
      },
    }, (res) => {
      if (res.statusCode !== 200) {
        this.emit('error', `SSE connection failed with status ${res.statusCode}`)
        this._running = false
        return
      }

      res.setEncoding('utf-8')

      res.on('data', (chunk: string) => {
        this.buffer += chunk
        this.parseSSEEvents()
      })

      res.on('end', () => {
        this._running = false
        this.emit('close', 0)
      })

      res.on('error', (err) => {
        this.emit('error', err.message)
      })
    })

    req.on('error', (err) => {
      this._running = false
      this.emit('error', err.message)
    })

    this.eventSource = req
  }

  send(message: Record<string, unknown>): void {
    // POST JSON-RPC message to the message endpoint (or base URL /message)
    const targetUrl = this.messageEndpoint ?? this.resolveMessageUrl()
    const body = JSON.stringify(message)
    const parsedUrl = new URL(targetUrl)
    const transport = parsedUrl.protocol === 'https:' ? https : http

    const req = transport.request(targetUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
      },
    }, (res) => {
      // Read response body for any errors
      let data = ''
      res.on('data', (chunk: string) => { data += chunk })
      res.on('end', () => {
        if (res.statusCode && res.statusCode >= 400) {
          this.emit('error', `SSE POST failed (${res.statusCode}): ${data}`)
        }
      })
    })

    req.on('error', (err) => {
      this.emit('error', `SSE POST error: ${err.message}`)
    })

    req.write(body)
    req.end()
  }

  stop(): void {
    this._running = false
    if (this.eventSource) {
      this.eventSource.destroy()
      this.eventSource = null
    }
  }

  get isRunning(): boolean {
    return this._running
  }

  /** Parse buffered SSE data into events */
  private parseSSEEvents(): void {
    const lines = this.buffer.split('\n')
    // Keep incomplete last line in buffer
    this.buffer = lines.pop() ?? ''

    let eventType = ''
    let eventData = ''

    for (const line of lines) {
      if (line.startsWith('event:')) {
        eventType = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        eventData += line.slice(5).trim()
      } else if (line.trim() === '' && eventData) {
        // Empty line = end of event
        this.handleSSEEvent(eventType, eventData)
        eventType = ''
        eventData = ''
      }
    }
  }

  /** Handle a single parsed SSE event */
  private handleSSEEvent(eventType: string, data: string): void {
    if (eventType === 'endpoint') {
      // Server sends the message endpoint URL
      this.messageEndpoint = this.resolveEndpointUrl(data)
      return
    }

    // Default: treat as JSON-RPC message
    try {
      const message = JSON.parse(data)
      this.emit('message', message)
    } catch {
      this.emit('error', `Failed to parse SSE JSON-RPC message: ${data.slice(0, 100)}`)
    }
  }

  /** Resolve relative endpoint URL against base URL */
  private resolveEndpointUrl(endpoint: string): string {
    try {
      return new URL(endpoint, this.url).toString()
    } catch {
      return endpoint
    }
  }

  /** Default message endpoint: base URL + /message */
  private resolveMessageUrl(): string {
    try {
      const parsed = new URL(this.url)
      parsed.pathname = parsed.pathname.replace(/\/$/, '') + '/message'
      return parsed.toString()
    } catch {
      return this.url + '/message'
    }
  }
}
