package com.speckit.companion.mcp

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

/**
 * Bridges a tool from a local MCP server into Copilot's in-process tool registry.
 * The MCP server runs locally (stdio); this class wraps its schema as a
 * LanguageModelToolRegistration and forwards invocations to McpClient.callTool().
 */
class McpToolBridge(
    private val client: McpClient,
    private val schema: McpToolSchema,
    private val namePrefix: String = ""
) : LanguageModelToolRegistration {

    private val registeredName = if (namePrefix.isNotEmpty()) "${namePrefix}_${schema.name}" else schema.name

    // Copilot LSP requires "required" on every tool schema, even if empty.
    private val normalizedSchema: Map<String, Any> =
        if (schema.inputSchema.containsKey("required")) schema.inputSchema
        else schema.inputSchema + ("required" to emptyList<String>())

    override val toolDefinition = LanguageModelTool(
        registeredName,
        schema.description,
        normalizedSchema,
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        return try {
            val result = client.callTool(schema.name, request.input)
            LanguageModelToolResult.Companion.success(result)
        } catch (e: Exception) {
            LanguageModelToolResult.Companion.error(e.message ?: "MCP tool call failed: ${schema.name}")
        }
    }
}
