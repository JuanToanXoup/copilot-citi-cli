package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitListTemplates(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_list_templates",
        "List all available Spec-Kit templates from .specify/templates/.",
        mapOf(
            "type" to "object",
            "properties" to mapOf<String, Any>(),
            "required" to listOf<String>()
        ),
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val templatesDir = File(basePath, ".specify/templates")
        if (!templatesDir.isDirectory) {
            return LanguageModelToolResult.Companion.error("No .specify/templates/ directory found in project.")
        }

        val templates = templatesDir.listFiles { f -> f.isFile }
            ?.sortedBy { it.name }
            ?.map { it.name }
            ?: emptyList()

        if (templates.isEmpty()) {
            return LanguageModelToolResult.Companion.success("No templates found in .specify/templates/.")
        }

        return LanguageModelToolResult.Companion.success(
            "Available templates (${templates.size}):\n${templates.joinToString("\n") { "- $it" }}"
        )
    }
}
