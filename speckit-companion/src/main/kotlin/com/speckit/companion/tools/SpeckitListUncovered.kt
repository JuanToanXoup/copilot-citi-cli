package com.speckit.companion.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitListUncovered(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_list_uncovered",
        "List source files with low coverage. Reads an existing coverage report (JaCoCo XML, lcov, or coverage.out) and returns files below the threshold.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "Service directory relative to project root (default: '.')"),
                "threshold" to mapOf("type" to "number", "description" to "Coverage threshold percentage (default: 80)")
            ),
            "required" to emptyList<String>()
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
        val workDir = if (path == ".") basePath else "$basePath/$path"

        // Find the coverage report
        val report = findCoverageReport(workDir)
            ?: return LanguageModelToolResult.Companion.error(
                "No coverage report found in $path. Run speckit_run_tests with coverage=true first. " +
                "Looked for: build/reports/jacoco/*/jacocoTestReport.xml, coverage/lcov.info, coverage.out"
            )

        val content = report.readText()
        return LanguageModelToolResult.Companion.success(
            "Coverage report (${report.name}, threshold: $threshold%):\n$content"
        )
    }

    private fun findCoverageReport(dir: String): File? {
        val d = File(dir)
        val candidates = listOf(
            "build/reports/jacoco/test/jacocoTestReport.xml",
            "target/site/jacoco/jacoco.xml",
            "coverage/lcov.info",
            "coverage/coverage-final.json",
            "htmlcov/coverage.json",
            "coverage.out",
        )
        return candidates.map { d.resolve(it) }.firstOrNull { it.exists() }
    }
}
