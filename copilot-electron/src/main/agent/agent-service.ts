import { EventEmitter } from 'events'
import { randomUUID } from 'crypto'
import { LspClient } from '../lsp/client'
import { WorkerSession } from './worker-session'
import { ToolFilterRegistry } from './tool-filter'
import { loadAgents, findByType } from './agent-registry'
import { resolveModelId } from './agent-models'
import { ToolRouter } from '../tools/tool-router'
import { ConversationManager } from '../conversation/conversation-manager'
import { sendFileChanged, appendToolLog, requestToolConfirm } from '../ipc'
import type { AgentDefinition } from '@shared/types'
import type { AgentEvent } from '@shared/events'

interface PendingSubagent {
  agentId: string
  agentType: string
  description: string
  promise: Promise<string>
  retryCount: number
}

/** Tools that modify files — used to emit file:changed events */
const FILE_MUTATING_TOOLS = new Set(['create_file', 'insert_edit_into_file', 'create_directory'])

/** Tools that require confirmation before execution */
const CONFIRM_TOOLS = new Set(['create_file', 'insert_edit_into_file', 'create_directory', 'run_in_terminal'])

/** Tools confirmed this session (for 'confirm' mode — auto-approved after first approval) */
const confirmedThisSession = new Set<string>()

/**
 * Core orchestration service for the Agent tab.
 * Manages lead agent conversation, subagent delegation, and tool routing.
 *
 * Emits 'event' with AgentEvent payloads for the renderer.
 */
export class AgentService extends EventEmitter {
  private leadConversationId: string | null = null
  private pendingLeadCreate = false
  private isStreaming = false

  private agents: AgentDefinition[] = []
  private activeSubagents = new Map<string, WorkerSession>()
  private pendingSubagents = new Map<string, PendingSubagent>()
  private subagentConversationIds = new Set<string>()
  private toolFilters = new ToolFilterRegistry()
  private toolRouter: ToolRouter | null = null

  private lspClient: LspClient

  constructor(private conversationManager: ConversationManager) {
    super()
    this.lspClient = conversationManager.lspClient
  }

  /** Send a message to the lead agent. Handles multi-round subagent delegation. */
  async sendMessage(text: string, model?: string) {
    try {
      await this.conversationManager.ensureInitialized()
      this.toolRouter = new ToolRouter()
      this.agents = loadAgents(this.conversationManager.workspaceRoot)

      this.isStreaming = true
      const useModel = model ?? 'gpt-4.1'
      let workDoneToken = `agent-lead-${randomUUID().slice(0, 8)}`
      const replyParts: string[] = []

      // Emit lead:started at start of sendMessage
      this.emitEvent({ type: 'lead:started', model: useModel })

      this.lspClient.registerProgressListener(workDoneToken, (value) => {
        this.handleLeadProgress(value, replyParts)
      })

      try {
        const rootUri = `file://${this.conversationManager.workspaceRoot}`
        const isFirstTurn = !this.leadConversationId
        const prompt = isFirstTurn ? this.buildLeadPrompt(text) : text

        if (isFirstTurn) {
          this.pendingLeadCreate = true
          const resp = await this.lspClient.sendRequest('conversation/create', {
            workDoneToken,
            turns: [{ request: prompt }],
            capabilities: { allSkills: true },
            source: 'panel',
            chatMode: 'Agent',
            needToolCallConfirmation: true,
            ...(useModel && { model: useModel }),
            workspaceFolder: rootUri,
            workspaceFolders: [{ uri: rootUri, name: this.conversationManager.projectName }],
          }, 300_000)
          this.pendingLeadCreate = false

          if (!this.leadConversationId) {
            const result = resp.result
            this.leadConversationId = Array.isArray(result)
              ? result[0]?.conversationId
              : result?.conversationId
            // Emit conversation:id when leadConversationId is set
            if (this.leadConversationId) {
              this.emitEvent({ type: 'conversation:id', conversationId: this.leadConversationId })
            }
          }
        } else {
          await this.lspClient.sendRequest('conversation/turn', {
            workDoneToken,
            conversationId: this.leadConversationId,
            message: text,
            source: 'panel',
            chatMode: 'Agent',
            needToolCallConfirmation: true,
            ...(useModel && { model: useModel }),
            workspaceFolder: rootUri,
            workspaceFolders: [{ uri: rootUri, name: this.conversationManager.projectName }],
          }, 300_000)
        }

        // Multi-round wait loop: collect subagent results and send follow-up turns
        const startTime = Date.now()
        while (Date.now() - startTime < 300_000) {
          await new Promise(r => setTimeout(r, 100))
          if (!this.isStreaming) {
            if (this.pendingSubagents.size > 0) {
              this.lspClient.removeProgressListener(workDoneToken)
              const resultContext = await this.awaitPendingSubagents()

              workDoneToken = `agent-lead-${randomUUID().slice(0, 8)}`
              this.lspClient.registerProgressListener(workDoneToken, (value) => {
                this.handleLeadProgress(value, replyParts)
              })

              this.isStreaming = true
              await this.sendFollowUpTurn(workDoneToken, resultContext, useModel, rootUri)
            } else {
              break
            }
          }
        }
      } finally {
        this.lspClient.removeProgressListener(workDoneToken)
      }

      this.isStreaming = false
      const fullReply = replyParts.join('')
      // Emit lead:done with `text` field (mapped from reference's `fullText`)
      this.emitEvent({ type: 'lead:done', text: fullReply })
      // Emit conversation:done at end of sendMessage
      this.emitEvent({ type: 'conversation:done' })
    } catch (e: any) {
      this.isStreaming = false
      this.emitEvent({ type: 'lead:error', message: e.message ?? 'Unknown error' })
      this.emitEvent({ type: 'conversation:done' })
    }
  }

