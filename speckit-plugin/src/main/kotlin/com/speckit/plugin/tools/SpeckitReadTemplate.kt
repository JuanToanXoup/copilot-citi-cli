package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitReadTemplate(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_read_template",
        "Read a Spec-Kit template file from .specify/templates/.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "Template file name, e.g. 'spec-template.md' or 'plan-template.md'")
            ),
            "required" to listOf("name")
        ),
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val name = request.input?.get("name")?.asString
            ?: return LanguageModelToolResult.Companion.error("Missing required parameter: name")

        val file = File(basePath, ".specify/templates/$name")

        if (!file.exists()) {
            return LanguageModelToolResult.Companion.error("Template not found: $name")
        }

        return LanguageModelToolResult.Companion.success(file.readText())
    }
}
