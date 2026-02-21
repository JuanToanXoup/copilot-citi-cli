/** IntelliJ-style SVG file and folder icons (16x16) */

const S = 16 // icon size

// ---- Folder icons ----

export function FolderIcon({ color = '#6b7280' }: { color?: string }) {
  return (
    <svg width={S} height={S} viewBox="0 0 16 16" fill="none">
      <path d="M1.5 3C1.5 2.45 1.95 2 2.5 2H6l1.5 1.5H13.5c.55 0 1 .45 1 1V12c0 .55-.45 1-1 1H2.5c-.55 0-1-.45-1-1V3z" fill={color} opacity={0.85}/>
    </svg>
  )
}

export function FolderOpenIcon({ color = '#6b7280' }: { color?: string }) {
  return (
    <svg width={S} height={S} viewBox="0 0 16 16" fill="none">
      <path d="M1.5 3C1.5 2.45 1.95 2 2.5 2H6l1.5 1.5H13.5c.55 0 1 .45 1 1V5H3L1 12V3z" fill={color} opacity={0.65}/>
      <path d="M1 12l2-7h12l-2 7H1z" fill={color} opacity={0.85}/>
    </svg>
  )
}

// ---- Generic file icon ----

function FileBase({ children, bg = '#4b5563' }: { children?: React.ReactNode; bg?: string }) {
  return (
    <svg width={S} height={S} viewBox="0 0 16 16" fill="none">
      <path d="M3 1.5h6.5L13 5v9.5c0 .28-.22.5-.5.5h-9a.5.5 0 01-.5-.5v-13c0-.28.22-.5.5-.5z" fill={bg} opacity={0.2}/>
      <path d="M3 1.5h6.5L13 5v9.5c0 .28-.22.5-.5.5h-9a.5.5 0 01-.5-.5v-13c0-.28.22-.5.5-.5z" stroke={bg} strokeWidth={0.8} opacity={0.6}/>
      <path d="M9.5 1.5V5H13" stroke={bg} strokeWidth={0.8} opacity={0.4}/>
      {children}
    </svg>
  )
}

// ---- Language-specific file icons ----

export function TypeScriptIcon() {
  return (
    <FileBase bg="#3178c6">
      <rect x="3.5" y="7" width="9" height="6" rx="0.8" fill="#3178c6"/>
      <text x="8" y="12" textAnchor="middle" fill="white" fontSize="5.5" fontWeight="700" fontFamily="sans-serif">TS</text>
    </FileBase>
  )
}

export function JavaScriptIcon() {
  return (
    <FileBase bg="#f0db4f">
      <rect x="3.5" y="7" width="9" height="6" rx="0.8" fill="#f0db4f"/>
      <text x="8" y="12" textAnchor="middle" fill="#323330" fontSize="5.5" fontWeight="700" fontFamily="sans-serif">JS</text>
    </FileBase>
  )
}

export function JavaIcon() {
  return (
    <FileBase bg="#e76f00">
      <text x="8" y="12" textAnchor="middle" fill="#e76f00" fontSize="8" fontWeight="700" fontFamily="sans-serif">J</text>
    </FileBase>
  )
}

export function KotlinIcon() {
  return (
    <FileBase bg="#7f52ff">
      <rect x="3.5" y="7" width="9" height="6" rx="0.8" fill="#7f52ff"/>
      <text x="8" y="12" textAnchor="middle" fill="white" fontSize="5.5" fontWeight="700" fontFamily="sans-serif">KT</text>
    </FileBase>
  )
}

export function PythonIcon() {
  return (
    <FileBase bg="#3572a5">
      <text x="8" y="12" textAnchor="middle" fill="#3572a5" fontSize="7" fontWeight="700" fontFamily="sans-serif">Py</text>
    </FileBase>
  )
}

export function RustIcon() {
  return (
    <FileBase bg="#ce422b">
      <text x="8" y="12" textAnchor="middle" fill="#ce422b" fontSize="7" fontWeight="700" fontFamily="sans-serif">Rs</text>
    </FileBase>
  )
}

