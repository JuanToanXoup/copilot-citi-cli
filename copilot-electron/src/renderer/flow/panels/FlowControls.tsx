import { useReactFlow } from '@xyflow/react'

export function FlowControls() {
  const { fitView, zoomIn, zoomOut } = useReactFlow()

  return (
    <div className="absolute left-4 bottom-4 flex flex-col gap-1 z-10">
      <ControlButton label="+" onClick={() => zoomIn()} />
      <ControlButton label="-" onClick={() => zoomOut()} />
      <ControlButton label="Fit" onClick={() => fitView({ padding: 0.2, duration: 300 })} />
    </div>
  )
}

function ControlButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="w-8 h-8 flex items-center justify-center text-xs font-medium
                 bg-gray-800 border border-gray-700 rounded text-gray-300
                 hover:bg-gray-700 hover:text-white transition-colors"
    >
      {label}
    </button>
  )
}
