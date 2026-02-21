interface ProjectPickerProps {
  onSelect: (path: string) => void
}

export function ProjectPicker({ onSelect }: ProjectPickerProps) {
  const handleOpenFolder = () => {
    // In full app: window.api.dialog.openDirectory()
    // For now, simulate with a prompt
    const path = window.prompt('Enter project path:')
    if (path) onSelect(path)
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
          <div className="text-sm text-gray-500">
            No recent projects
          </div>
        </div>
      </div>
    </div>
  )
}
