package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationManager
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import com.intellij.openapi.application.ApplicationManager

class SpeckitReadAgent : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_read_agent",
        "Read a Spec-Kit agent definition file.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "Agent file name, e.g. 'speckit.specify.agent.md' or just 'speckit.specify'")
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

        val fileName = if (name.endsWith(".agent.md")) name else "$name.agent.md"
        val content = ResourceLoader.readAgent(basePath, fileName)
            ?: return LanguageModelToolResult.Companion.error("Agent not found: $fileName")

        return LanguageModelToolResult.Companion.success(content)
    }
}
