/** Agent event types emitted by main process → renderer */

export type AgentEvent =
  | { type: 'lead:started'; model: string }
  | { type: 'lead:delta'; text: string }
  | { type: 'lead:toolcall'; name: string; input: Record<string, unknown> }
  | { type: 'lead:toolresult'; name: string; status: 'success' | 'error'; output?: string }
  | { type: 'lead:done'; text: string }
  | { type: 'lead:error'; message: string }
  | { type: 'subagent:spawned'; agentId: string; agentType: string; description: string; prompt?: string }
  | { type: 'subagent:delta'; agentId: string; text: string }
  | { type: 'subagent:toolcall'; agentId: string; name: string }
  | { type: 'subagent:completed'; agentId: string; status: 'success' | 'error'; result: string }
  | { type: 'subagent:retrying'; agentId: string }
  | { type: 'conversation:id'; conversationId: string }
  | { type: 'conversation:done' }
  | { type: 'file:changed'; filePath: string; action: 'created' | 'modified' | 'deleted' }
  | { type: 'status'; text: string }
