import type { SubagentToolFilter } from '@shared/types'

/**
 * Registry of per-conversation tool filters for subagents.
 * Provides hard enforcement of tool allowlists/blocklists.
 */
export class ToolFilterRegistry {
  private filters = new Map<string, SubagentToolFilter>()

  /** Register a tool filter for a subagent conversation. */
  register(conversationId: string, filter: SubagentToolFilter) {
    this.filters.set(conversationId, filter)
  }

  /**
   * Check if a tool is allowed for the given conversation.
   * Returns true if no filter is registered (lead conversation or unknown).
   */
  isAllowed(conversationId: string | null, toolName: string): boolean {
    if (!conversationId) return true
    const filter = this.filters.get(conversationId)
    if (!filter) return true

    // Blocklist check
    if (filter.disallowedTools.has(toolName)) return false

    // Allowlist check (null = all allowed)
    if (filter.allowedTools && !filter.allowedTools.has(toolName)) return false

    return true
  }

  /** Clear all filters. */
  clear() {
    this.filters.clear()
  }

  /** Remove a specific filter. */
  remove(conversationId: string) {
    this.filters.delete(conversationId)
  }
}
