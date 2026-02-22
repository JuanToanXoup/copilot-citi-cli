import { readFileSync, writeFileSync, readdirSync, statSync, mkdirSync, existsSync } from 'fs'
import { execSync } from 'child_process'
import { join, resolve } from 'path'

/**
 * Execute a built-in tool and return the result as a string.
 * Throws on error.
 */
export function executeBuiltInTool(name: string, input: any): string {
  switch (name) {
    case 'read_file':
      return readFile(input)
    case 'list_dir':
      return listDir(input)
    case 'grep_search':
      return grepSearch(input)
    case 'file_search':
      return fileSearch(input)
    case 'create_file':
      return createFile(input)
    case 'insert_edit_into_file':
      return insertEditIntoFile(input)
    case 'create_directory':
      return createDirectory(input)
    case 'run_in_terminal':
      return runInTerminal(input)
    default:
      return `Unknown tool: ${name}`
  }
}

function readFile(input: { filePath: string; startLineNumberBaseOne?: number; endLineNumberBaseOne?: number }): string {
  const content = readFileSync(input.filePath, 'utf-8')
  const lines = content.split('\n')
  const start = (input.startLineNumberBaseOne ?? 1) - 1
  const end = input.endLineNumberBaseOne ?? lines.length
  return lines.slice(start, end).map((line, i) => `${start + i + 1}\t${line}`).join('\n')
}

function listDir(input: { dirPath: string }): string {
  const entries = readdirSync(input.dirPath, { withFileTypes: true })
  return entries
    .map(e => `${e.isDirectory() ? '[dir]' : '[file]'} ${e.name}`)
    .join('\n')
}

function grepSearch(input: { pattern: string; path?: string; include?: string }): string {
  const args = ['rg', '--no-heading', '-n']
  if (input.include) args.push('--glob', input.include)
  args.push(input.pattern)
  if (input.path) args.push(input.path)

  try {
    return execSync(args.join(' '), { encoding: 'utf-8', maxBuffer: 1024 * 1024 }).trim()
  } catch (e: any) {
    return e.stdout?.trim() ?? 'No matches found'
  }
}

function fileSearch(input: { pattern: string; path?: string }): string {
  const searchPath = input.path ?? '.'
  try {
    return execSync(`find ${searchPath} -name "${input.pattern}" -type f 2>/dev/null | head -50`, {
      encoding: 'utf-8',
      maxBuffer: 1024 * 1024,
    }).trim() || 'No files found'
  } catch {
    return 'No files found'
  }
}

function createFile(input: { filePath: string; content: string }): string {
  const dir = input.filePath.substring(0, input.filePath.lastIndexOf('/'))
  if (dir && !existsSync(dir)) mkdirSync(dir, { recursive: true })
  writeFileSync(input.filePath, input.content, 'utf-8')
  return `Created ${input.filePath}`
}

function insertEditIntoFile(input: { filePath: string; text: string; startLine?: number; endLine?: number }): string {
  const content = readFileSync(input.filePath, 'utf-8')
  const lines = content.split('\n')

  if (input.startLine != null) {
    const start = input.startLine - 1
    const end = input.endLine ?? input.startLine
    lines.splice(start, end - start, ...input.text.split('\n'))
  } else {
    // Append
    lines.push(input.text)
  }

  writeFileSync(input.filePath, lines.join('\n'), 'utf-8')
  return `Edited ${input.filePath}`
}

function createDirectory(input: { dirPath: string }): string {
  mkdirSync(input.dirPath, { recursive: true })
  return `Created directory ${input.dirPath}`
}

function runInTerminal(input: { command: string; cwd?: string }): string {
  try {
    return execSync(input.command, {
      encoding: 'utf-8',
      cwd: input.cwd,
      maxBuffer: 5 * 1024 * 1024,
      timeout: 120_000,
    }).trim()
  } catch (e: any) {
    return `Exit code ${e.status ?? 1}\n${e.stdout ?? ''}${e.stderr ?? ''}`
  }
}
