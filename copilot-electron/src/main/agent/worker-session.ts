import { EventEmitter } from 'events'
import { LspClient } from '../lsp/client'
import { randomUUID } from 'crypto'

/**
 * Lightweight conversation wrapper for a single agent.
 * Each WorkerSession gets its own conversationId on the shared LspClient.
 *
 * Events:
 *   'delta'          - { workerId, text }
 *   'toolcall'       - { workerId, toolName }
 *   'done'           - { workerId }
 *   'error'          - { workerId, message }
 *   'conversationId' - conversationId string
 */
export class WorkerSession extends EventEmitter {
  private conversationId: string | null = null
  private isFirstTurn = true
  /** Tracks cumulative reply length per round index to emit only incremental deltas. */
  private roundReplyLengths = new Map<number, number>()

  constructor(
    public readonly workerId: string,
    public readonly role: string,
    private readonly systemPrompt: string,
    private readonly model: string,
    private readonly agentMode: boolean,
    private readonly toolsEnabled: string[] | null,  // null = all tools
    private readonly projectName: string,
    private readonly workspaceRoot: string,
    private readonly lspClient: LspClient,
  ) {
    super()
  }

  /**
   * Execute a task in this worker's conversation.
   * Creates a new conversation on first call, continues via conversation/turn on subsequent calls.
   */
  async executeTask(
    task: string,
    dependencyContext: Record<string, string> = {},
  ): Promise<string> {
    const prompt = this.buildPrompt(task, dependencyContext)
    const workDoneToken = `worker-${this.workerId}-${randomUUID().slice(0, 8)}`
    const replyParts: string[] = []

    // Promise that resolves when streaming ends
    const streamDone = new Promise<void>((resolve) => {
      this.lspClient.registerProgressListener(workDoneToken, (value) => {
        this.handleProgress(value, replyParts)
        if (value.kind === 'end') resolve()
      })
    })

    try {
      const rootUri = `file://${this.workspaceRoot}`

      if (!this.conversationId) {
        const resp = await this.lspClient.sendRequest('conversation/create', {
          workDoneToken,
          turns: [{ request: prompt }],
          capabilities: { allSkills: this.agentMode },
          source: 'panel',
          ...(this.agentMode && { chatMode: 'Agent', needToolCallConfirmation: true }),
          ...(this.model && { model: this.model }),
          workspaceFolder: rootUri,
          workspaceFolders: [{ uri: rootUri, name: this.projectName }],
        }, 300_000)

        const result = resp.result
        this.conversationId = Array.isArray(result)
          ? result[0]?.conversationId
          : result?.conversationId
        this.emit('conversationId', this.conversationId)

        // Fallback: extract reply from create response if no progress events
        if (replyParts.length === 0) {
          const fallback = this.extractReplyFromResponse(result)
          if (fallback) replyParts.push(fallback)
        }
      } else {
        await this.lspClient.sendRequest('conversation/turn', {
          workDoneToken,
          conversationId: this.conversationId,
          message: prompt,
          source: 'panel',
          ...(this.agentMode && { chatMode: 'Agent', needToolCallConfirmation: true }),
          ...(this.model && { model: this.model }),
          workspaceFolder: rootUri,
          workspaceFolders: [{ uri: rootUri, name: this.projectName }],
        }, 300_000)
      }

      // Wait for streaming to complete
      const timeout = this.agentMode ? 300_000 : 60_000
      await Promise.race([
        streamDone,
        new Promise<void>((_, reject) =>
          setTimeout(() => reject(new Error('Stream timeout')), timeout)
        ),
      ])
    } finally {
      this.lspClient.removeProgressListener(workDoneToken)
      this.isFirstTurn = false
    }

    return replyParts.join('')
  }

  cancel() {
    // Cancellation handled by the caller aborting the promise
  }

  private buildPrompt(task: string, dependencyContext: Record<string, string>): string {
    const parts: string[] = []

    if (this.isFirstTurn && this.systemPrompt) {
      parts.push(`<system_instructions>\n${this.systemPrompt}\n</system_instructions>`)
    }

    if (this.toolsEnabled) {
      const toolList = this.toolsEnabled.join(', ')
      parts.push(
        `<tool_restrictions>\n` +
        `You may ONLY use these tools: ${toolList}\n` +
        `Do not attempt to use any other tools.\n` +
        `</tool_restrictions>`
      )
    }

    if (Object.keys(dependencyContext).length > 0) {
      parts.push(`<shared_context>\n${JSON.stringify(dependencyContext)}\n</shared_context>`)
    }

    parts.push(task)
    return parts.join('\n\n')
  }

  private handleProgress(value: any, replyParts: string[]) {
    const reply = value.reply
    if (reply) {
      replyParts.push(reply)
      this.emit('delta', { workerId: this.workerId, text: reply })
    }

    const delta = value.delta
    if (delta) {
      replyParts.push(delta)
      this.emit('delta', { workerId: this.workerId, text: delta })
    }

    const message = value.message
    if (message && value.kind !== 'begin') {
      replyParts.push(message)
      this.emit('delta', { workerId: this.workerId, text: message })
    }

    // Agent rounds — reply text is cumulative, so track what we've already seen
    const rounds = value.editAgentRounds
    if (Array.isArray(rounds)) {
      for (let idx = 0; idx < rounds.length; idx++) {
        const round = rounds[idx]
        const roundReply = round.reply ?? ''
        const prevLen = this.roundReplyLengths.get(idx) ?? 0
        if (roundReply.length > prevLen) {
          const newText = roundReply.slice(prevLen)
          this.roundReplyLengths.set(idx, roundReply.length)
          replyParts.push(newText)
          this.emit('delta', { workerId: this.workerId, text: newText })
        }

        if (Array.isArray(round.toolCalls)) {
          for (const tc of round.toolCalls) {
            if (tc.name) this.emit('toolcall', { workerId: this.workerId, toolName: tc.name })
          }
        }
      }
    }

    if (value.kind === 'end') {
      this.emit('done', { workerId: this.workerId })
    }
  }

  private extractReplyFromResponse(result: any): string | null {
    if (!result) return null
    const obj = Array.isArray(result) ? result[0] : result
    if (!obj) return null

    const directReply = obj.reply ?? obj.message
    if (directReply) return directReply

    // Try editAgentRounds
    if (Array.isArray(obj.editAgentRounds)) {
      const replies = obj.editAgentRounds
        .map((r: any) => r.reply)
        .filter(Boolean)
      if (replies.length) return replies.join('')
    }

    return null
  }
}
