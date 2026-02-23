import { useState, useEffect } from 'react'

export function AgentsSettings() {
  const [agents, setAgents] = useState<Array<{ agentType: string; whenToUse: string; model: string; source: string; toolCount: number | null; maxTurns: number }>>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!window.api?.agents?.list) { setLoading(false); return }
    window.api.agents.list().then((list) => {
      setAgents(list)
      setLoading(false)
    }).catch(() => setLoading(false))
  }, [])

  if (loading) {
    return <p className="text-sm text-gray-500">Loading agents...</p>
  }

  if (agents.length === 0) {
    return (
      <div className="space-y-3">
        <p className="text-sm text-gray-400">
          No custom agents defined. Add <code className="text-xs bg-gray-800 px-1 py-0.5 rounded font-mono">.md</code> files to <code className="text-xs bg-gray-800 px-1 py-0.5 rounded font-mono">.copilot/agents/</code> to create agents.
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-2">
      {agents.map((agent) => (
        <div key={agent.agentType} className="px-3 py-2.5 bg-gray-800 rounded-lg">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium text-gray-200">{agent.agentType}</span>
            <span className="text-[10px] px-1.5 py-0.5 bg-gray-700 rounded text-gray-400">{agent.source}</span>
          </div>
          <div className="text-xs text-gray-500 mt-1">{agent.whenToUse}</div>
          <div className="flex items-center gap-3 mt-1.5 text-[11px] text-gray-600">
            <span>Model: {agent.model}</span>
            {agent.toolCount != null && <span>{agent.toolCount} tools</span>}
            <span>Max {agent.maxTurns} turns</span>
          </div>
        </div>
      ))}
    </div>
  )
}
