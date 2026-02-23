export function KeybindingsSettings() {
  const shortcuts = [
    { keys: 'Cmd+K', action: 'Open command palette' },
    { keys: 'Cmd+Enter', action: 'Send message' },
    { keys: 'Cmd+N', action: 'New conversation tab' },
    { keys: 'Cmd+/', action: 'Toggle sidebar' },
    { keys: 'Cmd+\\', action: 'Cycle view modes' },
    { keys: 'Cmd+Shift+P', action: 'Open settings' },
    { keys: 'Cmd+W', action: 'Close current tab' },
    { keys: 'Escape', action: 'Close panels / deselect' },
  ]

  return (
    <div className="space-y-1">
      {shortcuts.map((s) => (
        <div key={s.keys} className="flex items-center justify-between py-1.5">
          <span className="text-sm text-gray-300">{s.action}</span>
          <kbd className="text-xs font-mono bg-gray-800 border border-gray-700 px-2 py-0.5 rounded text-gray-400">
            {s.keys}
          </kbd>
        </div>
      ))}
    </div>
  )
}