export function GoIcon() {
  return (
    <FileBase bg="#00add8">
      <text x="8" y="12" textAnchor="middle" fill="#00add8" fontSize="6" fontWeight="700" fontFamily="sans-serif">Go</text>
    </FileBase>
  )
}

export function HtmlIcon() {
  return (
    <FileBase bg="#e44d26">
      <rect x="3.5" y="7" width="9" height="6" rx="0.8" fill="#e44d26"/>
      <text x="8" y="11.8" textAnchor="middle" fill="white" fontSize="4.5" fontWeight="700" fontFamily="sans-serif">&lt;/&gt;</text>
    </FileBase>
  )
}

export function CssIcon() {
  return (
    <FileBase bg="#264de4">
      <rect x="3.5" y="7" width="9" height="6" rx="0.8" fill="#264de4"/>
      <text x="8" y="11.8" textAnchor="middle" fill="white" fontSize="5" fontWeight="700" fontFamily="sans-serif">#</text>
    </FileBase>
  )
}

export function ScssIcon() {
  return (
    <FileBase bg="#cf649a">
      <rect x="3.5" y="7" width="9" height="6" rx="0.8" fill="#cf649a"/>
      <text x="8" y="11.8" textAnchor="middle" fill="white" fontSize="5" fontWeight="700" fontFamily="sans-serif">S</text>
    </FileBase>
  )
}

export function JsonIcon() {
  return (
    <FileBase bg="#cbcb41">
      <text x="8" y="11.5" textAnchor="middle" fill="#cbcb41" fontSize="7.5" fontWeight="700" fontFamily="sans-serif">{'{}'}</text>
    </FileBase>
  )
}

export function YamlIcon() {
  return (
    <FileBase bg="#cb171e">
      <text x="8" y="12" textAnchor="middle" fill="#cb171e" fontSize="6" fontWeight="700" fontFamily="sans-serif">yml</text>
    </FileBase>
  )
}

export function XmlIcon() {
  return (
    <FileBase bg="#e37933">
      <text x="8" y="11.8" textAnchor="middle" fill="#e37933" fontSize="4.5" fontWeight="700" fontFamily="sans-serif">&lt;/&gt;</text>
    </FileBase>
  )
}

export function MarkdownIcon() {
  return (
    <FileBase bg="#519aba">
      <rect x="3.5" y="7" width="9" height="6" rx="0.8" fill="#519aba"/>
      <text x="8" y="12" textAnchor="middle" fill="white" fontSize="5.5" fontWeight="700" fontFamily="sans-serif">M↓</text>
    </FileBase>
  )
}

export function ShellIcon() {
  return (
    <FileBase bg="#4eaa25">
      <text x="8" y="12" textAnchor="middle" fill="#4eaa25" fontSize="8" fontWeight="700" fontFamily="sans-serif">$</text>
    </FileBase>
  )
}

export function DockerIcon() {
  return (
    <FileBase bg="#2496ed">
      <rect x="3.5" y="7" width="9" height="6" rx="0.8" fill="#2496ed"/>
      <text x="8" y="12" textAnchor="middle" fill="white" fontSize="5" fontWeight="700" fontFamily="sans-serif">D</text>
    </FileBase>
  )
}

export function GradleIcon() {
  return (
    <FileBase bg="#02303a">
      <rect x="3.5" y="7" width="9" height="6" rx="0.8" fill="#02303a"/>
      <text x="8" y="12" textAnchor="middle" fill="#69c96b" fontSize="5.5" fontWeight="700" fontFamily="sans-serif">G</text>
    </FileBase>
  )
}

export function SqlIcon() {
  return (
    <FileBase bg="#e38c00">
      <text x="8" y="12" textAnchor="middle" fill="#e38c00" fontSize="5.5" fontWeight="700" fontFamily="sans-serif">SQL</text>
    </FileBase>
  )
}

