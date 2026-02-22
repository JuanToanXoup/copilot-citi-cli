/** IPC channel name constants */

// Renderer → Main
export const AGENT_MESSAGE = 'agent:message' as const
export const AGENT_CANCEL = 'agent:cancel' as const
export const AGENT_NEW_CONVERSATION = 'agent:new-conversation' as const
export const CHAT_MESSAGE = 'chat:message' as const
export const SETTINGS_UPDATE = 'settings:update' as const
export const DIALOG_OPEN_DIRECTORY = 'dialog:open-directory' as const
export const FS_READ_DIRECTORY = 'fs:read-directory' as const
export const FS_READ_FILE = 'fs:read-file' as const
export const FS_WRITE_FILE = 'fs:write-file' as const

// Main → Renderer
export const AGENT_EVENT = 'agent:event' as const
export const CHAT_EVENT = 'chat:event' as const
export const LSP_STATUS = 'lsp:status' as const
export const PSI_STATUS = 'psi:status' as const

// File changes (Phase 3)
export const FILE_CHANGED = 'file:changed' as const

// Auth (Phase 4)
export const AUTH_DEVICE_FLOW_START = 'auth:device-flow-start' as const
export const AUTH_DEVICE_FLOW_STATUS = 'auth:device-flow-status' as const
export const AUTH_SIGN_OUT = 'auth:sign-out' as const

// Settings persistence (Phase 5)
export const SETTINGS_READ = 'settings:read' as const
export const SETTINGS_WRITE = 'settings:write' as const

// Conversation persistence (Phase 6)
export const CONVERSATION_SAVE = 'conversation:save' as const
export const CONVERSATION_LIST = 'conversation:list' as const
export const CONVERSATION_LOAD = 'conversation:load' as const

// Tool permissions (Phase 7)
export const TOOL_CONFIRM_REQUEST = 'tool:confirm-request' as const
export const TOOL_CONFIRM_RESPONSE = 'tool:confirm-response' as const

// Git integration (Phase 9)
export const GIT_STATUS = 'git:status' as const
export const GIT_BRANCH = 'git:branch' as const
export const GIT_BRANCHES = 'git:branches' as const
export const GIT_CHECKOUT = 'git:checkout' as const
export const GIT_COMMIT = 'git:commit' as const
export const GIT_PUSH = 'git:push' as const
export const GIT_PULL = 'git:pull' as const
export const GIT_DIFF = 'git:diff' as const
export const GIT_CHECKOUT_FILE = 'git:checkout-file' as const

// Settings sync (bidirectional)
export const SETTINGS_CHANGED = 'settings:changed' as const

// Agent listing (Phase 3)
export const AGENTS_LIST = 'agents:list' as const

// MCP management
export const MCP_ADD_SERVER = 'mcp:add-server' as const
export const MCP_REMOVE_SERVER = 'mcp:remove-server' as const
export const MCP_DISCONNECT = 'mcp:disconnect' as const
export const MCP_RECONNECT = 'mcp:reconnect' as const
export const MCP_LIST_SERVERS = 'mcp:list-servers' as const

// Window management
export const WINDOW_SET_TITLE = 'window:set-title' as const
