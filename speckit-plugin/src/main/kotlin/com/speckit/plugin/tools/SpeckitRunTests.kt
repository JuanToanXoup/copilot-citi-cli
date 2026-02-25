package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitRunTests(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_run_tests",
        "Run tests with coverage collection. Auto-detects build system (Gradle/Maven/npm/pytest/Go) and coverage tooling. Returns test output and coverage report location.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "Service directory relative to project root (default: '.')"),
                "coverage" to mapOf("type" to "boolean", "description" to "Collect coverage data (default: true)"),
                "command" to mapOf("type" to "string", "description" to "Override: provide an explicit test command instead of auto-detection")
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
        val explicitCommand = request.input?.get("command")?.asString
        val workDir = if (path == ".") basePath else "$basePath/$path"

        val command = explicitCommand
            ?: detectTestCommand(workDir, coverage)
            ?: return LanguageModelToolResult.Companion.error(
                "No build system detected in '$path'. Looked for: build.gradle.kts, build.gradle, pom.xml, package.json, pyproject.toml, setup.py, go.mod. " +
                "Use the 'command' parameter to provide an explicit test command."
            )

        val result = ScriptRunner.exec(listOf("bash", "-c", command), workDir, timeoutSeconds = 300)

        val reportPath = if (coverage) findCoverageReport(workDir) else null
        val output = buildString {
            appendLine(result.output)
            if (reportPath != null) {
                appendLine()
                appendLine("Coverage report found: $reportPath")
            }
        }

        return if (result.success) {
            LanguageModelToolResult.Companion.success(output)
        } else {
            LanguageModelToolResult.Companion.error(output)
        }
    }

    private fun detectTestCommand(dir: String, coverage: Boolean): String? {
        val d = File(dir)
        return when {
            d.resolve("build.gradle.kts").exists() || d.resolve("build.gradle").exists() ->
                if (coverage) "./gradlew test jacocoTestReport 2>&1" else "./gradlew test 2>&1"
            d.resolve("pom.xml").exists() ->
                if (coverage) "mvn test jacoco:report 2>&1" else "mvn test 2>&1"
            d.resolve("package.json").exists() ->
                if (coverage) "npm test -- --coverage 2>&1" else "npm test 2>&1"
            d.resolve("pyproject.toml").exists() || d.resolve("setup.py").exists() ->
                if (coverage) "pytest --cov --cov-report=json --cov-report=term 2>&1" else "pytest 2>&1"
            d.resolve("go.mod").exists() ->
                if (coverage) "go test -coverprofile=coverage.out -covermode=atomic ./... 2>&1" else "go test ./... 2>&1"
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