export function ImageIcon() {
  return (
    <FileBase bg="#a074c4">
      <rect x="4" y="7.5" width="8" height="5.5" rx="0.5" fill="#a074c4" opacity={0.25}/>
      <circle cx="6.5" cy="9.5" r="1" fill="#a074c4"/>
      <path d="M4.5 12.5l2.5-2.5 1.5 1.5 1.5-1 2 2H4.5z" fill="#a074c4" opacity={0.6}/>
    </FileBase>
  )
}

export function SvgIcon() {
  return (
    <FileBase bg="#ffb13b">
      <text x="8" y="12" textAnchor="middle" fill="#ffb13b" fontSize="5" fontWeight="700" fontFamily="sans-serif">SVG</text>
    </FileBase>
  )
}

export function LockIcon() {
  return (
    <FileBase bg="#6b7280">
      <text x="8" y="12" textAnchor="middle" fill="#6b7280" fontSize="7" fontFamily="sans-serif">🔒</text>
    </FileBase>
  )
}

export function EnvIcon() {
  return (
    <FileBase bg="#eab308">
      <circle cx="8" cy="10" r="3" fill="#eab308" opacity={0.2}/>
      <text x="8" y="11.5" textAnchor="middle" fill="#eab308" fontSize="6" fontFamily="sans-serif">⚙</text>
    </FileBase>
  )
}

export function GitIcon() {
  return (
    <FileBase bg="#f05032">
      <circle cx="8" cy="10" r="3.5" fill="#f05032" opacity={0.15}/>
      <text x="8" y="12" textAnchor="middle" fill="#f05032" fontSize="6.5" fontWeight="700" fontFamily="sans-serif">G</text>
    </FileBase>
  )
}

export function ConfigIcon() {
  return (
    <FileBase bg="#6b7280">
      <text x="8" y="11.5" textAnchor="middle" fill="#9ca3af" fontSize="6" fontFamily="sans-serif">⚙</text>
    </FileBase>
  )
}

export function TextIcon() {
  return (
    <FileBase bg="#6b7280">
      <line x1="5" y1="7.5" x2="11" y2="7.5" stroke="#9ca3af" strokeWidth={0.8}/>
      <line x1="5" y1="9.5" x2="11" y2="9.5" stroke="#9ca3af" strokeWidth={0.8}/>
      <line x1="5" y1="11.5" x2="9" y2="11.5" stroke="#9ca3af" strokeWidth={0.8}/>
    </FileBase>
  )
}

export function DefaultFileIcon() {
  return <FileBase bg="#6b7280" />
}

export function CIcon() {
  return (
    <FileBase bg="#555555">
      <rect x="3.5" y="7" width="9" height="6" rx="0.8" fill="#555555"/>
      <text x="8" y="12" textAnchor="middle" fill="#a8b9cc" fontSize="6" fontWeight="700" fontFamily="sans-serif">C</text>
    </FileBase>
  )
}

export function CppIcon() {
  return (
    <FileBase bg="#004482">
      <rect x="3.5" y="7" width="9" height="6" rx="0.8" fill="#004482"/>
      <text x="8" y="11.8" textAnchor="middle" fill="#659ad2" fontSize="5" fontWeight="700" fontFamily="sans-serif">C++</text>
    </FileBase>
  )
}

/* ------------------------------------------------------------------ */
/*  Lookup functions                                                   */
/* ------------------------------------------------------------------ */

type IconComponent = () => React.JSX.Element

