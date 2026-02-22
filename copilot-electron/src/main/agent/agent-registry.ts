import { readFileSync, readdirSync, existsSync } from 'fs'
import { join } from 'path'
import { homedir } from 'os'
import { BUILT_IN_AGENTS } from './agent-models'
import type { AgentDefinition, AgentModel } from '@shared/types'

/**
 * Load all agent definitions: built-ins + custom .md files
 * from the project's .claude/agents/ and ~/.claude/agents/.
 */
export function loadAgents(projectBasePath?: string): AgentDefinition[] {
  const agents = [...BUILT_IN_AGENTS]

  // Project-level custom agents
  if (projectBasePath) {
    const dir = join(projectBasePath, '.claude/agents')
    agents.push(...loadAgentsFromDir(dir, 'custom-project'))
  }

  // User-level custom agents
  const userDir = join(homedir(), '.claude/agents')
  agents.push(...loadAgentsFromDir(userDir, 'custom-user'))

  return agents
}

/** Case-insensitive agent lookup by type. */
export function findByType(agentType: string, agents: AgentDefinition[]): AgentDefinition | undefined {
  return agents.find(a => a.agentType.toLowerCase() === agentType.toLowerCase())
}

function loadAgentsFromDir(dir: string, source: 'custom-project' | 'custom-user'): AgentDefinition[] {
  if (!existsSync(dir)) return []

  const agents: AgentDefinition[] = []
  for (const file of readdirSync(dir).filter(f => f.endsWith('.md'))) {
    try {
      const agent = parseAgentFile(join(dir, file), source)
      if (agent) agents.push(agent)
    } catch {
      // Skip malformed agent files
    }
  }
  return agents
}

/**
 * Parse a .md agent file with optional YAML frontmatter.
 */
function parseAgentFile(filePath: string, source: 'custom-project' | 'custom-user'): AgentDefinition | null {
  const content = readFileSync(filePath, 'utf-8')
  const frontmatterMatch = content.match(/^---\n(.*?)\n---\n/s)

  const frontmatter = frontmatterMatch?.[1] ?? ''
  const body = frontmatterMatch
    ? content.substring(frontmatterMatch[0].length).trim()
    : content.trim()

  if (!body && !frontmatter) return null

  // Parse frontmatter as simple key: value pairs
  const props: Record<string, string> = {}
  for (const line of frontmatter.split('\n')) {
    const colonIdx = line.indexOf(':')
    if (colonIdx > 0) {
      props[line.substring(0, colonIdx).trim()] = line.substring(colonIdx + 1).trim()
    }
  }

  const name = props.name ?? filePath.replace(/.*\//, '').replace(/\.md$/, '')
  const toolsRaw = props.tools
  const tools = toolsRaw
    ? toolsRaw.replace(/^\[|]$/g, '').split(',').map(t => t.trim()).filter(Boolean)
    : null

  const model: AgentModel = (() => {
    switch (props.model?.toLowerCase()) {
      case 'haiku': return 'haiku'
      case 'sonnet': return 'sonnet'
      case 'opus': return 'opus'
      default: return 'inherit'
    }
  })()

  return {
    agentType: name,
    whenToUse: props.description ?? `Custom agent: ${name}`,
    tools,
    disallowedTools: ['delegate_task', 'create_team', 'send_message', 'delete_team'],
    source,
    model,
    systemPrompt: body,
    maxTurns: parseInt(props.maxTurns ?? '30', 10),
  }
}
