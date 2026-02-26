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
                "path" to mapOf("type" to "string", "description" to "Service directory — absolute path or relative to project root (default: '.')"),
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
        val workDir = when {
            path == "." -> basePath
            path.startsWith("/") -> path
            else -> "$basePath/$path"
        }

        val d = File(workDir)
        if (!d.isDirectory) {
            return LanguageModelToolResult.Companion.error(
                "Directory not found: $workDir (path='$path', basePath='$basePath'). " +
                "If this is a file path, use the report_path parameter instead."
            )
        }

        val reportFile = if (explicitReport != null) {
            val f = File(if (explicitReport.startsWith("/")) explicitReport else "$workDir/$explicitReport")
            if (!f.exists()) return LanguageModelToolResult.Companion.error(
                "Report not found: ${f.absolutePath} (report_path='$explicitReport', workDir='$workDir')"
            )
            f
        } else {
            // Try discovery memory first for the known report path
            findFromDiscoveryMemory(workDir)
                ?: findCoverageReport(workDir)
                ?: return LanguageModelToolResult.Companion.error(
                    "No coverage report found in '$workDir'. Run speckit_run_tests with coverage=true first.\n" +
                    "Checked discovery memory, static paths, and recursive search.\n" +
                    "Tip: use report_path parameter to specify the exact file location."
                )
        }

        val content = reportFile.readText()
        val format = detectFormat(reportFile.name)

        return LanguageModelToolResult.Companion.success(
            "Coverage report: ${reportFile.absolutePath}\nFormat: $format\nSize: ${content.length} chars\n\n$content"
        )
    }

    private fun findFromDiscoveryMemory(workDir: String): File? {
        val candidates = listOf(
            File(workDir, ".specify/memory/discovery-report.md"),
            File(basePath, ".specify/memory/discovery-report.md")
        )
        val memoryFile = candidates.firstOrNull { it.exists() } ?: return null
        val content = memoryFile.readText()

        // Extract coverage report path from the discovery report
        val regex = Regex("""\*\*Coverage report path\*\*:\s*\[?(.+?)\]?\s*$""", RegexOption.MULTILINE)
        val match = regex.find(content) ?: return null
        val reportPath = match.groupValues[1].trim()
        if (reportPath.startsWith("e.g.,") || reportPath == "UNKNOWN" || reportPath.isEmpty()) return null

        val f = File(if (reportPath.startsWith("/")) reportPath else "$workDir/$reportPath")
        return if (f.exists()) f else null
    }

    private fun findCoverageReport(dir: String): File? {
        val d = File(dir)

        // 1. Check well-known static paths first
        val staticCandidates = listOf(
            "build/reports/jacoco/test/jacocoTestReport.xml",
            "target/site/jacoco/jacoco.xml",
            "coverage/lcov.info",
            "coverage/coverage-final.json",
            "coverage/clover.xml",
            "htmlcov/coverage.json",
            "coverage.out",
        )
        staticCandidates.map { d.resolve(it) }.firstOrNull { it.exists() }?.let { return it }

        // 2. Recursive fallback — handles multi-module projects, non-standard paths
        val reportFileNames = setOf(
            "jacocoTestReport.xml", "jacoco.xml", "lcov.info",
            "coverage-final.json", "clover.xml", "coverage.out"
        )
        return d.walkTopDown()
            .maxDepth(5)
            .filter { it.isFile && it.name in reportFileNames }
            .sortedByDescending { it.lastModified() }  // most recent first
            .firstOrNull()
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
