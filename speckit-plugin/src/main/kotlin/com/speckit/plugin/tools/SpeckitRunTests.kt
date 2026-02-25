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
                "path" to mapOf("type" to "string", "description" to "Service directory relative to project root (default: '.')"),
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
        val workDir = if (path == ".") basePath else "$basePath/$path"

        val command = detectTestCommand(workDir, coverage)
            ?: return LanguageModelToolResult.Companion.error(
                "No build system detected in '$path'. Looked for: build.gradle.kts, build.gradle, pom.xml, package.json, pyproject.toml, setup.py, go.mod. " +
                "Provide the test command directly to run_in_terminal."
            )

        val existingReport = findCoverageReport(workDir)

        val output = buildString {
            appendLine("## Detected Test Configuration")
            appendLine("- **Working directory**: $workDir")
            appendLine("- **Command**: `$command`")
            appendLine("- **Coverage enabled**: $coverage")
            if (existingReport != null) {
                appendLine("- **Existing coverage report**: $existingReport")
            }
            appendLine()
            appendLine("## Next Steps")
            appendLine("1. Run the command using `run_in_terminal`")
            appendLine("2. After tests complete, use `speckit_parse_coverage` to analyze the report")
        }

        return LanguageModelToolResult.Companion.success(output)
    }

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
        val candidates = listOf(
            "build/reports/jacoco/test/jacocoTestReport.xml",
            "build/reports/jacoco/test/html/index.html",
            "target/site/jacoco/jacoco.xml",
            "coverage/lcov.info",
            "coverage/coverage-final.json",
            "coverage/clover.xml",
            "htmlcov/coverage.json",
            "coverage.out",
        )
        return candidates.map { d.resolve(it) }.firstOrNull { it.exists() }?.absolutePath
    }
}
