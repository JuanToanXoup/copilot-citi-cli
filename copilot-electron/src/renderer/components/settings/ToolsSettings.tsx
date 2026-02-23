import { useState, useEffect } from 'react'
import { useSettingsStore, type ToolDef } from '../../stores/settings-store'

function BuiltInToolRow({ tool }: { tool: ToolDef }) {
  const setToolPermission = useSettingsStore((s) => s.setToolPermission)
  const permissions = useSettingsStore((s) => s.toolPermissions)
  const currentPerm = permissions[tool.name] ?? tool.permission

  return (
    <div className="flex items-center gap-2 px-2 py-1.5 rounded hover:bg-gray-800/50 transition-colors">
      <div className="flex-1 min-w-0">
        <div className="text-sm text-gray-200 font-mono">{tool.name}</div>
        <div className="text-[11px] text-gray-500">{tool.description}</div>
      </div>
      <select
        value={currentPerm}
        onChange={(e) => setToolPermission(tool.name, e.target.value as 'auto' | 'confirm' | 'always-ask')}
        className="text-[11px] bg-gray-800 border border-gray-700 rounded px-1.5 py-0.5 text-gray-300 outline-none"
      >
        <option value="auto">Auto</option>
        <option value="confirm">Confirm</option>
        <option value="always-ask">Always Ask</option>
      </select>
      {tool.usageCount > 0 && (
        <span className="text-[10px] text-gray-600">{tool.usageCount}x</span>
      )}
    </div>
  )
}

function AddServerWizard({ onClose, onAdded }: { onClose: () => void; onAdded: () => void }) {
  const [name, setName] = useState('')
  const [type, setType] = useState<'stdio' | 'sse'>('stdio')
  const [command, setCommand] = useState('')
  const [url, setUrl] = useState('')
  const [envVars, setEnvVars] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = async () => {
    if (!name.trim()) { setError('Name is required'); return }
    if (type === 'stdio' && !command.trim()) { setError('Command is required for stdio servers'); return }
    if (type === 'sse' && !url.trim()) { setError('URL is required for SSE servers'); return }
    if (!window.api?.mcp?.addServer) { setError('MCP API not available'); return }

    // Parse env vars (KEY=VALUE per line)
    const env: Record<string, string> = {}
    for (const line of envVars.split('\n')) {
      const trimmed = line.trim()
      if (!trimmed || trimmed.startsWith('#')) continue
      const eqIdx = trimmed.indexOf('=')
      if (eqIdx > 0) {
        env[trimmed.slice(0, eqIdx).trim()] = trimmed.slice(eqIdx + 1).trim()
      }
    }

    // Parse command into command + args
    const parts = command.trim().split(/\s+/)
    const cmd = parts[0] ?? ''
    const args = parts.slice(1)

    setSubmitting(true)
    setError('')
    try {
      const result = await window.api.mcp.addServer({
        name: name.trim(),
        type,
        command: type === 'stdio' ? cmd : undefined,
        args: type === 'stdio' ? args : undefined,
        url: type === 'sse' ? url.trim() : undefined,
        env: Object.keys(env).length > 0 ? env : undefined,
      })
      if (result.success) {
        onAdded()
      } else {
        setError(result.error ?? 'Failed to add server')
      }
    } catch (e: any) {
      setError(e.message ?? 'Unknown error')
    }
    setSubmitting(false)
  }

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50" onClick={onClose}>
      <div
        className="w-[440px] bg-gray-900 border border-gray-700 rounded-xl shadow-2xl overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="px-4 py-3 border-b border-gray-800">
          <h3 className="text-sm font-semibold text-gray-100">Add MCP Server</h3>
        </div>
        <div className="px-4 py-3 space-y-3">
          {/* Name */}
          <div>
            <label className="text-xs text-gray-400 mb-1 block">Server Name</label>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="my-mcp-server"
              className="w-full px-3 py-1.5 text-sm bg-gray-800 border border-gray-700 rounded
                         text-gray-100 placeholder-gray-600 focus:border-blue-500 outline-none"
            />
          </div>

          {/* Type selector */}
          <div>
            <label className="text-xs text-gray-400 mb-1 block">Transport Type</label>
            <div className="flex gap-1">
              <button
                onClick={() => setType('stdio')}
                className={`px-3 py-1.5 text-xs rounded transition-colors ${
                  type === 'stdio' ? 'bg-blue-600 text-white' : 'bg-gray-800 text-gray-400 hover:text-white'
                }`}
              >
                stdio
              </button>
              <button
                onClick={() => setType('sse')}
                className={`px-3 py-1.5 text-xs rounded transition-colors ${
                  type === 'sse' ? 'bg-blue-600 text-white' : 'bg-gray-800 text-gray-400 hover:text-white'
                }`}
              >
                SSE
              </button>
            </div>
          </div>

          {/* Command (stdio) or URL (sse) */}
          {type === 'stdio' ? (
            <div>
              <label className="text-xs text-gray-400 mb-1 block">Command</label>
              <input
                value={command}
                onChange={(e) => setCommand(e.target.value)}
                placeholder="npx -y @modelcontextprotocol/server-filesystem /tmp"
                className="w-full px-3 py-1.5 text-sm bg-gray-800 border border-gray-700 rounded
                           text-gray-100 placeholder-gray-600 focus:border-blue-500 outline-none font-mono"
              />
            </div>
          ) : (
            <div>
              <label className="text-xs text-gray-400 mb-1 block">Server URL</label>
              <input
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                placeholder="http://localhost:3001/sse"
                className="w-full px-3 py-1.5 text-sm bg-gray-800 border border-gray-700 rounded
                           text-gray-100 placeholder-gray-600 focus:border-blue-500 outline-none font-mono"
              />
            </div>
          )}

          {/* Env vars */}
          <div>
            <label className="text-xs text-gray-400 mb-1 block">Environment Variables (one per line, KEY=VALUE)</label>
            <textarea
              value={envVars}
              onChange={(e) => setEnvVars(e.target.value)}
              placeholder="API_KEY=sk-..."
              rows={3}
              className="w-full px-3 py-1.5 text-sm bg-gray-800 border border-gray-700 rounded
                         text-gray-100 placeholder-gray-600 focus:border-blue-500 outline-none font-mono resize-none"
            />
          </div>

          {error && (
            <div className="text-xs text-red-400">{error}</div>
          )}
        </div>
        <div className="flex items-center justify-end gap-2 px-4 py-3 border-t border-gray-800">
          <button
            onClick={onClose}
            className="text-xs px-3 py-1.5 bg-gray-800 border border-gray-700 rounded
                       text-gray-400 hover:text-white transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={submitting}
            className="text-xs px-3 py-1.5 bg-blue-600 border border-blue-500 rounded
                       text-white hover:bg-blue-500 transition-colors disabled:opacity-50"
          >
            {submitting ? 'Adding...' : 'Add Server'}
          </button>
        </div>
      </div>
    </div>
  )
}

