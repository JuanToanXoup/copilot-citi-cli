package com.speckit.plugin.tools

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
                "description" to mapOf("type" to "string", "description" to "Short feature description for branch naming"),
                "short_name" to mapOf("type" to "string", "description" to "Optional custom short name (2-4 words) for the branch"),
                "number" to mapOf("type" to "integer", "description" to "Optional branch number (overrides auto-detection)")
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
        val shortName = request.input?.get("short_name")?.asString
        val number = request.input?.get("number")?.asInt

        val args = mutableListOf("--json")
        if (shortName != null) args.addAll(listOf("--short-name", shortName))
        if (number != null) args.addAll(listOf("--number", number.toString()))
        args.add(description)

        val result = ScriptRunner.execScript(
            "$basePath/.specify/scripts/bash/create-new-feature.sh",
            args,
            basePath
        )

        return if (result.success) {
            LanguageModelToolResult.Companion.success(result.output)
        } else {
            LanguageModelToolResult.Companion.error(result.output)
        }
    }
}
