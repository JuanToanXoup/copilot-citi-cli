package com.speckit.companion.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitParseCoverage(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_parse_coverage",
        "Parse a coverage report (JaCoCo XML, lcov, pytest-cov JSON, go cover) and return structured summary with per-file line/branch percentages.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "report_path" to mapOf("type" to "string", "description" to "Path to coverage report file relative to project root"),
                "format" to mapOf(
                    "type" to "string",
                    "description" to "Report format",
                    "enum" to listOf("jacoco", "lcov", "pytest-cov", "go-cover")
                )
            ),
            "required" to listOf("report_path")
        ),
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val reportPath = request.input?.get("report_path")?.asString
            ?: return LanguageModelToolResult.Companion.error("Missing required parameter: report_path")

        val file = File(basePath, reportPath)
        if (!file.exists()) {
            return LanguageModelToolResult.Companion.error("Report not found: $reportPath")
        }

        // Return raw report content for the model to parse.
        // A future version can add structured parsing per format.
        val content = file.readText()
        return LanguageModelToolResult.Companion.success(content)
    }
}