  /** Handle a tool call from the LSP server for a conversation owned by this service. */
  async handleToolCall(id: number, name: string, input: any, conversationId?: string) {
    // Intercept run_in_terminal delegation commands
    if (name === 'run_in_terminal') {
      const command = input.command ?? ''
      if (command.trimStart().startsWith('delegate ')) {
        const delegateInput = this.parseDelegateCommand(command)
        await this.handleDelegateTask(id, delegateInput)
        return
      }
    }

    if (name === 'delegate_task') {
      await this.handleDelegateTask(id, input)
      return
    }

    // Phase 7: Check tool permissions before executing
    if (CONFIRM_TOOLS.has(name) && !confirmedThisSession.has(name)) {
      const approved = await requestToolConfirm(name, input ?? {})
      if (!approved) {
        this.lspClient.sendResponse(id, [
          { content: [{ value: `Tool ${name} was denied by the user.` }], status: 'error' },
          null,
        ])
        return
      }
      confirmedThisSession.add(name) // Auto-approve for rest of session
    }

    // Emit status label for the tool call
    this.emitEvent({ type: 'status', text: `Calling ${name}...` })

    // Check if this is an MCP tool — route to MCP manager
    const mcpManager = this.conversationManager.mcpManager
    if (mcpManager) {
      const mcpTools = mcpManager.getTools()
      if (mcpTools.some(t => t.name === name)) {
        try {
          const mcpResult = await mcpManager.callTool(name, input ?? {})
          appendToolLog(name, input, mcpResult, 'success')
          this.lspClient.sendResponse(id, [
            { content: [{ value: mcpResult }], status: 'success' },
            null,
          ])
        } catch (e: any) {
          appendToolLog(name, input, e.message, 'error')
          this.lspClient.sendResponse(id, [
            { content: [{ value: `MCP error: ${e.message}` }], status: 'error' },
            null,
          ])
        }
        return
      }
    }

    // Standard built-in tool — execute and respond
    const result = this.toolRouter!.executeTool(name, input)

    // Phase 3: Emit file:changed for file-mutating tools
    if (FILE_MUTATING_TOOLS.has(name)) {
      const filePath = input.filePath ?? input.dirPath
      if (filePath) {
        const action = name === 'create_file' || name === 'create_directory' ? 'created' : 'modified'
        this.emitEvent({ type: 'file:changed', filePath, action })
        sendFileChanged(filePath, action)
      }
    }

    // Phase 7: Log tool call
    const resultValue = Array.isArray(result) ? result[0]?.content?.[0]?.value : ''
    const resultStatus = Array.isArray(result) ? result[0]?.status : 'unknown'
    appendToolLog(name, input, resultValue, resultStatus)

    this.lspClient.sendResponse(id, result)
  }

