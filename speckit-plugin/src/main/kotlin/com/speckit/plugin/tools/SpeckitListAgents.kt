package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitListAgents(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_list_agents",
        "List all available Spec-Kit agent definitions from .github/agents/.",
        mapOf(
            "type" to "object",
            "properties" to mapOf<String, Any>()
        ),
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val agentsDir = File(basePath, ".github/agents")
        if (!agentsDir.isDirectory) {
            return LanguageModelToolResult.Companion.error("No .github/agents/ directory found in project.")
        }

        val agents = agentsDir.listFiles { f -> f.isFile && f.name.endsWith(".agent.md") }
            ?.sortedBy { it.name }
            ?.map { it.name }
            ?: emptyList()

        if (agents.isEmpty()) {
            return LanguageModelToolResult.Companion.success("No agent definitions found in .github/agents/.")
        }

        return LanguageModelToolResult.Companion.success(
            "Available agents (${agents.size}):\n${agents.joinToString("\n") { "- $it" }}"
        )
    }
}
