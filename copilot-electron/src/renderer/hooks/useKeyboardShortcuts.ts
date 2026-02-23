import { useEffect } from 'react'

interface KeyboardShortcutHandlers {
  cycleViewMode: () => void
  toggleSidebar: () => void
  toggleSettings: () => void
  togglePalette: () => void
  handleNewTab: () => void
  handleCloseTab: () => void
  handleCloseFile: () => void
  dismissAll: () => void
}

export function useKeyboardShortcuts(handlers: KeyboardShortcutHandlers) {
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const meta = e.metaKey || e.ctrlKey

      if (meta && e.key === '\\') {
        e.preventDefault()
        handlers.cycleViewMode()
      } else if (meta && e.key === '/') {
        e.preventDefault()
        handlers.toggleSidebar()
      } else if (meta && e.key === 'n' && !e.shiftKey) {
        e.preventDefault()
        handlers.handleNewTab()
      } else if (meta && e.shiftKey && e.key === 'p') {
        e.preventDefault()
        handlers.toggleSettings()
      } else if (meta && e.key === 'k') {
        e.preventDefault()
        handlers.togglePalette()
      } else if (meta && e.key === 'w') {
        e.preventDefault()
        handlers.handleCloseTab()
      } else if (meta && e.key === 't') {
        e.preventDefault()
        handlers.handleNewTab()
      } else if (e.key === 'Escape') {
        handlers.dismissAll()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [handlers])
}