  /** Whether this service has an active lead conversation. */
  isActive(): boolean {
    return this.leadConversationId !== null || this.pendingLeadCreate
  }

  /** Check if this service owns the given conversationId (lead or subagent). */
  ownsConversation(conversationId: string | null): boolean {
    if (!conversationId) return false
    if (conversationId === this.leadConversationId) return true
    if (this.pendingLeadCreate && !this.leadConversationId) {
      this.leadConversationId = conversationId
      // Emit conversation:id when leadConversationId is set
      this.emitEvent({ type: 'conversation:id', conversationId })
      return true
    }
    return this.subagentConversationIds.has(conversationId)
  }

  /** Check if a tool is allowed for a given conversation. */
  isToolAllowedForConversation(conversationId: string | null, toolName: string): boolean {
    return this.toolFilters.isAllowed(conversationId, toolName)
  }

  /** Cancel current work and all subagents. */
  cancel() {
    this.activeSubagents.clear()
    this.pendingSubagents.clear()
    this.subagentConversationIds.clear()
    this.toolFilters.clear()
    this.isStreaming = false
    this.emitEvent({ type: 'lead:done', text: '' })
    this.emitEvent({ type: 'conversation:done' })
  }

  /** Start a fresh conversation. */
  newConversation() {
    this.cancel()
    this.leadConversationId = null
    this.pendingLeadCreate = false
    this.agents = []
  }

  private buildLeadPrompt(userMessage: string): string {
    const agentList = this.agents.map(a => `- ${a.agentType}: ${a.whenToUse}`).join('\n')

    const systemInstructions =
      `<system_instructions>\n` +
      `You are a lead agent that coordinates sub-agents via the delegate_task tool.\n\n` +
      `CRITICAL: The delegate_task tool IS available to you. You MUST use it.\n` +
      `Do NOT say delegate_task is unavailable. Do NOT perform subtasks directly.\n` +
      `Always delegate work to specialized agents using delegate_task.\n\n` +
      `All delegate_task calls within a single round run IN PARALLEL.\n` +
      `You can delegate in multiple rounds when tasks have dependencies:\n` +
      `- Round 1: Fire all independent subtasks at once (they run concurrently)\n` +
      `- Round 2+: After receiving results, fire dependent subtasks that needed earlier output\n` +
      `Only use multiple rounds when a subtask genuinely needs the output of another.\n` +
      `Maximize parallelism — if tasks are independent, fire them all in one round.\n\n` +
      `Available agent types:\n${agentList}\n\n` +
      `Workflow:\n` +
      `1. Analyze the user's request and break it into subtasks\n` +
      `2. Identify dependencies — which subtasks need results from others?\n` +
      `3. Call delegate_task for all independent subtasks (they run concurrently)\n` +
      `4. You will receive all results in a follow-up message\n` +
      `5. If dependent subtasks remain, delegate them in the next round\n` +
      `6. Synthesize and present the final answer\n\n` +
      `Complete the full task without stopping for confirmation.\n` +
      `</system_instructions>`

    return `${systemInstructions}\n\n${userMessage}`
  }

