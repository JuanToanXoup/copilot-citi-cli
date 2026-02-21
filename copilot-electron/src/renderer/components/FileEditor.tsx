import { useState, useEffect, useCallback, useRef } from 'react'

export interface OpenFile {
  path: string
  name: string
}

interface FileEditorProps {
  openFiles: OpenFile[]
  activeFile: string | null
  onSelectFile: (path: string) => void
  onCloseFile: (path: string) => void
}

interface FileBuffer {
  original: string
  content: string
  dirty: boolean
}

const LINE_HEIGHT = 20
const GUTTER_WIDTH = 48

// Cache file reads so repeated opens are instant
const fileCache = new Map<string, string>()

export async function prefetchFile(filePath: string): Promise<string | null> {
  if (fileCache.has(filePath)) return fileCache.get(filePath)!
  const text = await loadFile(filePath)
  if (text !== null) fileCache.set(filePath, text)
  return text
}

export function FileEditor({ openFiles, activeFile, onSelectFile, onCloseFile }: FileEditorProps) {
  const [buffers, setBuffers] = useState<Record<string, FileBuffer>>({})
  const editorRef = useRef<HTMLPreElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const gutterRef = useRef<HTMLDivElement>(null)

  const activeBuffer = activeFile ? buffers[activeFile] : null

  // Load file content when a new file becomes active
  useEffect(() => {
    if (!activeFile) return
    if (buffers[activeFile]) return

    let cancelled = false
    prefetchFile(activeFile).then((text) => {
      if (cancelled || text === null) return
      setBuffers((prev) => ({
        ...prev,
        [activeFile]: { original: text, content: text, dirty: false },
      }))
    })
    return () => { cancelled = true }
  }, [activeFile, buffers])

  // Sync contentEditable text into the pre element when buffer changes externally (file load / tab switch)
  useEffect(() => {
    if (!editorRef.current || !activeBuffer) return
    // Only set textContent if it differs to avoid clobbering cursor position
    if (editorRef.current.textContent !== activeBuffer.content) {
      editorRef.current.textContent = activeBuffer.content
    }
  }, [activeFile, activeBuffer?.content])

  // Clean up buffers when files are closed
  useEffect(() => {
    const openPaths = new Set(openFiles.map((f) => f.path))
    setBuffers((prev) => {
      const next: Record<string, FileBuffer> = {}
      for (const [path, buf] of Object.entries(prev)) {
        if (openPaths.has(path)) next[path] = buf
      }
      return next
    })
  }, [openFiles])

  const handleInput = useCallback(() => {
    if (!activeFile || !editorRef.current) return
    const newContent = editorRef.current.textContent ?? ''
    setBuffers((prev) => {
      const buf = prev[activeFile]
      if (!buf) return prev
      return {
        ...prev,
        [activeFile]: { ...buf, content: newContent, dirty: newContent !== buf.original },
      }
    })
  }, [activeFile])

  const handleSave = useCallback(async () => {
    if (!activeFile || !activeBuffer?.dirty) return
    const success = await saveFile(activeFile, activeBuffer.content)
    if (success) {
      setBuffers((prev) => {
        const buf = prev[activeFile]
        if (!buf) return prev
        return {
          ...prev,
          [activeFile]: { ...buf, original: buf.content, dirty: false },
        }
      })
    }
  }, [activeFile, activeBuffer])

  // Cmd+S to save
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 's') {
        e.preventDefault()
        handleSave()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [handleSave])

  // Sync gutter scroll with main scroll container
  const handleScroll = useCallback(() => {
    if (scrollRef.current && gutterRef.current) {
      gutterRef.current.scrollTop = scrollRef.current.scrollTop
    }
  }, [])

  if (openFiles.length === 0) {
    return (
      <div className="flex items-center justify-center bg-gray-950 h-full">
        <div className="text-center">
          <div className="text-gray-600 text-sm">No files open</div>
          <div className="text-gray-700 text-xs mt-1">Click a file in the explorer to view it</div>
        </div>
      </div>
    )
  }

  const lines = activeBuffer ? activeBuffer.content.split('\n') : []

  return (
    <div className="flex flex-col bg-gray-950 overflow-hidden h-full">
      {/* Tab bar */}
      <div className="flex items-center bg-gray-900 border-b border-gray-800 shrink-0 overflow-x-auto">
        {openFiles.map((file) => {
          const buf = buffers[file.path]
          const isDirty = buf?.dirty ?? false
          return (
            <div
              key={file.path}
              onClick={() => onSelectFile(file.path)}
              className={`flex items-center gap-1.5 px-3 py-1.5 text-xs cursor-pointer border-r border-gray-800 shrink-0 transition-colors ${
                activeFile === file.path
                  ? 'bg-gray-950 text-gray-200 border-t-2 border-t-blue-500'
                  : 'bg-gray-900 text-gray-500 hover:text-gray-300 border-t-2 border-t-transparent'
              }`}
            >
              <span className="truncate max-w-[120px]">
                {file.name}
                {isDirty && <span className="text-gray-500 ml-0.5">&bull;</span>}
              </span>
              <button
                onClick={(e) => {
                  e.stopPropagation()
                  onCloseFile(file.path)
                }}
                className="text-gray-600 hover:text-gray-300 transition-colors ml-1"
              >
                <svg width={12} height={12} viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth={1.5}>
                  <line x1={3} y1={3} x2={9} y2={9} />
                  <line x1={9} y1={3} x2={3} y2={9} />
                </svg>
              </button>
            </div>
          )
        })}
      </div>

      {/* Editor area */}
      <div className="flex-1 overflow-hidden font-mono text-[13px]" style={{ lineHeight: `${LINE_HEIGHT}px` }}>
        {!activeBuffer ? (
          <div className="flex items-center justify-center h-full text-gray-600 text-sm">
            Unable to read file
          </div>
        ) : (
          <div className="flex h-full">
            {/* Line number gutter */}
            <div
              ref={gutterRef}
              className="shrink-0 overflow-hidden select-none bg-gray-900/50 border-r border-gray-800"
              style={{ width: `${GUTTER_WIDTH}px` }}
            >
              {lines.map((_, i) => (
                <div
                  key={i}
                  className="text-right text-gray-600 pr-2"
                  style={{ height: `${LINE_HEIGHT}px` }}
                >
                  {i + 1}
                </div>
              ))}
            </div>
            {/* Code area */}
            <div
              ref={scrollRef}
              className="flex-1 overflow-auto"
              onScroll={handleScroll}
            >
              <pre
                ref={editorRef}
                contentEditable
                suppressContentEditableWarning
                onInput={handleInput}
                spellCheck={false}
                className="text-gray-300 outline-none min-h-full pr-4"
                style={{ tabSize: 2, whiteSpace: 'pre', marginLeft: '0.20px' }}
              />
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

const BINARY_EXTENSIONS = new Set([
  '.zip', '.gz', '.tar', '.rar', '.7z', '.bz2', '.xz',
  '.png', '.jpg', '.jpeg', '.gif', '.bmp', '.ico', '.webp', '.svg',
  '.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx',
  '.exe', '.dll', '.so', '.dylib', '.bin', '.dat',
  '.class', '.jar', '.war', '.ear',
  '.woff', '.woff2', '.ttf', '.otf', '.eot',
  '.mp3', '.mp4', '.wav', '.avi', '.mov', '.mkv', '.flac',
  '.DS_Store',
])

function isBinaryFile(filePath: string): boolean {
  const dot = filePath.lastIndexOf('.')
  if (dot === -1) return false
  return BINARY_EXTENSIONS.has(filePath.slice(dot).toLowerCase())
}

async function loadFile(filePath: string): Promise<string | null> {
  if (isBinaryFile(filePath)) return null
  if (window.api?.fs?.readFile) {
    return window.api.fs.readFile(filePath)
  }
  return null
}

async function saveFile(filePath: string, content: string): Promise<boolean> {
  if (window.api?.fs?.writeFile) {
    return window.api.fs.writeFile(filePath, content)
  }
  return false
}
