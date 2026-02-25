package com.copilot.playwright.mcp

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

/**
 * Bridges a single Playwright MCP tool into Copilot's in-process tool registry.
 * Wraps the MCP tool schema as a LanguageModelToolRegistration and forwards
 * invocations to McpClient.callTool().
 */
class McpToolBridge(
    private val client: McpClient,
    private val schema: McpToolSchema,
    private val namePrefix: String = ""
) : LanguageModelToolRegistration {

    private val registeredName =
        if (namePrefix.isNotEmpty()) "${namePrefix}_${schema.name}" else schema.name

    override val toolDefinition = LanguageModelTool(
        registeredName,
        schema.description,
        schema.inputSchema,
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
            LanguageModelToolResult.Companion.error(
                e.message ?: "MCP tool call failed: ${schema.name}"
            )
        }
    }
}