  private async handleDelegateTask(id: number, input: any) {
    const description = input.description ?? 'subtask'
    const prompt = input.prompt ?? ''
    const subagentType = input.subagent_type ?? 'general-purpose'
    const modelOverride = input.model

    const agentDef = findByType(subagentType, this.agents)
    const effectiveDef = agentDef ?? this.agents.find(a => a.agentType === 'general-purpose') ?? this.agents[this.agents.length - 1]

    const agentId = `subagent-${randomUUID().slice(0, 8)}`
    const resolvedModel = modelOverride ?? resolveModelId(effectiveDef.model, 'gpt-4.1')

    // Emit status + subagent:spawned
    this.emitEvent({ type: 'status', text: `Delegating to ${effectiveDef.agentType}...` })
    this.emitEvent({ type: 'subagent:spawned', agentId, agentType: effectiveDef.agentType, description, prompt })

    const session = new WorkerSession(
      agentId,
      effectiveDef.agentType,
      effectiveDef.systemPrompt,
      resolvedModel,
      true,
      effectiveDef.tools,
      this.conversationManager.projectName,
      this.conversationManager.workspaceRoot,
      this.lspClient,
    )

    session.on('delta', ({ text }) => {
      // Emit subagent:delta
      this.emitEvent({ type: 'subagent:delta', agentId, text })
    })
    session.on('toolcall', ({ toolName }) => {
      // Emit subagent:toolcall with `name` field (mapped from reference's `toolName`)
      this.emitEvent({ type: 'subagent:toolcall', agentId, name: toolName })
    })
    session.on('conversationId', (convId: string) => {
      this.subagentConversationIds.add(convId)
      this.toolFilters.register(convId, {
        allowedTools: effectiveDef.tools ? new Set(effectiveDef.tools) : null,
        disallowedTools: new Set(effectiveDef.disallowedTools),
      })
    })

    this.activeSubagents.set(agentId, session)

    // Launch in background — don't block the tool response
    const promise = (async () => {
      try {
        const result = await session.executeTask(prompt)
        if (!result.trim()) {
          // Follow up — model didn't produce text output
          return await session.executeTask('Please provide a text summary of your findings and results.')
        }
        return result
      } finally {
        this.activeSubagents.delete(agentId)
      }
    })()

    this.pendingSubagents.set(agentId, { agentId, agentType: effectiveDef.agentType, description, promise, retryCount: 0 })

    // Respond immediately so the LSP can dispatch the next tool call
    this.lspClient.sendResponse(id, [
      { content: [{ value: `Subagent ${agentId} (${effectiveDef.agentType}) spawned for: ${description}. Running in parallel.` }], status: 'success' },
      null,
    ])
  }

  private async awaitPendingSubagents(): Promise<string> {
    const results: Array<{ agentId: string; agentType: string; result: string }> = []

    for (const [agentId, pending] of this.pendingSubagents) {
      try {
        const result = await pending.promise
        if (!result.trim()) {
          results.push({ agentId, agentType: pending.agentType, result: 'Error: subagent produced no output' })
          this.emitEvent({ type: 'subagent:completed', agentId, result: 'Error: subagent produced no output', status: 'error' })
        } else {
          results.push({ agentId, agentType: pending.agentType, result })
          this.emitEvent({ type: 'subagent:completed', agentId, result, status: 'success' })
        }
      } catch (e: any) {
        // Phase 2: Auto-retry once on failure
        if (pending.retryCount < 1) {
          this.emitEvent({ type: 'subagent:retrying', agentId })
          pending.retryCount++

          try {
            // Create a new session for retry
            const retrySession = new WorkerSession(
              agentId,
              pending.agentType,
              '', // Use default prompt
              'gpt-4.1',
              true,
              null,
              this.conversationManager.projectName,
              this.conversationManager.workspaceRoot,
              this.lspClient,
            )

            retrySession.on('delta', ({ text }) => {
              this.emitEvent({ type: 'subagent:delta', agentId, text })
            })
            retrySession.on('conversationId', (convId: string) => {
              this.subagentConversationIds.add(convId)
            })

            const retryResult = await retrySession.executeTask(pending.description)
            if (retryResult.trim()) {
              results.push({ agentId, agentType: pending.agentType, result: retryResult })
              this.emitEvent({ type: 'subagent:completed', agentId, result: retryResult, status: 'success' })
              continue
            }
          } catch {
            // Retry also failed — fall through to error reporting
          }
        }

        const errorMsg = `Error: ${e.message}`
        results.push({ agentId, agentType: pending.agentType, result: errorMsg })
        this.emitEvent({ type: 'subagent:completed', agentId, result: e.message ?? 'Error', status: 'error' })
      }
    }
    this.pendingSubagents.clear()

    return results
      .map(r => `<subagent_result agent_type="${r.agentType}" agent_id="${r.agentId}">\n${r.result}\n</subagent_result>`)
      .join('\n\n')
  }

