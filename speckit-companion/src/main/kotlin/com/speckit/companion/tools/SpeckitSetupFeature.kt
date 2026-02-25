package com.speckit.companion.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitSetupFeature(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_setup_feature",
        "Create a new feature branch and spec scaffold. Auto-detects next feature number, creates specs/NNN-name/ directory with spec-template.md.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "description" to mapOf("type" to "string", "description" to "Short feature description for branch naming")
            ),
            "required" to listOf("description")
        ),
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val description = request.input?.get("description")?.asString
            ?: return LanguageModelToolResult.Companion.error("Missing required parameter: description")

        val result = ScriptRunner.execScript(
            "$basePath/.specify/scripts/bash/create-new-feature.sh",
            listOf(description),
            basePath
        )

        return if (result.success) {
            LanguageModelToolResult.Companion.success(result.output)
        } else {
            LanguageModelToolResult.Companion.error(result.output)
        }
    }
}
