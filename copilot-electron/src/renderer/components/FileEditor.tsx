import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import Prism from 'prismjs'
import 'prismjs/components/prism-typescript'
import 'prismjs/components/prism-javascript'
import 'prismjs/components/prism-jsx'
import 'prismjs/components/prism-tsx'
import 'prismjs/components/prism-css'
import 'prismjs/components/prism-json'
import 'prismjs/components/prism-markdown'
import 'prismjs/components/prism-bash'
import 'prismjs/components/prism-yaml'
import 'prismjs/components/prism-python'
import 'prismjs/components/prism-java'
import 'prismjs/components/prism-kotlin'
import 'prismjs/components/prism-go'
import 'prismjs/components/prism-rust'
import 'prismjs/components/prism-sql'
import 'prismjs/components/prism-toml'
import 'prismjs/components/prism-xml-doc'
import 'prismjs/components/prism-markup'

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

/** Map file extension to Prism language key */
function detectLanguage(filePath: string): string {
  const ext = filePath.slice(filePath.lastIndexOf('.')).toLowerCase()
  const map: Record<string, string> = {
    '.ts': 'typescript', '.tsx': 'tsx', '.js': 'javascript', '.jsx': 'jsx',
    '.css': 'css', '.scss': 'css', '.json': 'json', '.md': 'markdown',
    '.sh': 'bash', '.bash': 'bash', '.zsh': 'bash',
    '.yml': 'yaml', '.yaml': 'yaml', '.py': 'python',
    '.java': 'java', '.kt': 'kotlin', '.kts': 'kotlin',
    '.go': 'go', '.rs': 'rust', '.sql': 'sql',
    '.toml': 'toml', '.xml': 'markup', '.html': 'markup', '.svg': 'markup',
  }
  return map[ext] ?? 'plaintext'
}

