package com.citigroup.copilotchat.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Abstracts tool execution so consumers don't depend on [ToolRouter] internals.
 *
 * The default implementation is [ToolRouter], which routes calls through
 * PSI tools, ide-index, and built-in tools with priority-based fallback.
 */
interface ToolExecutor {
    /** Get all tool schemas to register with the server. */
    fun getToolSchemas(): List<String>

    /** Execute a tool call and return the result in copilot response format. */
    suspend fun executeTool(
        name: String,
        input: JsonObject,
        workspaceRootOverride: String? = null,
        conversationId: String? = null,
    ): JsonElement
}
