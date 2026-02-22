/** Shared type definitions */

export type AgentModel = 'inherit' | 'haiku' | 'sonnet' | 'opus'

export interface AgentDefinition {
  agentType: string
  whenToUse: string
  tools: string[] | null         // null = all tools allowed
  disallowedTools: string[]
  model: AgentModel
  systemPrompt: string
  maxTurns: number
  source: 'built-in' | 'custom-project' | 'custom-user'
}

export interface SubagentToolFilter {
  allowedTools: Set<string> | null  // null = all allowed
  disallowedTools: Set<string>
}

export interface ToolSchema {
  name: string
  description: string
  inputSchema: Record<string, unknown>
}

export interface LspStatus {
  connected: boolean
  user?: string
}

export interface PsiStatus {
  connected: boolean
  tools: string[]
}

/** Serialisable agent card sent to the renderer */
export interface AgentCard {
  agentType: string
  whenToUse: string
  model: AgentModel
  source: 'built-in' | 'custom-project' | 'custom-user'
  toolCount: number | null  // null = all tools
  maxTurns: number
}