export function FileEditor({ openFiles, activeFile, onSelectFile, onCloseFile }: FileEditorProps) {
  const [buffers, setBuffers] = useState<Record<string, FileBuffer>>({})
  const [diffMode, setDiffMode] = useState(false)
  const [findOpen, setFindOpen] = useState(false)
  const [findQuery, setFindQuery] = useState('')
  const [replaceQuery, setReplaceQuery] = useState('')
  const [matchIndex, setMatchIndex] = useState(0)
  const editorRef = useRef<HTMLPreElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const gutterRef = useRef<HTMLDivElement>(null)
  const findInputRef = useRef<HTMLInputElement>(null)

  const activeBuffer = activeFile ? buffers[activeFile] : null
  const lang = activeFile ? detectLanguage(activeFile) : 'plaintext'

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

  // Reset modes on tab switch
  useEffect(() => {
    setDiffMode(false)
    setFindOpen(false)
  }, [activeFile])

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

  // Undo/redo history
  const undoStackRef = useRef<Map<string, string[]>>(new Map())
  const redoStackRef = useRef<Map<string, string[]>>(new Map())

  const pushUndo = useCallback((filePath: string, content: string) => {
    const stack = undoStackRef.current.get(filePath) ?? []
    if (stack[stack.length - 1] !== content) {
      stack.push(content)
      if (stack.length > 100) stack.shift()
      undoStackRef.current.set(filePath, stack)
    }
    redoStackRef.current.set(filePath, [])
  }, [])

  const handleTextareaChange = useCallback((e: React.ChangeEvent<HTMLTextAreaElement>) => {
    if (!activeFile) return
    const newContent = e.target.value
    const buf = buffers[activeFile]
    if (buf) pushUndo(activeFile, buf.content)
    setBuffers((prev) => {
      const b = prev[activeFile]
      if (!b) return prev
      return {
        ...prev,
        [activeFile]: { ...b, content: newContent, dirty: newContent !== b.original },
      }
    })
  }, [activeFile, buffers, pushUndo])

  // Handle Tab key in textarea (insert tab instead of losing focus)
  const handleTextareaKeyDown = useCallback((e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Tab') {
      e.preventDefault()
      const ta = e.currentTarget
      const start = ta.selectionStart
      const end = ta.selectionEnd
      const value = ta.value
      const newValue = value.substring(0, start) + '  ' + value.substring(end)
      // Trigger a synthetic change
      const nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value')!.set!
      nativeInputValueSetter.call(ta, newValue)
      ta.dispatchEvent(new Event('input', { bubbles: true }))
      // Restore cursor
      requestAnimationFrame(() => {
        ta.selectionStart = ta.selectionEnd = start + 2
      })
    }
  }, [])

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

  const handleUndo = useCallback(() => {
    if (!activeFile) return
    const stack = undoStackRef.current.get(activeFile) ?? []
    if (stack.length === 0) return
    const prev = stack.pop()!
    undoStackRef.current.set(activeFile, stack)
    const redo = redoStackRef.current.get(activeFile) ?? []
    const buf = buffers[activeFile]
    if (buf) redo.push(buf.content)
    redoStackRef.current.set(activeFile, redo)
    setBuffers((s) => {
      const b = s[activeFile]
      if (!b) return s
      return { ...s, [activeFile]: { ...b, content: prev, dirty: prev !== b.original } }
    })
  }, [activeFile, buffers])

  const handleRedo = useCallback(() => {
    if (!activeFile) return
    const stack = redoStackRef.current.get(activeFile) ?? []
    if (stack.length === 0) return
    const next = stack.pop()!
    redoStackRef.current.set(activeFile, stack)
    const undo = undoStackRef.current.get(activeFile) ?? []
    const buf = buffers[activeFile]
    if (buf) undo.push(buf.content)
    undoStackRef.current.set(activeFile, undo)
    setBuffers((s) => {
      const b = s[activeFile]
      if (!b) return s
      return { ...s, [activeFile]: { ...b, content: next, dirty: next !== b.original } }
    })
  }, [activeFile, buffers])

  // Keyboard shortcuts: Cmd+S, Cmd+F, Cmd+Z, Cmd+Shift+Z
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const meta = e.metaKey || e.ctrlKey
      if (meta && e.key === 's') {
        e.preventDefault()
        handleSave()
      } else if (meta && e.key === 'f') {
        e.preventDefault()
        setFindOpen(true)
        setTimeout(() => findInputRef.current?.focus(), 50)
      } else if (meta && e.shiftKey && e.key === 'z') {
        e.preventDefault()
        handleRedo()
      } else if (meta && e.key === 'z') {
        e.preventDefault()
        handleUndo()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [handleSave, handleUndo, handleRedo])

  // Sync gutter scroll with main scroll container
  const handleScroll = useCallback(() => {
    if (scrollRef.current && gutterRef.current) {
      gutterRef.current.scrollTop = scrollRef.current.scrollTop
    }
  }, [])

  // Find matches
  const findMatches = useMemo(() => {
    if (!findQuery || !activeBuffer) return []
    const matches: number[] = []
    const lower = activeBuffer.content.toLowerCase()
    const queryLower = findQuery.toLowerCase()
    let idx = lower.indexOf(queryLower)
    while (idx !== -1) {
      matches.push(idx)
      idx = lower.indexOf(queryLower, idx + 1)
    }
    return matches
  }, [findQuery, activeBuffer?.content])

  const handleFindNext = useCallback(() => {
    if (findMatches.length === 0) return
    setMatchIndex((i) => (i + 1) % findMatches.length)
  }, [findMatches])

  const handleFindPrev = useCallback(() => {
    if (findMatches.length === 0) return
    setMatchIndex((i) => (i - 1 + findMatches.length) % findMatches.length)
  }, [findMatches])

  const handleReplace = useCallback(() => {
    if (!activeFile || findMatches.length === 0 || !activeBuffer) return
    const idx = findMatches[matchIndex]
    const before = activeBuffer.content.slice(0, idx)
    const after = activeBuffer.content.slice(idx + findQuery.length)
    const newContent = before + replaceQuery + after
    setBuffers((prev) => ({
      ...prev,
      [activeFile]: { ...prev[activeFile], content: newContent, dirty: newContent !== prev[activeFile].original },
    }))
  }, [activeFile, activeBuffer, findMatches, matchIndex, findQuery, replaceQuery])

  const handleReplaceAll = useCallback(() => {
    if (!activeFile || !activeBuffer || !findQuery) return
    const newContent = activeBuffer.content.split(findQuery).join(replaceQuery)
    setBuffers((prev) => ({
      ...prev,
      [activeFile]: { ...prev[activeFile], content: newContent, dirty: newContent !== prev[activeFile].original },
    }))
  }, [activeFile, activeBuffer, findQuery, replaceQuery])

  // Highlighted HTML (always computed, used in both view and edit modes)
  const highlightedHtml = useMemo(() => {
    if (!activeBuffer) return ''
    const grammar = Prism.languages[lang]
    if (!grammar) return escapeHtml(activeBuffer.content)
    try {
      return Prism.highlight(activeBuffer.content, grammar, lang)
    } catch {
      return escapeHtml(activeBuffer.content)
    }
  }, [activeBuffer?.content, lang])

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
        {/* Editor toolbar buttons */}
        {activeBuffer && activeBuffer.dirty && (
          <div className="ml-auto flex items-center gap-1 px-2 shrink-0">
            <button
              onClick={() => setDiffMode((s) => !s)}
              className={`text-[10px] px-2 py-0.5 rounded transition-colors ${
                diffMode ? 'bg-blue-600 text-white' : 'text-gray-500 hover:text-gray-300 bg-gray-800'
              }`}
            >
              Diff
            </button>
          </div>
        )}
      </div>

      {/* Find/Replace bar */}
      {findOpen && (
        <FindReplaceBar
          findQuery={findQuery}
          replaceQuery={replaceQuery}
          matchCount={findMatches.length}
          matchIndex={matchIndex}
          findInputRef={findInputRef}
          onFindChange={setFindQuery}
          onReplaceChange={setReplaceQuery}
          onNext={handleFindNext}
          onPrev={handleFindPrev}
          onReplace={handleReplace}
          onReplaceAll={handleReplaceAll}
          onClose={() => { setFindOpen(false); setFindQuery(''); setReplaceQuery('') }}
        />
      )}

      {/* Editor area */}
      <div className="flex-1 overflow-hidden font-mono text-[13px]" style={{ lineHeight: `${LINE_HEIGHT}px` }}>
        {!activeBuffer ? (
          <div className="flex items-center justify-center h-full text-gray-600 text-sm">
            Unable to read file
          </div>
        ) : diffMode ? (
          <DiffView original={activeBuffer.original} modified={activeBuffer.content} />
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
            {/* Code area — single scroll container */}
            <div
              ref={scrollRef}
              className="flex-1 overflow-auto"
              onScroll={handleScroll}
            >
              <div className="relative" style={{ minHeight: '100%' }}>
                {/* Highlighted code layer */}
                <pre
                  ref={editorRef}
                  className="text-gray-300 min-h-full m-0"
                  style={{ tabSize: 2, whiteSpace: 'pre', padding: '0 16px 0 4px', fontFamily: 'inherit', fontSize: 'inherit', lineHeight: 'inherit' }}
                  dangerouslySetInnerHTML={{ __html: highlightedHtml }}
                />
                {/* Transparent textarea overlay — sized to content, no own scroll */}
                {activeBuffer && (
                  <textarea
                    ref={textareaRef}
                    value={activeBuffer.content}
                    onChange={handleTextareaChange}
                    onKeyDown={handleTextareaKeyDown}
                    spellCheck={false}
                    className="absolute top-0 left-0 w-full h-full text-transparent caret-white bg-transparent outline-none resize-none"
                    style={{
                      tabSize: 2,
                      whiteSpace: 'pre',
                      padding: '0 16px 0 4px',
                      margin: 0,
                      border: 'none',
                      fontFamily: 'inherit',
                      fontSize: 'inherit',
                      lineHeight: 'inherit',
                      letterSpacing: 'inherit',
                      overflow: 'hidden',
                    }}
                  />
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  Find/Replace Bar                                                    */
/* ------------------------------------------------------------------ */

function FindReplaceBar({ findQuery, replaceQuery, matchCount, matchIndex, findInputRef, onFindChange, onReplaceChange, onNext, onPrev, onReplace, onReplaceAll, onClose }: {
  findQuery: string
  replaceQuery: string
  matchCount: number
  matchIndex: number
  findInputRef: React.RefObject<HTMLInputElement | null>
  onFindChange: (v: string) => void
  onReplaceChange: (v: string) => void
  onNext: () => void
  onPrev: () => void
  onReplace: () => void
  onReplaceAll: () => void
  onClose: () => void
}) {
  return (
    <div className="flex items-center gap-2 px-3 py-1.5 bg-gray-900 border-b border-gray-800 shrink-0">
      <input
        ref={findInputRef}
        value={findQuery}
        onChange={(e) => onFindChange(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') onNext()
          if (e.key === 'Escape') onClose()
        }}
        placeholder="Find"
        className="w-40 px-2 py-0.5 text-xs bg-gray-800 border border-gray-700 rounded text-gray-200 placeholder-gray-600 outline-none focus:border-blue-500"
      />
      <span className="text-[10px] text-gray-500 w-12 text-center">
        {matchCount > 0 ? `${matchIndex + 1}/${matchCount}` : 'No results'}
      </span>
      <button onClick={onPrev} className="text-gray-500 hover:text-white text-xs px-1" title="Previous">&#9650;</button>
      <button onClick={onNext} className="text-gray-500 hover:text-white text-xs px-1" title="Next">&#9660;</button>
      <div className="w-px h-4 bg-gray-700" />
      <input
        value={replaceQuery}
        onChange={(e) => onReplaceChange(e.target.value)}
        onKeyDown={(e) => { if (e.key === 'Escape') onClose() }}
        placeholder="Replace"
        className="w-32 px-2 py-0.5 text-xs bg-gray-800 border border-gray-700 rounded text-gray-200 placeholder-gray-600 outline-none focus:border-blue-500"
      />
      <button onClick={onReplace} className="text-[10px] text-gray-500 hover:text-white px-1.5 py-0.5 bg-gray-800 rounded">Replace</button>
      <button onClick={onReplaceAll} className="text-[10px] text-gray-500 hover:text-white px-1.5 py-0.5 bg-gray-800 rounded">All</button>
      <button onClick={onClose} className="text-gray-500 hover:text-white text-xs ml-auto">&#10005;</button>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  Diff View                                                           */
/* ------------------------------------------------------------------ */

interface DiffLine {
  type: 'same' | 'added' | 'removed'
  text: string
  lineNum?: number
}

function computeDiff(original: string, modified: string): { left: DiffLine[]; right: DiffLine[] } {
  const origLines = original.split('\n')
  const modLines = modified.split('\n')
  const left: DiffLine[] = []
  const right: DiffLine[] = []

  const maxLen = Math.max(origLines.length, modLines.length)
  let oi = 0, mi = 0

  while (oi < origLines.length || mi < modLines.length) {
    if (oi < origLines.length && mi < modLines.length && origLines[oi] === modLines[mi]) {
      left.push({ type: 'same', text: origLines[oi], lineNum: oi + 1 })
      right.push({ type: 'same', text: modLines[mi], lineNum: mi + 1 })
      oi++
      mi++
    } else if (oi < origLines.length && (mi >= modLines.length || !modLines.slice(mi, mi + 5).includes(origLines[oi]))) {
      left.push({ type: 'removed', text: origLines[oi], lineNum: oi + 1 })
      right.push({ type: 'removed', text: '' })
      oi++
    } else if (mi < modLines.length) {
      left.push({ type: 'added', text: '' })
      right.push({ type: 'added', text: modLines[mi], lineNum: mi + 1 })
      mi++
    }
  }

  return { left, right }
}

function DiffView({ original, modified }: { original: string; modified: string }) {
  const { left, right } = useMemo(() => computeDiff(original, modified), [original, modified])

  return (
    <div className="flex h-full overflow-auto">
      {/* Left: original */}
      <div className="flex-1 border-r border-gray-800 overflow-auto">
        <div className="text-[10px] text-gray-500 px-3 py-1 bg-gray-900 border-b border-gray-800 sticky top-0">Original</div>
        {left.map((line, i) => (
          <div
            key={i}
            className={`flex text-[13px] font-mono ${
              line.type === 'removed' ? 'bg-red-950/30' : line.type === 'added' ? 'bg-green-950/10' : ''
            }`}
            style={{ height: `${LINE_HEIGHT}px` }}
          >
            <span className="w-10 text-right text-gray-600 pr-2 shrink-0 select-none">{line.lineNum ?? ''}</span>
            <span className={line.type === 'removed' ? 'text-red-300' : 'text-gray-400'}>{line.text}</span>
          </div>
        ))}
      </div>
      {/* Right: modified */}
      <div className="flex-1 overflow-auto">
        <div className="text-[10px] text-gray-500 px-3 py-1 bg-gray-900 border-b border-gray-800 sticky top-0">Modified</div>
        {right.map((line, i) => (
          <div
            key={i}
            className={`flex text-[13px] font-mono ${
              line.type === 'added' ? 'bg-green-950/30' : line.type === 'removed' ? 'bg-red-950/10' : ''
            }`}
            style={{ height: `${LINE_HEIGHT}px` }}
          >
            <span className="w-10 text-right text-gray-600 pr-2 shrink-0 select-none">{line.lineNum ?? ''}</span>
            <span className={line.type === 'added' ? 'text-green-300' : 'text-gray-400'}>{line.text}</span>
          </div>
        ))}
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  Utilities                                                           */
/* ------------------------------------------------------------------ */

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
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
