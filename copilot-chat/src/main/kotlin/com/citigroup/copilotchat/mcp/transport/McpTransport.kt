package com.citigroup.copilotchat.mcp.transport

import kotlinx.serialization.json.JsonObject

/**
 * Common interface for MCP server transports (stdio, SSE).
 *
 * Both [McpServer] (stdio) and [McpSseServer] (SSE) expose the same
 * lifecycle: start -> initialize -> listTools -> callTool -> stop.
 * [com.citigroup.copilotchat.mcp.ClientMcpManager] operates on this interface so transport details
 * are hidden behind a single collection.
 */
interface McpTransport {
    val name: String
    val tools: List<JsonObject>
    suspend fun start()
    suspend fun initialize()
    suspend fun listTools(): List<JsonObject>
    suspend fun callTool(toolName: String, arguments: JsonObject): String
    fun stop()
}
