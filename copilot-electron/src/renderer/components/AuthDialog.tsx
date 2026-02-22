import { useState, useEffect, useCallback } from 'react'

interface AuthDialogProps {
  onClose: () => void
}

export function AuthDialog({ onClose }: AuthDialogProps) {
  const [userCode, setUserCode] = useState<string | null>(null)
  const [verificationUri, setVerificationUri] = useState<string>('https://github.com/login/device')
  const [status, setStatus] = useState<'idle' | 'waiting' | 'success' | 'error'>('idle')
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    // Listen for auth status updates
    if (!window.api?.auth?.onStatus) return
    const unsub = window.api.auth.onStatus((s) => {
      if (s.state === 'success' || s.state === 'OK' || s.state === 'MaybeOk') {
        setStatus('success')
        setTimeout(onClose, 1500)
      }
    })
    return unsub
  }, [onClose])

  const startFlow = useCallback(async () => {
    setStatus('waiting')
    try {
      const result = await window.api?.auth?.startDeviceFlow()
      if (result) {
        setUserCode(result.userCode)
        setVerificationUri(result.verificationUri)
      }
    } catch {
      setStatus('error')
    }
  }, [])

  const copyAndOpen = useCallback(() => {
    if (userCode) {
      navigator.clipboard.writeText(userCode).then(() => {
        setCopied(true)
        setTimeout(() => setCopied(false), 2000)
      })
    }
    window.open(verificationUri, '_blank')
  }, [userCode, verificationUri])

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={onClose}>
      <div
        className="w-[400px] bg-gray-900 border border-gray-700 rounded-xl shadow-2xl overflow-hidden"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="px-6 py-4 border-b border-gray-800">
          <h2 className="text-lg font-semibold text-gray-100">GitHub Copilot Authentication</h2>
          <p className="text-xs text-gray-400 mt-1">Sign in to use Copilot Desktop</p>
        </div>

        <div className="px-6 py-6 space-y-4">
          {status === 'idle' && (
            <>
              <p className="text-sm text-gray-300">
                No GitHub Copilot authentication found. Start the device authorization flow to sign in.
              </p>
              <button
                onClick={startFlow}
                className="w-full py-2.5 text-sm font-medium bg-green-600 text-white rounded-lg
                           hover:bg-green-500 transition-colors"
              >
                Start Device Auth Flow
              </button>
            </>
          )}

          {status === 'waiting' && (
            <>
              {userCode ? (
                <>
                  <div className="text-center">
                    <p className="text-sm text-gray-400 mb-2">Enter this code on GitHub:</p>
                    <div className="inline-block bg-gray-800 border border-gray-700 rounded-lg px-6 py-3">
                      <span className="text-2xl font-mono font-bold text-white tracking-widest">
                        {userCode}
                      </span>
                    </div>
                  </div>
                  <button
                    onClick={copyAndOpen}
                    className="w-full py-2.5 text-sm font-medium bg-blue-600 text-white rounded-lg
                               hover:bg-blue-500 transition-colors"
                  >
                    {copied ? 'Copied! Opening GitHub...' : 'Copy Code & Open GitHub'}
                  </button>
                  <p className="text-xs text-gray-500 text-center">
                    Waiting for authorization...
                    <span className="inline-block w-1.5 h-1.5 rounded-full bg-blue-400 animate-pulse ml-1.5 align-middle" />
                  </p>
                </>
              ) : (
                <div className="flex items-center justify-center py-6">
                  <span className="inline-block w-2 h-2 rounded-full bg-blue-400 animate-pulse mr-2" />
                  <span className="text-sm text-gray-400">Initiating device flow...</span>
                </div>
              )}
            </>
          )}

          {status === 'success' && (
            <div className="text-center py-4">
              <span className="text-green-400 text-3xl">&#10003;</span>
              <p className="text-sm text-green-400 mt-2">Authenticated successfully!</p>
            </div>
          )}

          {status === 'error' && (
            <div className="text-center py-4">
              <p className="text-sm text-red-400">Authentication failed. Please try again.</p>
              <button
                onClick={startFlow}
                className="mt-3 px-4 py-1.5 text-xs bg-gray-800 border border-gray-700 rounded
                           text-gray-400 hover:text-white transition-colors"
              >
                Retry
              </button>
            </div>
          )}
        </div>

        <div className="px-6 py-3 border-t border-gray-800 flex justify-end">
          <button
            onClick={onClose}
            className="text-xs text-gray-500 hover:text-white transition-colors"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  )
}
