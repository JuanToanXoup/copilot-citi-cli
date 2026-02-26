package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationManager
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import com.intellij.openapi.application.ApplicationManager

class SpeckitReadTemplate : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_read_template",
        "Read a Spec-Kit template file.",
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
        val manager = ApplicationManager.getApplication().getService(ToolInvocationManager::class.java)
        val project = manager.findProjectForInvocation(request.identifier)
            ?: return LanguageModelToolResult.Companion.error("No project found for invocation")
        val basePath = project.basePath
            ?: return LanguageModelToolResult.Companion.error("No project base path")

        val name = request.input?.get("name")?.asString
            ?: return LanguageModelToolResult.Companion.error("Missing required parameter: name")

        if (!PathSandbox.isSafeName(name)) {
            return LanguageModelToolResult.Companion.error("Invalid template name: '$name'")
        }

        val content = ResourceLoader.readTemplate(basePath, name)
            ?: return LanguageModelToolResult.Companion.error("Template not found: $name")

        return LanguageModelToolResult.Companion.success(content)
    }
}
