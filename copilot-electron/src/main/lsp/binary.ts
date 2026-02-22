import { existsSync, readdirSync, statSync } from 'fs'
import { join } from 'path'
import { homedir } from 'os'

/**
 * Discover the copilot-language-server binary.
 * Searches JetBrains plugin directories and VS Code extensions,
 * returning the most recently modified match.
 */
export function findCopilotBinary(): string | null {
  const home = homedir()
  const platform = process.platform
  const arch = process.arch === 'arm64' ? 'arm64' : 'x64'
  const binaryName = platform === 'win32' ? 'copilot-language-server.exe' : 'copilot-language-server'

  const candidates: Array<{ dir: string; subdirPattern: RegExp; pathSuffix: string }> = []

  if (platform === 'darwin') {
    candidates.push(
      {
        dir: join(home, 'Library/Application Support/JetBrains'),
        subdirPattern: /^.+$/,
        pathSuffix: `plugins/github-copilot-intellij/copilot-agent/native/darwin-${arch}/${binaryName}`,
      },
      {
        dir: join(home, '.vscode/extensions'),
        subdirPattern: /^github\.copilot-/,
        pathSuffix: `dist/${binaryName}`,
      },
    )
  } else if (platform === 'linux') {
    candidates.push(
      {
        dir: join(home, '.local/share/JetBrains'),
        subdirPattern: /^.+$/,
        pathSuffix: `plugins/github-copilot-intellij/copilot-agent/native/linux-x64/${binaryName}`,
      },
      {
        dir: join(home, '.vscode/extensions'),
        subdirPattern: /^github\.copilot-/,
        pathSuffix: `dist/${binaryName}`,
      },
    )
  } else if (platform === 'win32') {
    const localAppData = process.env.LOCALAPPDATA ?? join(home, 'AppData/Local')
    candidates.push(
      {
        dir: join(localAppData, 'JetBrains'),
        subdirPattern: /^.+$/,
        pathSuffix: `plugins/github-copilot-intellij/copilot-agent/native/win32-x64/${binaryName}`,
      },
      {
        dir: join(home, '.vscode/extensions'),
        subdirPattern: /^github\.copilot-/,
        pathSuffix: `dist/${binaryName}`,
      },
    )
  }

  const matches: Array<{ path: string; mtime: number }> = []

  for (const { dir, subdirPattern, pathSuffix } of candidates) {
    if (!existsSync(dir)) continue
    try {
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        if (!entry.isDirectory() || !subdirPattern.test(entry.name)) continue
        const fullPath = join(dir, entry.name, pathSuffix)
        if (existsSync(fullPath)) {
          matches.push({ path: fullPath, mtime: statSync(fullPath).mtimeMs })
        }
      }
    } catch {
      // Permission errors, etc.
    }
  }

  matches.sort((a, b) => b.mtime - a.mtime)
  return matches[0]?.path ?? null
}
