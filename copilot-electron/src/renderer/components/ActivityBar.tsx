export type SidePanel = 'explorer' | 'search' | 'vcs'

interface ActivityBarProps {
  activePanel: SidePanel | null
  onPanelToggle: (panel: SidePanel) => void
  onSettingsOpen: () => void
}

export function ActivityBar({ activePanel, onPanelToggle, onSettingsOpen }: ActivityBarProps) {
  return (
    <div className="w-[40px] shrink-0 bg-gray-900 border-r border-gray-800 flex flex-col items-center py-2 gap-1">
      <ActivityIcon
        label="Explorer"
        active={activePanel === 'explorer'}
        onClick={() => onPanelToggle('explorer')}
      >
        <svg width={20} height={20} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
          <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
        </svg>
      </ActivityIcon>
      <ActivityIcon
        label="Search"
        active={activePanel === 'search'}
        onClick={() => onPanelToggle('search')}
      >
        <svg width={20} height={20} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
          <circle cx={11} cy={11} r={8} />
          <line x1={21} y1={21} x2={16.65} y2={16.65} />
        </svg>
      </ActivityIcon>
      <ActivityIcon
        label="Source Control"
        active={activePanel === 'vcs'}
        onClick={() => onPanelToggle('vcs')}
      >
        <svg width={20} height={20} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
          <line x1={6} y1={3} x2={6} y2={15} />
          <circle cx={18} cy={6} r={3} />
          <circle cx={6} cy={18} r={3} />
          <path d="M18 9a9 9 0 0 1-9 9" />
        </svg>
      </ActivityIcon>

      <div className="flex-1" />

      <ActivityIcon label="Settings" active={false} onClick={onSettingsOpen}>
        <svg width={20} height={20} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round">
          <circle cx={12} cy={12} r={3} />
          <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
        </svg>
      </ActivityIcon>
    </div>
  )
}

function ActivityIcon({
  label,
  active,
  onClick,
  children,
}: {
  label: string
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      onClick={onClick}
      title={label}
      className={`w-[32px] h-[32px] flex items-center justify-center rounded transition-colors ${
        active
          ? 'text-white bg-gray-800'
          : 'text-gray-500 hover:text-gray-300'
      }`}
    >
      {children}
    </button>
  )
}