// Exact filename → icon
const FILE_NAME_MAP: Record<string, IconComponent> = {
  'package.json': JsonIcon,
  'package-lock.json': LockIcon,
  'tsconfig.json': TypeScriptIcon,
  'tsconfig.node.json': TypeScriptIcon,
  'jsconfig.json': JavaScriptIcon,
  '.gitignore': GitIcon,
  '.gitattributes': GitIcon,
  '.gitmodules': GitIcon,
  'dockerfile': DockerIcon,
  'docker-compose.yml': DockerIcon,
  'docker-compose.yaml': DockerIcon,
  'makefile': ShellIcon,
  '.env': EnvIcon,
  '.env.local': EnvIcon,
  '.env.development': EnvIcon,
  '.env.production': EnvIcon,
  '.env.test': EnvIcon,
  'build.gradle': GradleIcon,
  'build.gradle.kts': GradleIcon,
  'settings.gradle': GradleIcon,
  'settings.gradle.kts': GradleIcon,
  'gradlew': GradleIcon,
  'gradlew.bat': GradleIcon,
  'gradle.properties': GradleIcon,
  'yarn.lock': LockIcon,
  'pnpm-lock.yaml': LockIcon,
  'vite.config.ts': TypeScriptIcon,
  'vite.config.js': JavaScriptIcon,
  'vite.main.config.ts': TypeScriptIcon,
  'vite.preload.config.ts': TypeScriptIcon,
  'webpack.config.js': JavaScriptIcon,
  'tailwind.config.js': JavaScriptIcon,
  'tailwind.config.ts': TypeScriptIcon,
  'postcss.config.js': JavaScriptIcon,
  'postcss.config.cjs': JavaScriptIcon,
  'electron-builder.yml': YamlIcon,
}

// Extension → icon
const EXT_MAP: Record<string, IconComponent> = {
  ts: TypeScriptIcon,
  tsx: TypeScriptIcon,
  js: JavaScriptIcon,
  jsx: JavaScriptIcon,
  mjs: JavaScriptIcon,
  cjs: JavaScriptIcon,
  java: JavaIcon,
  kt: KotlinIcon,
  kts: KotlinIcon,
  scala: JavaIcon,
  groovy: JavaIcon,
  py: PythonIcon,
  pyi: PythonIcon,
  rs: RustIcon,
  go: GoIcon,
  c: CIcon,
  h: CIcon,
  cpp: CppIcon,
  hpp: CppIcon,
  html: HtmlIcon,
  htm: HtmlIcon,
  css: CssIcon,
  scss: ScssIcon,
  less: CssIcon,
  sass: ScssIcon,
  json: JsonIcon,
  jsonc: JsonIcon,
  yaml: YamlIcon,
  yml: YamlIcon,
  toml: YamlIcon,
  xml: XmlIcon,
  svg: SvgIcon,
  md: MarkdownIcon,
  mdx: MarkdownIcon,
  txt: TextIcon,
  sh: ShellIcon,
  bash: ShellIcon,
  zsh: ShellIcon,
  bat: ShellIcon,
  ps1: ShellIcon,
  sql: SqlIcon,
  png: ImageIcon,
  jpg: ImageIcon,
  jpeg: ImageIcon,
  gif: ImageIcon,
  webp: ImageIcon,
  ico: ImageIcon,
  bmp: ImageIcon,
  lock: LockIcon,
  ini: ConfigIcon,
  cfg: ConfigIcon,
  conf: ConfigIcon,
  properties: ConfigIcon,
  pdf: TextIcon,
  csv: TextIcon,
  log: TextIcon,
  map: ConfigIcon,
}

export function getFileIcon(name: string): IconComponent {
  const lower = name.toLowerCase()
  if (FILE_NAME_MAP[lower]) return FILE_NAME_MAP[lower]
  if (lower.startsWith('.env')) return EnvIcon
  if (lower.startsWith('.git')) return GitIcon
  if (lower.startsWith('.eslint')) return ConfigIcon
  if (lower.startsWith('.prettier')) return ConfigIcon

  const ext = name.includes('.') ? name.split('.').pop()?.toLowerCase() : undefined
  if (ext && EXT_MAP[ext]) return EXT_MAP[ext]

  return DefaultFileIcon
}

const FOLDER_COLOR = '#6b7280'

export function getFolderColor(_name: string): string {
  return FOLDER_COLOR
}
