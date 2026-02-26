package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationManager
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import com.intellij.openapi.application.ApplicationManager

class SpeckitListTemplates : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_list_templates",
        "List all available Spec-Kit templates.",
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
        val manager = ApplicationManager.getApplication().getService(ToolInvocationManager::class.java)
        val project = manager.findProjectForInvocation(request.identifier)
            ?: return LanguageModelToolResult.Companion.error("No project found for invocation")
        val basePath = project.basePath
            ?: return LanguageModelToolResult.Companion.error("No project base path")

        val templates = ResourceLoader.listTemplates(basePath)

        if (templates.isEmpty()) {
            return LanguageModelToolResult.Companion.success("No templates found.")
        }

        return LanguageModelToolResult.Companion.success(
            "Available templates (${templates.size}):\n${templates.joinToString("\n") { "- $it" }}"
        )
    }
}
