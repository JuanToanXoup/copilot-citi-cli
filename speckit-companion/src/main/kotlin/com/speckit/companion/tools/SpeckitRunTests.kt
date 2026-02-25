package com.speckit.companion.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitRunTests(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_run_tests",
        "Run tests and collect coverage report. Auto-detects build system (Gradle/Maven/npm/pytest/Go) from the project root or a subdirectory.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "Service directory relative to project root (default: '.')"),
                "coverage" to mapOf("type" to "boolean", "description" to "Collect coverage data (default: true)")
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
        val coverage = request.input?.get("coverage")?.asBoolean ?: true
        val workDir = if (path == ".") basePath else "$basePath/$path"

        val command = detectTestCommand(workDir, coverage)
            ?: return LanguageModelToolResult.Companion.error("No build system detected in $path. Looked for build.gradle.kts, pom.xml, package.json, pyproject.toml, go.mod.")

        val result = ScriptRunner.exec(listOf("bash", "-c", command), workDir, timeoutSeconds = 300)

        return if (result.success) {
            LanguageModelToolResult.Companion.success(result.output)
        } else {
            LanguageModelToolResult.Companion.error(result.output)
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
                if (coverage) "pytest --cov --cov-report=json 2>&1" else "pytest 2>&1"
            d.resolve("go.mod").exists() ->
                if (coverage) "go test -coverprofile=coverage.out ./... 2>&1" else "go test ./... 2>&1"
            else -> null
        }
    }
}
