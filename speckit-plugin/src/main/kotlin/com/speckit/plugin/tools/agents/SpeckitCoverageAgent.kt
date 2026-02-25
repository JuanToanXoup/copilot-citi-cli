package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import com.speckit.plugin.tools.ScriptRunner
import java.io.File

class SpeckitCoverageAgent(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_coverage",
        "Autonomous coverage orchestrator. Drives the full speckit pipeline to bring the current project's unit test coverage to 80%+. Detects build system, measures baseline, generates tests in batches, self-heals failures, loops until target is met.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "target" to mapOf("type" to "integer", "description" to "Coverage target percentage (default: 80)"),
                "path" to mapOf("type" to "string", "description" to "Service directory relative to project root (default: '.')"),
                "batch_size" to mapOf("type" to "integer", "description" to "Number of source files to cover per batch (default: 5)")
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
        val target = request.input?.get("target")?.asInt ?: 80
        val path = request.input?.get("path")?.asString ?: "."
        val batchSize = request.input?.get("batch_size")?.asInt ?: 5
        val workDir = if (path == ".") basePath else "$basePath/$path"

        val context = buildString {
            appendLine("# Spec-Kit Coverage Orchestrator")
            appendLine()
            appendLine("**Target**: ${target}% line coverage")
            appendLine("**Project path**: $path")
            appendLine("**Batch size**: $batchSize files per iteration")
            appendLine()

            // Detect build system
            appendLine("## Project Detection")
            val buildSystem = detectBuildSystem(workDir)
            if (buildSystem != null) {
                appendLine("Build system: ${buildSystem.name}")
                appendLine("Test command: ${buildSystem.testCommand}")
                appendLine("Coverage command: ${buildSystem.coverageCommand}")
                appendLine("Test framework: ${buildSystem.testFramework}")
                appendLine()
            } else {
                appendLine("WARNING: No build system auto-detected. You will need to determine the test command manually.")
                appendLine()
            }

            // Detect existing test patterns
            appendLine("## Existing Test Structure")
            val testInfo = detectTestStructure(workDir)
            appendLine(testInfo)
            appendLine()

            // Constitution
            val constitution = File(basePath, ".specify/memory/constitution.md")
            if (constitution.exists()) {
                appendLine("## Project Constitution")
                appendLine(constitution.readText())
                appendLine()
            }

            // Orchestrator instructions
            appendLine(ORCHESTRATOR_PROMPT.replace("{{TARGET}}", target.toString()).replace("{{BATCH_SIZE}}", batchSize.toString()))
        }

        return LanguageModelToolResult.Companion.success(context)
    }

    private data class BuildSystem(
        val name: String,
        val testCommand: String,
        val coverageCommand: String,
        val testFramework: String
    )

    private fun detectBuildSystem(dir: String): BuildSystem? {
        val d = File(dir)
        return when {
            d.resolve("build.gradle.kts").exists() || d.resolve("build.gradle").exists() ->
                BuildSystem("Gradle", "./gradlew test", "./gradlew test jacocoTestReport", "JUnit/JaCoCo")
            d.resolve("pom.xml").exists() ->
                BuildSystem("Maven", "mvn test", "mvn test jacoco:report", "JUnit/JaCoCo")
            d.resolve("package.json").exists() ->
                BuildSystem("npm", "npm test", "npm test -- --coverage", "Jest/Istanbul")
            d.resolve("pyproject.toml").exists() || d.resolve("setup.py").exists() ->
                BuildSystem("Python", "pytest", "pytest --cov --cov-report=json --cov-report=term", "pytest/pytest-cov")
            d.resolve("go.mod").exists() ->
                BuildSystem("Go", "go test ./...", "go test -coverprofile=coverage.out -covermode=atomic ./...", "go test/go cover")
            else -> null
        }
    }

    private fun detectTestStructure(dir: String): String {
        val d = File(dir)
        val result = buildString {
            // Find test directories
            val testDirs = mutableListOf<String>()
            val candidates = listOf("src/test", "test", "tests", "__tests__", "spec", "*_test.go")
            for (candidate in candidates) {
                if (d.resolve(candidate).exists()) testDirs.add(candidate)
            }

            if (testDirs.isNotEmpty()) {
                appendLine("Test directories found: ${testDirs.joinToString(", ")}")
            } else {
                appendLine("No standard test directories found.")
            }

            // Count test files
            val testPatterns = listOf("*Test.kt", "*Test.java", "*.test.ts", "*.test.js", "*.spec.ts", "*.spec.js", "test_*.py", "*_test.py", "*_test.go")
            val countResult = ScriptRunner.exec(
                listOf("bash", "-c", "find . -name '*Test*' -o -name '*test*' -o -name '*spec*' | grep -E '\\.(kt|java|ts|js|py|go)\$' | wc -l"),
                dir
            )
            if (countResult.success) {
                appendLine("Approximate test file count: ${countResult.output.trim()}")
            }

            // Count source files
            val srcResult = ScriptRunner.exec(
                listOf("bash", "-c", "find . -name '*.kt' -o -name '*.java' -o -name '*.ts' -o -name '*.js' -o -name '*.py' -o -name '*.go' | grep -v test | grep -v Test | grep -v spec | grep -v node_modules | grep -v build | grep -v target | grep -v dist | wc -l"),
                dir
            )
            if (srcResult.success) {
                appendLine("Approximate source file count: ${srcResult.output.trim()}")
            }
        }
        return result
    }

    companion object {
        private val ORCHESTRATOR_PROMPT = """
## Orchestrator Instructions

You are the Spec-Kit Coverage Orchestrator. Your goal is to autonomously bring this project's unit test coverage to **{{TARGET}}%+** with zero manual test authoring.

### Available Tools

You have access to these tools — use them throughout the process:

| Tool | Purpose |
|------|---------|
| `speckit_run_tests` | Run tests with coverage collection |
| `speckit_parse_coverage` | Read and analyze coverage reports |
| `speckit_constitution` | Establish testing standards for this project |
| `speckit_specify` | Analyze coverage gaps and create a spec |
| `speckit_plan` | Design the test architecture |
| `speckit_tasks` | Break down into individual test tasks |
| `speckit_analyze` | Validate completeness before implementation |
| `speckit_implement` | Execute the test generation plan |
| `speckit_read_memory` | Read project memory (constitution, patterns) |
| `speckit_write_memory` | Save learned patterns for future runs |
| `speckit_list_agents` | List available agent definitions |
| `speckit_read_agent` | Read a specific agent's instructions |

### Execution Pipeline

Follow this exact sequence:

#### Phase 1 — Baseline
1. Call `speckit_run_tests` with `coverage=true` to get the current coverage baseline
2. Call `speckit_parse_coverage` to read the report and identify the current percentage
3. If already at {{TARGET}}%+, report success and stop
4. Record the baseline number

#### Phase 2 — Standards (speckit.constitution)
5. Call `speckit_constitution` to establish or load testing standards
6. Learn the project's existing test conventions:
   - Test file naming patterns (e.g., `*Test.kt`, `*.test.ts`)
   - Test framework and assertion style
   - Mock patterns (what's mocked, what's real)
   - Test organization (mirrors source structure or flat)
7. Save conventions to memory via `speckit_write_memory`

#### Phase 3 — Analysis (speckit.specify)
8. From the coverage report, identify files below {{TARGET}}% coverage
9. Rank by impact: business logic > controllers > utilities
10. Call `speckit_specify` to create a coverage improvement spec

#### Phase 4 — Strategy (speckit.clarify + speckit.plan)
11. Call `speckit_clarify` to resolve mock strategies, edge cases, framework decisions
12. Call `speckit_plan` to design the test architecture:
    - Shared fixtures and helpers
    - Execution order (leaf dependencies first)
    - Coverage prediction per batch

#### Phase 5 — Task Breakdown (speckit.tasks + speckit.analyze)
13. Call `speckit_tasks` to generate individual test tasks
14. Call `speckit_analyze` to validate completeness — every gap has a task

#### Phase 6 — Implementation Loop
15. Process source files in batches of {{BATCH_SIZE}}:
    a. For each file in the batch:
       - Read the source file to understand what needs testing
       - Read any existing tests for this file
       - Write unit tests following the project's conventions
       - Ensure tests cover: happy path, validation failures, exception paths, edge cases
    b. After each batch:
       - Run `speckit_run_tests` with `coverage=true`
       - Call `speckit_parse_coverage` to measure progress
       - Report: "Batch N complete: coverage now X% (was Y%, target {{TARGET}}%)"
    c. If any tests fail:
       - Read the error output
       - Fix the failing tests (self-heal)
       - Re-run until all pass
    d. If coverage >= {{TARGET}}%: **STOP** and report success
    e. If coverage didn't improve after a batch: analyze why, adjust strategy

#### Phase 7 — Completion
16. Final `speckit_run_tests` with `coverage=true` to confirm
17. Report final coverage breakdown per file/package
18. Save learned patterns to memory via `speckit_write_memory`

### Rules

- **Never skip the baseline measurement** — always know where you start
- **Learn before you write** — read existing tests to match conventions
- **Batch and measure** — don't write all tests at once, measure after each batch
- **Self-heal** — if a test fails to compile or run, fix it immediately
- **Stop at target** — once {{TARGET}}%+ is reached, stop generating. Don't over-test
- **Report progress** — after every batch, show current vs target coverage
- **Save patterns** — write successful mock strategies and patterns to memory for future runs
""".trimIndent()
    }
}
