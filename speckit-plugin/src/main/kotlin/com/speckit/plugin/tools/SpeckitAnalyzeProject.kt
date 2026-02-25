package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitAnalyzeProject(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_analyze_project",
        "Check spec-driven workflow prerequisites. Must be on a feature branch (NNN-name). Returns feature paths and available documents.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "mode" to mapOf(
                    "type" to "string",
                    "description" to "Output mode",
                    "enum" to listOf("json", "paths-only", "full")
                ),
                "require_tasks" to mapOf("type" to "boolean", "description" to "Require tasks.md to exist (for implementation phase)"),
                "include_tasks" to mapOf("type" to "boolean", "description" to "Include tasks.md in available docs list")
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
        val mode = request.input?.get("mode")?.asString ?: "json"
        val requireTasks = request.input?.get("require_tasks")?.asBoolean ?: false
        val includeTasks = request.input?.get("include_tasks")?.asBoolean ?: false

        val args = mutableListOf<String>()
        when (mode) {
            "json" -> args.add("--json")
            "paths-only" -> args.addAll(listOf("--paths-only", "--json"))
            "full" -> args.add("--json")
        }
        if (requireTasks) args.add("--require-tasks")
        if (includeTasks) args.add("--include-tasks")

        val result = ScriptRunner.execScript(
            "$basePath/.specify/scripts/bash/check-prerequisites.sh",
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
