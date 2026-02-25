package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitSetupPlan(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_setup_plan",
        "Copy the plan template into the current feature's spec directory. Must be on a feature branch.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "json" to mapOf("type" to "boolean", "description" to "Return JSON output (default: true)")
            ),
            "required" to listOf<String>()
        ),
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val json = request.input?.get("json")?.asBoolean ?: true

        val args = mutableListOf<String>()
        if (json) args.add("--json")

        val result = ScriptRunner.execScript(
            "$basePath/.specify/scripts/bash/setup-plan.sh",
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
