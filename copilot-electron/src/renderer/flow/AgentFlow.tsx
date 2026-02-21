import { useCallback, useState } from 'react'
import {
  ReactFlow,
  Background,
  MiniMap,
  type Node,
  type NodeMouseHandler,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'

import { useFlowStore } from '../stores/flow-store'
import { UserNode } from './nodes/UserNode'
import { LeadNode } from './nodes/LeadNode'
import { SubagentNode } from './nodes/SubagentNode'
import { ToolNode } from './nodes/ToolNode'
import { AnimatedEdge } from './edges/AnimatedEdge'
import { FlowControls } from './panels/FlowControls'
import { NodeDetail } from './panels/NodeDetail'

const nodeTypes = {
  user: UserNode,
  lead: LeadNode,
  subagent: SubagentNode,
  tool: ToolNode,
}

const edgeTypes = {
  animated: AnimatedEdge,
}

interface AgentFlowProps {
  selectedNode: Node | null
  onNodeSelect: (node: Node | null) => void
}

export function AgentFlow({ selectedNode, onNodeSelect }: AgentFlowProps) {
  const nodes = useFlowStore((s) => s.nodes)
  const edges = useFlowStore((s) => s.edges)

  const onNodeClick: NodeMouseHandler = useCallback(
    (_event, node) => {
      onNodeSelect(node)
    },
    [onNodeSelect],
  )

  const onPaneClick = useCallback(() => {
    onNodeSelect(null)
  }, [onNodeSelect])

  return (
    <div className="relative w-full h-full">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        onNodeClick={onNodeClick}
        onPaneClick={onPaneClick}
        fitView
        minZoom={0.1}
        maxZoom={2}
        proOptions={{ hideAttribution: true }}
      >
        <Background color="#374151" gap={16} size={1} />
        <MiniMap
          nodeColor={(n) => {
            switch (n.type) {
              case 'user': return '#6b7280'
              case 'lead': return '#eab308'
              case 'subagent': return '#3b82f6'
              case 'tool': return '#8b5cf6'
              default: return '#6b7280'
            }
          }}
          maskColor="rgba(0,0,0,0.7)"
          className="!bg-gray-900 !border-gray-700"
        />
        <FlowControls />
      </ReactFlow>
      <NodeDetail node={selectedNode} onClose={() => onNodeSelect(null)} />
    </div>
  )
}
