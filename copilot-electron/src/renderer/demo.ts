import { useFlowStore } from './stores/flow-store'
import { useAgentStore } from './stores/agent-store'

/**
 * Runs a multi-turn demo that showcases the turn-based flow graph
 * and synchronized chat panel.
 *
 * Turn 1: "Explore auth modules" → Lead → 2 subagents (Explore, Plan)
 * Turn 2: "Now add JWT" → Lead · Turn 2 → 2 subagents (Code, Explore) + tool + terminal
 */
export function startDemo(): void {
  const flow = useFlowStore.getState()
  const chat = useAgentStore.getState()
  flow.onReset()
  chat.reset()

  // ---- Turn 1 ----
  const t1Start = 0
  schedule(t1Start, () => {
    flow.onNewTurn('Explore auth modules')
    chat.addUserMessage('Explore auth modules')
    flow.onLeadStatus('running')
    chat.setProcessing(true)
  })

  schedule(t1Start + 400, () => {
    chat.addAgentDelta('Let me explore your auth modules...')
  })

  schedule(t1Start + 600, () => {
    flow.onSubagentSpawned('explore-1', 'Explore', 'Search for auth-related files')
    chat.addSubagentMessage('explore-1', 'Explore', 'Search for auth-related files')
  })

  schedule(t1Start + 900, () => {
    flow.onSubagentSpawned('plan-1', 'Plan', 'Create implementation plan for auth')
    chat.addSubagentMessage('plan-1', 'Plan', 'Create implementation plan for auth')
  })

  schedule(t1Start + 1500, () => {
    flow.onSubagentDelta('explore-1', 'Found auth.ts, middleware.ts, session.ts...')
    chat.addSubagentDelta('explore-1', 'Found auth.ts, middleware.ts, session.ts...')
  })

  schedule(t1Start + 2200, () => {
    flow.onSubagentCompleted('explore-1', 'success')
    chat.updateSubagentStatus('explore-1', 'success')
  })

  schedule(t1Start + 2800, () => {
    flow.onSubagentDelta('plan-1', 'Plan: 1) Add JWT library 2) Create token utils 3) Update middleware')
    chat.addSubagentDelta('plan-1', 'Plan: 1) Add JWT library 2) Create token utils 3) Update middleware')
  })

  schedule(t1Start + 3400, () => {
    flow.onSubagentCompleted('plan-1', 'success')
    chat.updateSubagentStatus('plan-1', 'success')
    flow.onLeadStatus('done')
    chat.addAgentDelta('\n\nI found the auth modules and created an implementation plan.')
  })

  // ---- Turn 2 ----
  const t2Start = 4500

  schedule(t2Start, () => {
    flow.onNewTurn('Now add JWT')
    chat.addUserMessage('Now add JWT')
    flow.onLeadStatus('running')
  })

  schedule(t2Start + 400, () => {
    chat.addAgentDelta('I\'ll implement JWT authentication...')
  })

  schedule(t2Start + 600, () => {
    flow.onSubagentSpawned('code-1', 'Code', 'Implement JWT token utilities')
    chat.addSubagentMessage('code-1', 'Code', 'Implement JWT token utilities')
  })

  schedule(t2Start + 900, () => {
    flow.onSubagentSpawned('explore-2', 'Explore', 'Find existing session handling')
    chat.addSubagentMessage('explore-2', 'Explore', 'Find existing session handling')
  })

  schedule(t2Start + 1200, () => {
    flow.onLeadToolCall('read_file')
    chat.addToolMessage('read_file')
  })

  schedule(t2Start + 1800, () => {
    flow.onLeadToolResult('read_file', 'success')
    chat.updateToolStatus('read_file', 'success')
  })

  schedule(t2Start + 2000, () => {
    flow.onSubagentDelta('code-1', 'import jwt from "jsonwebtoken"\n\nexport function signToken(payload)...')
    chat.addSubagentDelta('code-1', 'import jwt from "jsonwebtoken"\n\nexport function signToken(payload)...')
  })

  schedule(t2Start + 2200, () => {
    flow.onTerminalCommand('npm install jsonwebtoken')
    chat.addStatusMessage('Running: npm install jsonwebtoken')
  })

  schedule(t2Start + 2600, () => {
    flow.onSubagentCompleted('explore-2', 'success')
    chat.updateSubagentStatus('explore-2', 'success')
  })

  schedule(t2Start + 2800, () => {
    flow.onTerminalResult('npm install jsonwebtoken', 'success')
  })

  schedule(t2Start + 3200, () => {
    flow.onSubagentCompleted('code-1', 'success')
    chat.updateSubagentStatus('code-1', 'success')
    flow.onLeadStatus('done')
    chat.addAgentDelta('\n\nJWT authentication has been implemented successfully.')
    chat.setProcessing(false)
  })
}

function schedule(ms: number, fn: () => void): void {
  setTimeout(fn, ms)
}
