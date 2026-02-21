/** IPC channel name constants */

// Renderer → Main
export const AGENT_MESSAGE = 'agent:message' as const
export const AGENT_CANCEL = 'agent:cancel' as const
export const AGENT_NEW_CONVERSATION = 'agent:new-conversation' as const
export const CHAT_MESSAGE = 'chat:message' as const
export const SETTINGS_UPDATE = 'settings:update' as const

// Main → Renderer
export const AGENT_EVENT = 'agent:event' as const
export const CHAT_EVENT = 'chat:event' as const
export const LSP_STATUS = 'lsp:status' as const
export const PSI_STATUS = 'psi:status' as const
