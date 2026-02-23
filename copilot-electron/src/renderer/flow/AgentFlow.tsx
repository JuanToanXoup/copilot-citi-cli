import { useCallback, useEffect, useRef } from 'react'
import {
  ReactFlow,
  Background,
  MiniMap,
  useReactFlow,
  type Node,
  type NodeMouseHandler,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'

import { useTabFlowStore } from '../contexts/TabStoreContext'
import { UserNode } from './nodes/UserNode'
import { AgentNode } from './nodes/AgentNode'
import { SubagentNode } from './nodes/SubagentNode'
import { SubagentResultNode } from './nodes/SubagentResultNode'
import { ToolNode } from './nodes/ToolNode'
import { ToolResultNode } from './nodes/ToolResultNode'
import { TerminalNode } from './nodes/TerminalNode'
import { TerminalResultNode } from './nodes/TerminalResultNode'
import { ParticleEdge } from './edges/ParticleEdge'
import { FlowControls } from './panels/FlowControls'
import { NodeDetail } from './panels/NodeDetail'

const nodeTypes = {
  user: UserNode,
  agent: AgentNode,
  subagent: SubagentNode,
  subagentResult: SubagentResultNode,
  tool: ToolNode,
  toolResult: ToolResultNode,
  terminal: TerminalNode,
  terminalResult: TerminalResultNode,
}

const edgeTypes = {
  particle: ParticleEdge,
}

interface AgentFlowProps {
  selectedNode: Node | null
  onNodeSelect: (node: Node | null) => void
}

function AutoFitView() {
  const { fitView } = useReactFlow()
  const nodeCount = useTabFlowStore((s) => s.nodes.length)
  const prevCount = useRef(nodeCount)

  useEffect(() => {
    if (nodeCount > 0 && nodeCount !== prevCount.current) {
      prevCount.current = nodeCount
      const timer = setTimeout(() => {
        fitView({ padding: 0.15, duration: 400 })
      }, 100)
      return () => clearTimeout(timer)
    }
  }, [nodeCount, fitView])

  return null
}

export function AgentFlow({ selectedNode, onNodeSelect }: AgentFlowProps) {
  const nodes = useTabFlowStore((s) => s.nodes)
  const edges = useTabFlowStore((s) => s.edges)

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
              case 'agent': return '#10b981'
              case 'subagent': return '#3b82f6'
              case 'subagentResult': return '#22c55e'
              case 'tool': return '#8b5cf6'
              case 'toolResult': return '#22c55e'
              case 'terminal': return '#a855f7'
              case 'terminalResult': return '#22c55e'
              default: return '#6b7280'
            }
          }}
          maskColor="rgba(0,0,0,0.7)"
          className="!bg-gray-900 !border-gray-700"
        />
        <FlowControls />
        <AutoFitView />
      </ReactFlow>
      <NodeDetail node={selectedNode} onClose={() => onNodeSelect(null)} />
    </div>
  )
}
