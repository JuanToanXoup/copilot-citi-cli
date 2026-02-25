package com.speckit.companion.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitListUncovered(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_list_uncovered",
        "List source files below a coverage threshold, sorted by impact (lines missed). Requires a prior speckit_run_tests call with coverage enabled.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "Service directory relative to project root"),
                "threshold" to mapOf("type" to "number", "description" to "Coverage threshold percentage (default: 80)")
            ),
            "required" to listOf("path")
        ),
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val path = request.input?.get("path")?.asString ?: "."
        val threshold = request.input?.get("threshold")?.asInt ?: 80

        val result = ScriptRunner.execScript(
            "$basePath/.specify/scripts/bash/list-uncovered.sh",
            listOf(path, threshold.toString()),
            basePath
        )

        return if (result.success) {
            LanguageModelToolResult.Companion.success(result.output)
        } else {
            LanguageModelToolResult.Companion.error(result.output)
        }
    }
}
