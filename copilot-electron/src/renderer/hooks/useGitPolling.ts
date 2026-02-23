import { useState, useEffect } from 'react'

export function useGitPolling() {
  const [gitBranch, setGitBranch] = useState('')
  const [gitChangesCount, setGitChangesCount] = useState(0)
  const [gitStatus, setGitStatus] = useState<Map<string, string>>(new Map())

  useEffect(() => {
    if (!window.api?.git) return

    const poll = async () => {
      try {
        const [branch, status] = await Promise.all([
          window.api.git.branch(),
          window.api.git.status(),
        ])
        setGitBranch(branch)
        setGitChangesCount(status.length)
        const statusMap = new Map<string, string>()
        for (const s of status) {
          statusMap.set(s.file, s.status)
        }
        setGitStatus(statusMap)
      } catch {
        // Git not available
      }
    }

    poll()
    const interval = setInterval(poll, 5000)
    return () => clearInterval(interval)
  }, [])

  return { gitBranch, gitChangesCount, gitStatus }
}
