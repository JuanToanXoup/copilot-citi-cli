package com.speckit.companion.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitRunTests(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_run_tests",
        "Run tests and collect coverage report. Auto-detects build system (Gradle/Maven/npm/pytest/Go).",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "Service directory relative to project root"),
                "coverage" to mapOf("type" to "boolean", "description" to "Collect coverage data")
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
        val coverage = request.input?.get("coverage")?.asBoolean ?: true

        val args = mutableListOf(path)
        if (coverage) args.add("--coverage")

        val result = ScriptRunner.execScript(
            "$basePath/.specify/scripts/bash/run-tests.sh",
            args,
            basePath,
            timeoutSeconds = 300
        )

        return if (result.success) {
            LanguageModelToolResult.Companion.success(result.output)
        } else {
            LanguageModelToolResult.Companion.error(result.output)
        }
    }
}