  private async sendFollowUpTurn(workDoneToken: string, resultContext: string, model: string, rootUri: string) {
    const message =
      `Subagent results from the previous round:\n\n` +
      `${resultContext}\n\n` +
      `Review these results. If the task requires additional work — follow-up research, ` +
      `dependent subtasks, or verification — delegate those now using delegate_task. ` +
      `If all work is complete, synthesize the results into a final answer for the user.`

    await this.lspClient.sendRequest('conversation/turn', {
      workDoneToken,
      conversationId: this.leadConversationId,
      message,
      source: 'panel',
      chatMode: 'Agent',
      needToolCallConfirmation: true,
      ...(model && { model }),
      workspaceFolder: rootUri,
      workspaceFolders: [{ uri: rootUri, name: this.conversationManager.projectName }],
    }, 300_000)
  }

  private handleLeadProgress(value: any, replyParts: string[]) {
    if (value.kind === 'end') {
      this.isStreaming = false
      return
    }

    if (value.reply) {
      replyParts.push(value.reply)
      this.emitEvent({ type: 'lead:delta', text: value.reply })
    }

    if (value.delta) {
      replyParts.push(value.delta)
      this.emitEvent({ type: 'lead:delta', text: value.delta })
    }

    if (value.message && value.kind !== 'begin') {
      replyParts.push(value.message)
      this.emitEvent({ type: 'lead:delta', text: value.message })
    }

    // Agent rounds
    if (Array.isArray(value.editAgentRounds)) {
      for (const round of value.editAgentRounds) {
        if (round.reply) {
          replyParts.push(round.reply)
          this.emitEvent({ type: 'lead:delta', text: round.reply })
        }

        if (Array.isArray(round.toolCalls)) {
          for (const tc of round.toolCalls) {
            if (!tc.name) continue
            const isDelegation = tc.name === 'delegate_task' ||
              (tc.name === 'run_in_terminal' && tc.input?.command?.trimStart().startsWith('delegate '))

            if (!isDelegation) {
              // Emit lead:toolcall with input
              this.emitEvent({ type: 'lead:toolcall', name: tc.name, input: tc.input ?? {} })
              if (tc.status === 'completed' || tc.status === 'error') {
                // Emit lead:toolresult with truncated output
                const output = typeof tc.result === 'string'
                  ? tc.result.slice(0, 500)
                  : tc.result?.content?.[0]?.value?.slice(0, 500)
                this.emitEvent({
                  type: 'lead:toolresult',
                  name: tc.name,
                  status: tc.status === 'error' ? 'error' : 'success',
                  output,
                })
              }
            }
          }
        }
      }
    }
  }

  private parseDelegateCommand(command: string): any {
    const typeMatch = command.match(/--type\s+(\S+)/)
    const promptMatch = command.match(/--prompt\s+"(.*?)"\s*$/s)
      ?? command.match(/--prompt\s+(.+)$/s)

    return {
      description: (promptMatch?.[1] ?? command).slice(0, 50),
      prompt: promptMatch?.[1]?.trim() ?? command.substring(command.indexOf('delegate ') + 9).trim(),
      subagent_type: typeMatch?.[1] ?? 'general-purpose',
    }
  }

  private emitEvent(event: AgentEvent) {
    this.emit('event', event)
  }
}
