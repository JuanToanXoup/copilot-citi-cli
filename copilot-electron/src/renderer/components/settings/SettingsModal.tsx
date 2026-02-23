import { useState } from 'react'
import { ConnectionSettings } from './ConnectionSettings'
import { AgentsSettings } from './AgentsSettings'
import { ToolsSettings } from './ToolsSettings'
import { ThemeSettings } from './ThemeSettings'
import { ChatSettings } from './ChatSettings'
import { KeybindingsSettings } from './KeybindingsSettings'

function SettingsContent({ section }: { section: string }) {
  switch (section) {
    case 'Connection': return <ConnectionSettings />
    case 'Agents': return <AgentsSettings />
    case 'Tools': return <ToolsSettings />
    case 'Theme': return <ThemeSettings />
    case 'Chat': return <ChatSettings />
    case 'Keybindings': return <KeybindingsSettings />
    default:
      return <p className="text-sm text-gray-500">{section} settings will be configured here.</p>
  }
}

export function SettingsModal({ onClose }: { onClose: () => void }) {
  const sections = ['Connection', 'Agents', 'Tools', 'Theme', 'Chat', 'Keybindings'] as const
  const [activeSection, setActiveSection] = useState<string>(sections[0])

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={onClose}>
      <div
        className="w-[720px] h-[520px] bg-gray-900 border border-gray-700 rounded-xl shadow-2xl flex overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Sidebar */}
        <div className="w-44 border-r border-gray-800 p-3 space-y-0.5">
          <div className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2 px-2">
            Settings
          </div>
          {sections.map((s) => (
            <button
              key={s}
              onClick={() => setActiveSection(s)}
              className={`w-full text-left px-2 py-1.5 text-sm rounded transition-colors ${
                activeSection === s
                  ? 'bg-gray-800 text-white'
                  : 'text-gray-400 hover:text-gray-200 hover:bg-gray-800/50'
              }`}
            >
              {s}
            </button>
          ))}
        </div>
        {/* Content */}
        <div className="flex-1 p-6 overflow-y-auto">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold text-gray-100">{activeSection}</h2>
            <button onClick={onClose} className="text-gray-500 hover:text-white text-sm">
              Esc
            </button>
          </div>
          <SettingsContent section={activeSection} />
        </div>
      </div>
    </div>
  )
}
