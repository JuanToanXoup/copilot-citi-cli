package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitRunTests(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_run_tests",
        "Detect the test command and coverage report location for a project. Auto-detects build system (Gradle/Maven/npm/pytest/Go) and coverage tooling. Returns the command to run — use run_in_terminal to execute it.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "Service directory — absolute path or relative to project root (default: '.')"),
                "coverage" to mapOf("type" to "boolean", "description" to "Include coverage flags in the command (default: true)")
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
        val coverage = request.input?.get("coverage")?.asBoolean ?: true
        val workDir = when {
            path == "." -> basePath
            path.startsWith("/") -> path
            else -> "$basePath/$path"
        }

        // Check discovery memory first
        val discovery = readDiscoveryMemory(workDir)
        val memoryCommand = if (coverage) discovery?.coverageCommand else discovery?.testCommand

        val command = memoryCommand
            ?: detectTestCommand(workDir, coverage)
            ?: return LanguageModelToolResult.Companion.error(
                "No build system detected in '$workDir' (path='$path'). Looked for: build.gradle.kts, build.gradle, pom.xml, package.json, pyproject.toml, setup.py, go.mod. " +
                "Run speckit_discover first, or provide the test command directly to run_in_terminal."
            )

        val reportPath = discovery?.coverageReportPath
        val existingReport = if (reportPath != null) {
            val f = File(if (reportPath.startsWith("/")) reportPath else "$workDir/$reportPath")
            if (f.exists()) f.absolutePath else null
        } else null
            ?: findCoverageReport(workDir)

        val source = if (memoryCommand != null) "discovery memory" else "auto-detect"

        val output = buildString {
            appendLine("## Detected Test Configuration")
            appendLine("- **Working directory**: $workDir")
            appendLine("- **Command**: `$command`")
            appendLine("- **Source**: $source")
            appendLine("- **Coverage enabled**: $coverage")
            if (existingReport != null) {
                appendLine("- **Existing coverage report**: $existingReport")
            }
            if (discovery?.coverageReportPath != null) {
                appendLine("- **Expected report path**: ${discovery.coverageReportPath}")
            }
            appendLine()
            appendLine("## Next Steps")
            appendLine("1. Run the command using `run_in_terminal`")
            appendLine("2. After tests complete, use `speckit_parse_coverage` to analyze the report")
        }

        return LanguageModelToolResult.Companion.success(output)
    }

    private fun readDiscoveryMemory(workDir: String): DiscoveryConfig? {
        // Check both the workDir and basePath for the memory file
        val candidates = listOf(
            File(workDir, ".specify/memory/discovery-report.md"),
            File(basePath, ".specify/memory/discovery-report.md")
        )
        val memoryFile = candidates.firstOrNull { it.exists() } ?: return null
        return parseDiscoveryReport(memoryFile.readText())
    }

    private fun parseDiscoveryReport(content: String): DiscoveryConfig? {
        fun extractField(label: String): String? {
            val regex = Regex("""\*\*$label\*\*:\s*\[?(.+?)\]?\s*$""", RegexOption.MULTILINE)
            val match = regex.find(content) ?: return null
            val value = match.groupValues[1].trim()
            // Skip unfilled template placeholders
            if (value.startsWith("e.g.,") || value == "UNKNOWN" || value.isEmpty()) return null
            return value
        }

        val testCommand = extractField("Test command")
        val coverageCommand = extractField("Coverage command")
        val coverageReportPath = extractField("Coverage report path")
        val coverageReportFormat = extractField("Coverage report format")

        // Only return if at least one useful field was found
        if (testCommand == null && coverageCommand == null && coverageReportPath == null) return null

        return DiscoveryConfig(testCommand, coverageCommand, coverageReportPath, coverageReportFormat)
    }

    private data class DiscoveryConfig(
        val testCommand: String?,
        val coverageCommand: String?,
        val coverageReportPath: String?,
        val coverageReportFormat: String?
    )

    private fun detectTestCommand(dir: String, coverage: Boolean): String? {
        val d = File(dir)
        return when {
            d.resolve("build.gradle.kts").exists() || d.resolve("build.gradle").exists() ->
                if (coverage) "./gradlew test jacocoTestReport" else "./gradlew test"
            d.resolve("pom.xml").exists() ->
                if (coverage) "mvn test jacoco:report" else "mvn test"
            d.resolve("package.json").exists() ->
                if (coverage) "npm test -- --coverage" else "npm test"
            d.resolve("pyproject.toml").exists() || d.resolve("setup.py").exists() ->
                if (coverage) "pytest --cov --cov-report=json --cov-report=term" else "pytest"
            d.resolve("go.mod").exists() ->
                if (coverage) "go test -coverprofile=coverage.out -covermode=atomic ./..." else "go test ./..."
            else -> null
        }
    }

    private fun findCoverageReport(dir: String): String? {
        val d = File(dir)

        // 1. Check well-known static paths first
        val staticCandidates = listOf(
            "build/reports/jacoco/test/jacocoTestReport.xml",
            "build/reports/jacoco/test/html/index.html",
            "target/site/jacoco/jacoco.xml",
            "coverage/lcov.info",
            "coverage/coverage-final.json",
            "coverage/clover.xml",
            "htmlcov/coverage.json",
            "coverage.out",
        )
        staticCandidates.map { d.resolve(it) }.firstOrNull { it.exists() }?.let { return it.absolutePath }

        // 2. Recursive fallback — handles multi-module projects
        val reportFileNames = setOf(
            "jacocoTestReport.xml", "jacoco.xml", "lcov.info",
            "coverage-final.json", "clover.xml", "coverage.out", "index.html"
        )
        return d.walkTopDown()
            .maxDepth(5)
            .filter { it.isFile && it.name in reportFileNames }
            .filter { it.name != "index.html" || it.parentFile?.name == "jacoco" }  // only jacoco html index
            .sortedByDescending { it.lastModified() }
            .firstOrNull()
            ?.absolutePath
    }
}
