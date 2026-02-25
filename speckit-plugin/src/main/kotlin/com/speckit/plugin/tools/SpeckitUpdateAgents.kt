package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitUpdateAgents(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_update_agents",
        "Update agent context files from the current feature's plan.md. Optionally target a specific agent type.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "agent_type" to mapOf(
                    "type" to "string",
                    "description" to "Optional agent type to update (e.g. claude, gemini, copilot, cursor-agent, qwen). Omit to update all."
                )
            )
        ),
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val agentType = request.input?.get("agent_type")?.asString

        val args = mutableListOf<String>()
        if (agentType != null) args.add(agentType)

        val result = ScriptRunner.execScript(
            "$basePath/.specify/scripts/bash/update-agent-context.sh",
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
