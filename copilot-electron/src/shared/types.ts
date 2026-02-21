/** Shared type definitions */

export interface AgentDefinition {
  id: string
  name: string
  description: string
  systemPrompt: string
  allowedTools?: string[]
  blockedTools?: string[]
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
