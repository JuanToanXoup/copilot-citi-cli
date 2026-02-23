import { useState } from 'react'
import { useSettingsStore } from '../../stores/settings-store'
import { SettingsField } from './SettingsField'

export function ConnectionSettings() {
  const model = useSettingsStore((s) => s.model)
  const proxyUrl = useSettingsStore((s) => s.proxyUrl)
  const caCertPath = useSettingsStore((s) => s.caCertPath)
  const [signingOut, setSigningOut] = useState(false)

  const handleSignOut = async () => {
    setSigningOut(true)
    try {
      await window.api?.auth?.signOut()
    } catch { /* ignore */ }
    setSigningOut(false)
  }

  return (
    <div className="space-y-4">
      <SettingsField label="Model" value={model} onChange={(v) => useSettingsStore.getState().setModel(v)} />
      <SettingsField label="Proxy URL" value={proxyUrl ?? ''} onChange={(v) => useSettingsStore.getState().setProxyUrl(v || null)} placeholder="Auto-detected from system" />
      <SettingsField label="CA Certificate Path" value={caCertPath ?? ''} onChange={(v) => useSettingsStore.getState().setCaCertPath(v || null)} placeholder="/path/to/corporate-ca.pem" />
      <div className="pt-2 border-t border-gray-800">
        <div className="text-xs text-gray-500 mb-2">Authentication</div>
        <div className="flex items-center gap-3">
          <button
            onClick={handleSignOut}
            disabled={signingOut}
            className="text-xs px-3 py-1.5 bg-gray-800 border border-gray-700 rounded
                       text-red-400 hover:text-red-300 hover:border-red-500/50 transition-colors disabled:opacity-50"
          >
            {signingOut ? 'Signing out...' : 'Sign Out'}
          </button>
          <span className="text-xs text-gray-500">Clear stored tokens and re-authenticate</span>
        </div>
      </div>
    </div>
  )
}
