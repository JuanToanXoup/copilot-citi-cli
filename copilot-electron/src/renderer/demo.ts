import { useFlowStore } from './stores/flow-store'

/**
 * Runs a multi-turn demo that showcases the turn-based flow graph.
 *
 * Turn 1: "Explore auth modules" → Lead → 2 subagents (Explore, Plan)
 * Turn 2: "Now add JWT" → Lead · Turn 2 → 2 subagents (Code, Explore)
 */
export function startDemo(): void {
  const store = useFlowStore.getState()
  store.onReset()

  // ---- Turn 1 ----
  const t1Start = 0
  schedule(t1Start, () => {
    store.onNewTurn('Explore auth modules')
    store.onLeadStatus('running')
  })

  schedule(t1Start + 600, () => {
    store.onSubagentSpawned('explore-1', 'Explore', 'Search for auth-related files')
  })

  schedule(t1Start + 900, () => {
    store.onSubagentSpawned('plan-1', 'Plan', 'Create implementation plan for auth')
  })

  schedule(t1Start + 1500, () => {
    store.onSubagentDelta('explore-1', 'Found auth.ts, middleware.ts, session.ts...')
  })

  schedule(t1Start + 2200, () => {
    store.onSubagentCompleted('explore-1', 'success')
  })

  schedule(t1Start + 2800, () => {
    store.onSubagentDelta('plan-1', 'Plan: 1) Add JWT library 2) Create token utils 3) Update middleware')
  })

  schedule(t1Start + 3400, () => {
    store.onSubagentCompleted('plan-1', 'success')
    store.onLeadStatus('done')
  })

  // ---- Turn 2 ----
  const t2Start = 4500

  schedule(t2Start, () => {
    store.onNewTurn('Now add JWT')
    store.onLeadStatus('running')
  })

  schedule(t2Start + 600, () => {
    store.onSubagentSpawned('code-1', 'Code', 'Implement JWT token utilities')
  })

  schedule(t2Start + 900, () => {
    store.onSubagentSpawned('explore-2', 'Explore', 'Find existing session handling')
  })

  schedule(t2Start + 1200, () => {
    store.onLeadToolCall('read_file')
  })

  schedule(t2Start + 1800, () => {
    store.onLeadToolResult('read_file', 'success')
  })

  schedule(t2Start + 2000, () => {
    store.onSubagentDelta('code-1', 'import jwt from "jsonwebtoken"\n\nexport function signToken(payload)...')
  })

  schedule(t2Start + 2600, () => {
    store.onSubagentCompleted('explore-2', 'success')
  })

  schedule(t2Start + 3200, () => {
    store.onSubagentCompleted('code-1', 'success')
    store.onLeadStatus('done')
  })
}

function schedule(ms: number, fn: () => void): void {
  setTimeout(fn, ms)
}
