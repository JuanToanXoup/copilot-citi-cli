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
