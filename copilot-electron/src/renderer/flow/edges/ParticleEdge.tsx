import { getBezierPath, type EdgeProps } from '@xyflow/react'

export function ParticleEdge(props: EdgeProps) {
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, style, data } = props

  const [edgePath] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  })

  const edgeData = data as { status?: string; color?: string } | undefined
  const status = edgeData?.status ?? 'active'
  const color = edgeData?.color ?? '#3b82f6'

  const isActive = status === 'active'
  const isDashed = status === 'dashed'
  const isSuccess = status === 'success'
  const isError = status === 'error'

  return (
    <>
      {/* Background glow for active edges */}
      {isActive && (
        <path
          d={edgePath}
          fill="none"
          stroke={color}
          strokeWidth={6}
          strokeOpacity={0.15}
          className="particle-edge-glow"
        />
      )}

      {/* Main edge path */}
      <path
        id={id}
        d={edgePath}
        fill="none"
        stroke={isError ? '#ef4444' : isSuccess ? '#22c55e' : color}
        strokeWidth={2}
        strokeDasharray={isDashed ? '6 3' : isActive ? '8 4' : 'none'}
        className={isActive ? 'particle-edge-flow' : undefined}
        style={style}
      />

      {/* Particle dots for active edges */}
      {isActive && (
        <>
          <circle r={3} fill={color} className="particle-dot particle-dot-1">
            <animateMotion dur="1.5s" repeatCount="indefinite" path={edgePath} />
          </circle>
          <circle r={2} fill={color} opacity={0.6} className="particle-dot particle-dot-2">
            <animateMotion dur="1.5s" repeatCount="indefinite" path={edgePath} begin="0.5s" />
          </circle>
          <circle r={2} fill={color} opacity={0.4} className="particle-dot particle-dot-3">
            <animateMotion dur="1.5s" repeatCount="indefinite" path={edgePath} begin="1s" />
          </circle>
        </>
      )}
    </>
  )
}