export function ToolsSettings() {
  const tools = useSettingsStore((s) => s.tools)
  const [servers, setServers] = useState<Array<{
    name: string; type: 'stdio' | 'sse'; status: string; toolCount: number; error?: string
  }>>([])
  const [showAddWizard, setShowAddWizard] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)

  // Load server list
  useEffect(() => {
    if (!window.api?.mcp?.listServers) return
    window.api.mcp.listServers().then(setServers).catch(() => {})
  }, [refreshKey])

  const handleRemoveServer = async (name: string) => {
    if (!window.api?.mcp?.removeServer) return
    await window.api.mcp.removeServer(name)
    setRefreshKey(k => k + 1)
  }

  // Group tools by source
  const grouped = tools.reduce<Record<string, ToolDef[]>>((acc, tool) => {
    const key = tool.source
    if (!acc[key]) acc[key] = []
    acc[key].push(tool)
    return acc
  }, {})

  return (
    <div className="space-y-4">
      {/* MCP Servers section */}
      <div>
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-semibold text-gray-400 uppercase tracking-wide">MCP Servers</span>
          <button
            onClick={() => setShowAddWizard(true)}
            className="text-xs px-2 py-1 bg-blue-600 text-white rounded hover:bg-blue-500 transition-colors"
          >
            + Add Server
          </button>
        </div>

        {servers.length === 0 ? (
          <p className="text-sm text-gray-500">No MCP servers configured. Add one to extend available tools.</p>
        ) : (
          <div className="space-y-2">
            {servers.map((server) => {
              const statusColor =
                server.status === 'connected' ? 'bg-green-500' :
                server.status === 'error' ? 'bg-red-500' :
                server.status === 'connecting' ? 'bg-yellow-500' :
                'bg-gray-600'
              return (
                <div key={server.name} className="flex items-center gap-3 px-3 py-2 bg-gray-800 rounded-lg">
                  <span className={`inline-block w-2 h-2 rounded-full ${statusColor} shrink-0`} />
                  <div className="flex-1 min-w-0">
                    <div className="text-sm text-gray-200 font-medium">{server.name}</div>
                    <div className="text-[11px] text-gray-500">
                      {server.type.toUpperCase()} &middot; {server.toolCount} tool{server.toolCount !== 1 ? 's' : ''}
                      {server.error && <span className="text-red-400 ml-2">{server.error}</span>}
                    </div>
                  </div>
                  <button
                    onClick={() => handleRemoveServer(server.name)}
                    className="text-xs px-2 py-1 text-red-400 hover:text-red-300 hover:bg-gray-700 rounded transition-colors"
                  >
                    Remove
                  </button>
                </div>
              )
            })}
          </div>
        )}
      </div>

      {/* Built-in tools section */}
      <div className="border-t border-gray-800 pt-4">
        <span className="text-xs font-semibold text-gray-400 uppercase tracking-wide">Built-in Tools</span>
        <div className="mt-2 space-y-1">
          {(grouped['built-in'] ?? []).map((tool) => (
            <BuiltInToolRow key={tool.name} tool={tool} />
          ))}
        </div>
      </div>

      {/* MCP tool lists per server */}
      {Object.entries(grouped)
        .filter(([source]) => source !== 'built-in')
        .map(([source, sourceTools]) => (
          <div key={source} className="border-t border-gray-800 pt-4">
            <span className="text-xs font-semibold text-gray-400 uppercase tracking-wide">{source} Tools</span>
            <div className="mt-2 space-y-1">
              {sourceTools.map((tool) => (
                <div key={tool.name} className="flex items-center gap-2 px-2 py-1 text-sm text-gray-300">
                  <span className="font-mono text-gray-200">{tool.name}</span>
                  <span className="text-xs text-gray-500">{tool.description}</span>
                  {tool.usageCount > 0 && (
                    <span className="text-[10px] text-gray-600 ml-auto">{tool.usageCount}x</span>
                  )}
                </div>
              ))}
            </div>
          </div>
        ))}

      {/* Add Server Wizard modal */}
      {showAddWizard && (
        <AddServerWizard
          onClose={() => setShowAddWizard(false)}
          onAdded={() => { setShowAddWizard(false); setRefreshKey(k => k + 1) }}
        />
      )}
    </div>
  )
}
