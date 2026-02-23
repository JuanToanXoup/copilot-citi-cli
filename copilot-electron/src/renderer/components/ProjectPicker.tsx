import { useState, useEffect } from 'react'

interface ProjectPickerProps {
  onSelect: (path: string) => void
}

const RECENT_PROJECTS_KEY = 'copilot-recent-projects'
const MAX_RECENT = 5

function getRecentProjects(): string[] {
  try {
    const data = localStorage.getItem(RECENT_PROJECTS_KEY)
    return data ? JSON.parse(data) : []
  } catch {
    return []
  }
}

export function addRecentProject(path: string): void {
  const recent = getRecentProjects().filter((p) => p !== path)
  recent.unshift(path)
  if (recent.length > MAX_RECENT) recent.length = MAX_RECENT
  localStorage.setItem(RECENT_PROJECTS_KEY, JSON.stringify(recent))
}

export function ProjectPicker({ onSelect }: ProjectPickerProps) {
  const [recentProjects, setRecentProjects] = useState<string[]>([])

  useEffect(() => {
    setRecentProjects(getRecentProjects())
  }, [])

  const handleOpenFolder = async () => {
    // Use native Electron dialog if available
    if (window.api?.dialog?.openDirectory) {
      const path = await window.api.dialog.openDirectory()
      if (path) {
        addRecentProject(path)
        onSelect(path)
      }
      return
    }

    // Browser fallback: File System Access API (Chromium)
    if ('showDirectoryPicker' in window) {
      try {
        const handle = await (window as any).showDirectoryPicker()
        addRecentProject(handle.name)
        onSelect(handle.name)
      } catch {
        // User cancelled
      }
      return
    }

    // Last resort
    const path = window.prompt('Enter project path:')
    if (path) {
      addRecentProject(path)
      onSelect(path)
    }
  }

  const handleSelectRecent = (path: string) => {
    addRecentProject(path)
    onSelect(path)
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-gray-950">
      <div className="w-[440px] text-center">
        <h1 className="text-2xl font-semibold text-gray-100 mb-2">
          Copilot Desktop
        </h1>
        <p className="text-sm text-gray-400 mb-8">
          Open a project folder to get started
        </p>

        <button
          onClick={handleOpenFolder}
          className="px-6 py-3 text-sm font-medium bg-blue-600 text-white rounded-lg
                     hover:bg-blue-500 transition-colors shadow-lg"
        >
          Open Folder
        </button>

        <div className="mt-10">
          <div className="text-xs text-gray-600 uppercase tracking-wide mb-3">
            Recent Projects
          </div>
          {recentProjects.length === 0 ? (
            <div className="text-sm text-gray-500">
              No recent projects
            </div>
          ) : (
            <div className="space-y-1">
              {recentProjects.map((path) => {
                const name = path.split('/').pop() || path
                return (
                  <button
                    key={path}
                    onClick={() => handleSelectRecent(path)}
                    className="w-full text-left px-4 py-2 rounded-lg text-sm
                               hover:bg-gray-800 transition-colors group"
                  >
                    <div className="text-gray-300 group-hover:text-white font-medium">{name}</div>
                    <div className="text-xs text-gray-600 truncate">{path}</div>
                  </button>
                )
              })}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
