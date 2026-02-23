import { useState, useCallback, useRef } from 'react'

interface UseResizableDividerOptions {
  initial: number
  min: number
  max: number
  /** 'percentage' computes delta as % of parent width; 'pixel' uses raw pixel delta */
  mode: 'percentage' | 'pixel'
}

export function useResizableDivider({ initial, min, max, mode }: UseResizableDividerOptions) {
  const [position, setPosition] = useState(initial)
  const ref = useRef<HTMLDivElement>(null)

  const onMouseDown = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault()
      const startX = e.clientX
      const startPos = position

      const onMouseMove = (moveEvent: MouseEvent) => {
        if (mode === 'percentage') {
          const container = ref.current?.parentElement
          if (!container) return
          const rect = container.getBoundingClientRect()
          const delta = ((moveEvent.clientX - startX) / rect.width) * 100
          setPosition(Math.min(max, Math.max(min, startPos + delta)))
        } else {
          const delta = moveEvent.clientX - startX
          setPosition(Math.min(max, Math.max(min, startPos + delta)))
        }
      }

      const onMouseUp = () => {
        document.removeEventListener('mousemove', onMouseMove)
        document.removeEventListener('mouseup', onMouseUp)
      }

      document.addEventListener('mousemove', onMouseMove)
      document.addEventListener('mouseup', onMouseUp)
    },
    [position, min, max, mode],
  )

  return { position, setPosition, ref, onMouseDown }
}
