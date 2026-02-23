import { useState, useEffect, useCallback, type ReactElement } from 'react'
import { useSettingsStore } from '../stores/settings-store'
import { FolderIcon, FolderOpenIcon, getFileIcon, getFolderColor } from './FileIcons'

interface FileEntry {
  name: string
  path: string
  isDirectory: boolean
}

interface FileTreeProps {
  onFileSelect?: (path: string, name: string) => void
  gitStatus?: Map<string, string>
  refreshKey?: number
}

export function FileTree({ onFileSelect, gitStatus, refreshKey }: FileTreeProps) {
  const projectPath = useSettingsStore((s) => s.projectPath)
  const [entries, setEntries] = useState<FileEntry[]>([])

  useEffect(() => {
    if (!projectPath) return
    loadDirectory(projectPath).then(setEntries)
  }, [projectPath, refreshKey])

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
          <TreeNode key={entry.path} entry={entry} depth={0} onFileSelect={onFileSelect} gitStatus={gitStatus} />
        ))}
      </div>
    </div>
  )
}

function TreeNode({ entry, depth, onFileSelect, gitStatus }: {
  entry: FileEntry
  depth: number
  onFileSelect?: (path: string, name: string) => void
  gitStatus?: Map<string, string>
}) {
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

  // Phase 9: Get git status for this file
  const fileGitStatus = gitStatus?.get(entry.name) ?? gitStatus?.get(entry.path)
  const gitDot = getGitStatusDot(fileGitStatus)

  if (entry.isDirectory) {
    const color = getFolderColor(entry.name)
    return (
      <>
        <div
          onClick={toggle}
          className="flex items-center gap-1 py-[2px] pr-2 hover:bg-[#2b2d30] cursor-pointer transition-colors"
          style={{ paddingLeft: `${paddingLeft}px` }}
        >
          <svg
            width={10}
            height={10}
            viewBox="0 0 10 10"
            className="shrink-0 text-gray-500"
            style={{ transform: expanded ? 'rotate(90deg)' : 'rotate(0deg)', transition: 'transform 0.15s' }}
          >
            <path d="M3 1.5L7 5L3 8.5" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" fill="none" />
          </svg>
          <span className="shrink-0">
            {expanded ? <FolderOpenIcon color={color} /> : <FolderIcon color={color} />}
          </span>
          <span className="text-[13px] text-gray-300 truncate">{entry.name}</span>
        </div>
        {expanded && (
          <div className="relative">
            <div
              className="absolute top-0 bottom-0 border-l border-[#3c3f41]"
              style={{ left: `${paddingLeft + 5}px` }}
            />
            {children.map((child) => (
              <TreeNode key={child.path} entry={child} depth={depth + 1} onFileSelect={onFileSelect} gitStatus={gitStatus} />
            ))}
          </div>
        )}
      </>
    )
  }

  const FileIconComponent = getFileIcon(entry.name)
  return (
    <div
      onClick={() => onFileSelect?.(entry.path, entry.name)}
      className="flex items-center gap-1 py-[2px] pr-2 hover:bg-[#2b2d30] cursor-pointer transition-colors"
      style={{ paddingLeft: `${paddingLeft}px` }}
    >
      <span className="w-3 shrink-0" />
      <span className="shrink-0">
        <FileIconComponent />
      </span>
      <span className="text-[13px] text-gray-400 truncate flex-1">{entry.name}</span>
      {gitDot}
    </div>
  )
}

function getGitStatusDot(status: string | undefined): ReactElement | null {
  if (!status) return null
  if (status === 'M' || status === 'MM' || status === 'AM') {
    return <span className="inline-block w-1.5 h-1.5 rounded-full bg-yellow-400 shrink-0" title="Modified" />
  }
  if (status === '??' || status === 'A') {
    return <span className="inline-block w-1.5 h-1.5 rounded-full bg-green-400 shrink-0" title="New" />
  }
  if (status === 'D') {
    return <span className="inline-block w-1.5 h-1.5 rounded-full bg-red-400 shrink-0" title="Deleted" />
  }
  return <span className="inline-block w-1.5 h-1.5 rounded-full bg-gray-500 shrink-0" title={status} />
}

async function loadDirectory(dirPath: string): Promise<FileEntry[]> {
  if (window.api?.fs?.readDirectory) {
    return window.api.fs.readDirectory(dirPath)
  }
  return []
}
