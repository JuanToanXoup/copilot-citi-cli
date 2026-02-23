import { writeFileSync, renameSync, unlinkSync } from 'fs'
import { randomBytes } from 'crypto'

/**
 * Write a file atomically: write to a temp file, then rename.
 * Prevents partial reads if the process crashes or a watcher fires mid-write.
 */
export function atomicWriteSync(filePath: string, data: string): void {
  const tmpPath = `${filePath}.${randomBytes(4).toString('hex')}.tmp`
  writeFileSync(tmpPath, data, 'utf-8')
  try {
    renameSync(tmpPath, filePath)
  } catch (e) {
    // Clean up temp file on rename failure
    try { unlinkSync(tmpPath) } catch {}
    throw e
  }
}
