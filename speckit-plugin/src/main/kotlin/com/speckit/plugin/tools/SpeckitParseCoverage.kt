package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitParseCoverage(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_parse_coverage",
        "Find and read the coverage report for a project or service. Auto-detects report format (JaCoCo XML, lcov, coverage.json, coverage.out). Returns raw report content for analysis.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "Service directory relative to project root (default: '.')"),
                "report_path" to mapOf("type" to "string", "description" to "Override: explicit path to coverage report file")
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
        val path = request.input?.get("path")?.asString ?: "."
        val explicitReport = request.input?.get("report_path")?.asString
        val workDir = if (path == ".") basePath else "$basePath/$path"

        val reportFile = if (explicitReport != null) {
            val f = File(if (explicitReport.startsWith("/")) explicitReport else "$workDir/$explicitReport")
            if (!f.exists()) return LanguageModelToolResult.Companion.error("Report not found: $explicitReport")
            f
        } else {
            findCoverageReport(workDir)
                ?: return LanguageModelToolResult.Companion.error(
                    "No coverage report found in '$path'. Run speckit_run_tests with coverage=true first. " +
                    "Looked for: build/reports/jacoco/*/jacocoTestReport.xml, target/site/jacoco/jacoco.xml, " +
                    "coverage/lcov.info, coverage/coverage-final.json, htmlcov/coverage.json, coverage.out"
                )
        }

        val content = reportFile.readText()
        val format = detectFormat(reportFile.name)

        return LanguageModelToolResult.Companion.success(
            "Coverage report: ${reportFile.absolutePath}\nFormat: $format\nSize: ${content.length} chars\n\n$content"
        )
    }

    private fun findCoverageReport(dir: String): File? {
        val d = File(dir)
        val candidates = listOf(
            "build/reports/jacoco/test/jacocoTestReport.xml",
            "target/site/jacoco/jacoco.xml",
            "coverage/lcov.info",
            "coverage/coverage-final.json",
            "coverage/clover.xml",
            "htmlcov/coverage.json",
            "coverage.out",
        )
        return candidates.map { d.resolve(it) }.firstOrNull { it.exists() }
    }

    private fun detectFormat(fileName: String): String = when {
        fileName.endsWith(".xml") && fileName.contains("jacoco", ignoreCase = true) -> "JaCoCo XML"
        fileName.endsWith(".xml") && fileName.contains("clover", ignoreCase = true) -> "Clover XML"
        fileName.endsWith(".xml") -> "XML (unknown format)"
        fileName == "lcov.info" -> "LCOV"
        fileName.endsWith(".json") -> "JSON"
        fileName == "coverage.out" -> "Go coverage profile"
        else -> "Unknown"
    }
}
