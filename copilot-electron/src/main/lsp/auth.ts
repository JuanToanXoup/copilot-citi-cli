import { readFileSync, existsSync } from 'fs'
import { join } from 'path'
import { homedir } from 'os'

interface AppsJsonEntry {
  oauth_token: string
  user?: string
}

/**
 * Read the GitHub Copilot OAuth token from apps.json.
 * Prefers ghu_ tokens (Copilot user tokens) over gho_ tokens.
 */
export function readCopilotAuth(): { token: string; appId: string; user?: string } | null {
  const configDir = process.platform === 'win32'
    ? join(process.env.APPDATA ?? join(homedir(), 'AppData/Roaming'), 'github-copilot')
    : join(homedir(), '.config/github-copilot')

  const appsPath = join(configDir, 'apps.json')
  if (!existsSync(appsPath)) return null

  try {
    const raw = JSON.parse(readFileSync(appsPath, 'utf-8'))
    const entries: Array<{ key: string; appId: string; entry: AppsJsonEntry }> = []

    for (const [key, value] of Object.entries(raw)) {
      const appId = key.includes(':') ? key.split(':').slice(1).join(':') : 'Iv1.b507a08c87ecfe98'
      entries.push({ key, appId, entry: value as AppsJsonEntry })
    }

    // Prefer ghu_ tokens
    const preferred = entries.find(e => e.entry.oauth_token?.startsWith('ghu_'))
      ?? entries[0]

    if (!preferred) return null

    return {
      token: preferred.entry.oauth_token,
      appId: preferred.appId,
      user: preferred.entry.user,
    }
  } catch {
    return null
  }
}
