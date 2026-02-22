import type { AgentDefinition } from '@shared/types'

/** Resolve an agent model tier to the actual model ID accepted by the LSP server. */
export function resolveModelId(model: string, parentModel: string): string {
  switch (model) {
    case 'inherit': return parentModel
    case 'haiku': return 'gpt-4.1'
    case 'sonnet': return 'gpt-4.1'
    case 'opus': return 'gpt-4.1'
    default: return parentModel
  }
}

/** 4 built-in agents matching the IntelliJ plugin architecture. */
export const BUILT_IN_AGENTS: AgentDefinition[] = [
  {
    agentType: 'Explore',
    whenToUse: 'Fast agent for exploring codebases. Use for file searches, keyword searches, and codebase questions.',
    tools: ['ide', 'read_file', 'list_dir', 'grep_search', 'file_search'],
    disallowedTools: ['delegate_task', 'create_team', 'send_message', 'delete_team'],
    model: 'haiku',
    systemPrompt: 'You are an Explore agent specialized for fast codebase exploration. Search for files, read code, and answer questions about the codebase. Do NOT modify any files.',
    maxTurns: 15,
    source: 'built-in',
  },
  {
    agentType: 'Plan',
    whenToUse: 'Software architect agent for designing implementation plans. Use for planning strategy, identifying critical files, and considering architectural trade-offs.',
    tools: ['ide', 'read_file', 'list_dir', 'grep_search', 'file_search'],
    disallowedTools: ['delegate_task', 'create_team', 'send_message', 'delete_team'],
    model: 'inherit',
    systemPrompt: 'You are a Plan agent specialized for designing implementation approaches. Explore the codebase, identify patterns, and return a step-by-step plan. Do NOT modify any files.',
    maxTurns: 20,
    source: 'built-in',
  },
  {
    agentType: 'Bash',
    whenToUse: 'Command execution specialist for running bash commands. Use for git operations, builds, tests, and terminal tasks.',
    tools: ['run_in_terminal'],
    disallowedTools: ['delegate_task', 'create_team', 'send_message', 'delete_team'],
    model: 'inherit',
    systemPrompt: 'You are a Bash agent specialized for command execution. Run bash commands to accomplish the requested task. Only use the run_in_terminal tool.',
    maxTurns: 15,
    source: 'built-in',
  },
  {
    agentType: 'general-purpose',
    whenToUse: 'General-purpose agent for complex multi-step tasks. Has access to all tools. Use when no specialized agent fits.',
    tools: null,
    disallowedTools: ['delegate_task', 'create_team', 'send_message', 'delete_team'],
    model: 'inherit',
    systemPrompt: 'You are a general-purpose agent. Complete the requested task using any tools available to you.',
    maxTurns: 30,
    source: 'built-in',
  },
]
