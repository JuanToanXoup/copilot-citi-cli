import { BaseEdge, getStraightPath, type EdgeProps } from '@xyflow/react'

export function AnimatedEdge(props: EdgeProps) {
  const { sourceX, sourceY, targetX, targetY } = props

  const [edgePath] = getStraightPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
  })

  return (
    <BaseEdge
      {...props}
      path={edgePath}
      style={{
        ...props.style,
        strokeWidth: 2,
      }}
    />
  )
}
