import { useState, useEffect, useCallback } from 'react'
import { useSettingsStore } from '../stores/settings-store'
import { FolderIcon, FolderOpenIcon, getFileIcon, getFolderColor } from './FileIcons'

interface FileEntry {
  name: string
  path: string
  isDirectory: boolean
}

declare global {
  interface Window {
    api?: {
      dialog: { openDirectory: () => Promise<string | null> }
      fs: { readDirectory: (path: string) => Promise<FileEntry[]> }
    }
  }
}

export function FileTree() {
  const projectPath = useSettingsStore((s) => s.projectPath)
  const [entries, setEntries] = useState<FileEntry[]>([])

  useEffect(() => {
    if (!projectPath) return
    loadDirectory(projectPath).then(setEntries)
  }, [projectPath])

  if (!projectPath) {
    return (
      <div className="p-3 h-full flex flex-col">
        <div className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-3">
          Explorer
        </div>
        <div className="flex-1 flex items-center justify-center">
          <p className="text-xs text-gray-600 text-center px-4">
            Open a project to see files
          </p>
        </div>
      </div>
    )
  }

  const projectName = projectPath.split('/').pop() || projectPath

  return (
    <div className="py-2 h-full flex flex-col select-none">
      <div className="text-[11px] font-semibold text-gray-500 uppercase tracking-wide mb-1 px-3">
        {projectName}
      </div>
      <div className="flex-1 overflow-y-auto">
        {entries.map((entry) => (
          <TreeNode key={entry.path} entry={entry} depth={0} />
        ))}
      </div>
    </div>
  )
}

function TreeNode({ entry, depth }: { entry: FileEntry; depth: number }) {
  const [expanded, setExpanded] = useState(false)
  const [children, setChildren] = useState<FileEntry[]>([])
  const [loaded, setLoaded] = useState(false)

  const toggle = useCallback(async () => {
    if (!entry.isDirectory) return
    if (!loaded) {
      const items = await loadDirectory(entry.path)
      setChildren(items)
      setLoaded(true)
    }
    setExpanded((s) => !s)
  }, [entry, loaded])

  const paddingLeft = depth * 16 + 8

  if (entry.isDirectory) {
    const color = getFolderColor(entry.name)
    return (
      <>
        <div
          onClick={toggle}
          className="flex items-center gap-1 py-[2px] pr-2 hover:bg-[#2b2d30] cursor-pointer transition-colors"
          style={{ paddingLeft: `${paddingLeft}px` }}
        >
          <span className="text-[9px] text-gray-500 w-3 text-center shrink-0">
            {expanded ? '▾' : '▸'}
          </span>
          <span className="shrink-0">
            {expanded ? <FolderOpenIcon color={color} /> : <FolderIcon color={color} />}
          </span>
          <span className="text-[13px] text-gray-300 truncate">{entry.name}</span>
        </div>
        {expanded && children.map((child) => (
          <TreeNode key={child.path} entry={child} depth={depth + 1} />
        ))}
      </>
    )
  }

  const FileIconComponent = getFileIcon(entry.name)
  return (
    <div
      className="flex items-center gap-1 py-[2px] pr-2 hover:bg-[#2b2d30] cursor-pointer transition-colors"
      style={{ paddingLeft: `${paddingLeft}px` }}
    >
      <span className="w-3 shrink-0" />
      <span className="shrink-0">
        <FileIconComponent />
      </span>
      <span className="text-[13px] text-gray-400 truncate">{entry.name}</span>
    </div>
  )
}

async function loadDirectory(dirPath: string): Promise<FileEntry[]> {
  if (window.api?.fs?.readDirectory) {
    return window.api.fs.readDirectory(dirPath)
  }
  return []
}
