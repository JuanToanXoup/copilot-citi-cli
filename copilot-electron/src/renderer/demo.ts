import type { AgentStoreApi } from './stores/create-agent-store'
import type { FlowStoreApi } from './stores/create-flow-store'

/**
 * Runs a multi-turn demo that showcases the execution trace flow graph
 * and synchronized chat panel.
 *
 * Turn 1: "Explore auth modules" → Agent → 2 subagents (Explore, Plan)
 * Turn 2: "Now add JWT" → Agent → 2 subagents + tool + terminal
 */
export function startDemo(agentStore: AgentStoreApi, flowStore: FlowStoreApi): void {
  const flow = flowStore.getState()
  const chat = agentStore.getState()
  flow.onReset()
  chat.reset()

  // Mirrors the ref tracking from App.tsx
  let currentAgentNodeId = ''
  let lastBranchParent = ''

  // ---- Turn 1 ----
  const t1Start = 0
  schedule(t1Start, () => {
    const userId = 'user-t1'
    flow.addEvent('user', userId, { message: 'Explore auth modules' })
    chat.addUserMessage('Explore auth modules')
    lastBranchParent = userId
    chat.setProcessing(true)
  })

  schedule(t1Start + 400, () => {
    const nodeId = 'agent-t1'
    flow.addEvent('agent', nodeId, { status: 'running', text: 'Let me explore your auth modules...' }, lastBranchParent)
    currentAgentNodeId = nodeId
    lastBranchParent = nodeId
    chat.addAgentDelta('Let me explore your auth modules...')
  })

  schedule(t1Start + 600, () => {
    currentAgentNodeId = '' // break agent text
    const nodeId = 'subagent-explore-1'
    flow.addEvent('subagent', nodeId, {
      agentId: 'explore-1', agentType: 'Explore',
      description: 'Search for auth-related files', status: 'running', textPreview: '',
    }, lastBranchParent)
    chat.addSubagentMessage('explore-1', 'Explore', 'Search for auth-related files')
  })

  schedule(t1Start + 900, () => {
    const nodeId = 'subagent-plan-1'
    flow.addEvent('subagent', nodeId, {
      agentId: 'plan-1', agentType: 'Plan',
      description: 'Create implementation plan for auth', status: 'running', textPreview: '',
    }, lastBranchParent)
    chat.addSubagentMessage('plan-1', 'Plan', 'Create implementation plan for auth')
  })

  schedule(t1Start + 1500, () => {
    flow.updateNode('subagent-explore-1', {
      textPreview: 'Found auth.ts, middleware.ts, session.ts...',
    })
    chat.addSubagentDelta('explore-1', 'Found auth.ts, middleware.ts, session.ts...')
  })

  schedule(t1Start + 2200, () => {
    flow.updateNode('subagent-explore-1', { status: 'success' })
    flow.updateEdge('subagent-explore-1', { status: 'success' })
    flow.addEvent('subagentResult', 'subresult-explore-1', {
      agentId: 'explore-1', agentType: 'Explore',
      status: 'success', text: 'Found auth.ts, middleware.ts, session.ts',
    }, 'subagent-explore-1')
    chat.updateSubagentStatus('explore-1', 'success')
  })

  schedule(t1Start + 2800, () => {
    flow.updateNode('subagent-plan-1', {
      textPreview: 'Plan: 1) Add JWT library 2) Create token utils 3) Update middleware',
    })
    chat.addSubagentDelta('plan-1', 'Plan: 1) Add JWT library 2) Create token utils 3) Update middleware')
  })

  schedule(t1Start + 3400, () => {
    flow.updateNode('subagent-plan-1', { status: 'success' })
    flow.updateEdge('subagent-plan-1', { status: 'success' })
    flow.addEvent('subagentResult', 'subresult-plan-1', {
      agentId: 'plan-1', agentType: 'Plan',
      status: 'success', text: 'Plan: 1) Add JWT 2) Token utils 3) Middleware',
    }, 'subagent-plan-1')
    chat.updateSubagentStatus('plan-1', 'success')

    // Agent sends final message for this turn
    const nodeId = 'agent-t1-final'
    flow.addEvent('agent', nodeId, {
      status: 'done', text: 'I found the auth modules and created an implementation plan.',
    })
    currentAgentNodeId = ''
    lastBranchParent = nodeId
    chat.addAgentDelta('\n\nI found the auth modules and created an implementation plan.')
  })

  // ---- Turn 2 ----
  const t2Start = 4500

  schedule(t2Start, () => {
    const userId = 'user-t2'
    flow.addEvent('user', userId, { message: 'Now add JWT' })
    chat.addUserMessage('Now add JWT')
    lastBranchParent = userId
  })

  schedule(t2Start + 400, () => {
    const nodeId = 'agent-t2'
    flow.addEvent('agent', nodeId, { status: 'running', text: 'I\'ll implement JWT authentication...' }, lastBranchParent)
    currentAgentNodeId = nodeId
    lastBranchParent = nodeId
    chat.addAgentDelta('I\'ll implement JWT authentication...')
  })

  schedule(t2Start + 600, () => {
    currentAgentNodeId = ''
    const nodeId = 'subagent-code-1'
    flow.addEvent('subagent', nodeId, {
      agentId: 'code-1', agentType: 'Code',
      description: 'Implement JWT token utilities', status: 'running', textPreview: '',
    }, lastBranchParent)
    chat.addSubagentMessage('code-1', 'Code', 'Implement JWT token utilities')
  })

  schedule(t2Start + 900, () => {
    const nodeId = 'subagent-explore-2'
    flow.addEvent('subagent', nodeId, {
      agentId: 'explore-2', agentType: 'Explore',
      description: 'Find existing session handling', status: 'running', textPreview: '',
    }, lastBranchParent)
    chat.addSubagentMessage('explore-2', 'Explore', 'Find existing session handling')
  })

  schedule(t2Start + 1200, () => {
    const nodeId = 'tool-read_file-t2'
    flow.addEvent('tool', nodeId, { name: 'read_file', status: 'running', input: { path: 'src/auth.ts' } }, lastBranchParent)
    chat.addToolMessage('read_file')
  })

  schedule(t2Start + 1800, () => {
    flow.updateNode('tool-read_file-t2', { status: 'success' })
    flow.updateEdge('tool-read_file-t2', { status: 'success' })
    flow.addEvent('toolResult', 'result-tool-read_file-t2', {
      name: 'read_file', status: 'success', output: 'export function login() { ... }',
    }, 'tool-read_file-t2')
    chat.updateToolStatus('read_file', 'success')
  })

  schedule(t2Start + 2000, () => {
    flow.updateNode('subagent-code-1', {
      textPreview: 'import jwt from "jsonwebtoken"\n\nexport function signToken(payload)...',
    })
    chat.addSubagentDelta('code-1', 'import jwt from "jsonwebtoken"\n\nexport function signToken(payload)...')
  })

  schedule(t2Start + 2200, () => {
    const nodeId = 'terminal-t2'
    flow.addEvent('terminal', nodeId, { command: 'npm install jsonwebtoken', status: 'running' }, lastBranchParent)
    chat.addStatusMessage('Running: npm install jsonwebtoken')
  })

  schedule(t2Start + 2600, () => {
    flow.updateNode('subagent-explore-2', { status: 'success' })
    flow.updateEdge('subagent-explore-2', { status: 'success' })
    flow.addEvent('subagentResult', 'subresult-explore-2', {
      agentId: 'explore-2', agentType: 'Explore',
      status: 'success', text: 'Found session.ts with cookie-based auth',
    }, 'subagent-explore-2')
    chat.updateSubagentStatus('explore-2', 'success')
  })

  schedule(t2Start + 2800, () => {
    flow.updateNode('terminal-t2', { status: 'success' })
    flow.updateEdge('terminal-t2', { status: 'success' })
    flow.addEvent('terminalResult', 'termresult-terminal-t2', {
      command: 'npm install jsonwebtoken', status: 'success', output: 'added 1 package in 2.3s',
    }, 'terminal-t2')
  })

  schedule(t2Start + 3200, () => {
    flow.updateNode('subagent-code-1', { status: 'success' })
    flow.updateEdge('subagent-code-1', { status: 'success' })
    flow.addEvent('subagentResult', 'subresult-code-1', {
      agentId: 'code-1', agentType: 'Code',
      status: 'success', text: 'Created jwt-utils.ts with signToken/verifyToken',
    }, 'subagent-code-1')
    chat.updateSubagentStatus('code-1', 'success')

    // Agent final message
    const nodeId = 'agent-t2-final'
    flow.addEvent('agent', nodeId, {
      status: 'done', text: 'JWT authentication has been implemented successfully.',
    })
    currentAgentNodeId = ''
    chat.addAgentDelta('\n\nJWT authentication has been implemented successfully.')
    chat.setProcessing(false)
  })
}

function schedule(ms: number, fn: () => void): void {
  setTimeout(fn, ms)
}
