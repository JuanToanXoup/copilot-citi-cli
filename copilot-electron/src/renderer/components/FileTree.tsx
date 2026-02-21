export function FileTree() {
  // Placeholder — will be wired to project directory reading via IPC
  return (
    <div className="p-3">
      <div className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-3">
        Explorer
      </div>
      <div className="text-xs text-gray-600 space-y-1">
        <TreeItem name="src/" indent={0} isDir />
        <TreeItem name="components/" indent={1} isDir />
        <TreeItem name="stores/" indent={1} isDir />
        <TreeItem name="flow/" indent={1} isDir />
        <TreeItem name="views/" indent={1} isDir />
        <TreeItem name="package.json" indent={0} />
        <TreeItem name="tsconfig.json" indent={0} />
      </div>
      <div className="mt-6 text-xs text-gray-700 italic">
        Open a project to see files
      </div>
    </div>
  )
}

function TreeItem({ name, indent, isDir }: { name: string; indent: number; isDir?: boolean }) {
  return (
    <div
      className="flex items-center gap-1.5 py-0.5 px-1 rounded hover:bg-gray-800 cursor-pointer text-gray-400"
      style={{ paddingLeft: `${indent * 12 + 4}px` }}
    >
      <span className="text-gray-600">{isDir ? '▸' : ' '}</span>
      <span>{name}</span>
    </div>
  )
}
