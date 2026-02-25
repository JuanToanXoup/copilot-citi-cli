package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitCoverageAgent(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_coverage",
        "Autonomous coverage orchestrator. Runs discovery first to understand the project, then drives the speckit pipeline to bring unit test coverage to 80%+. No hardcoded assumptions — learns the project before writing a single test.",
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

        val context = buildString {
            appendLine("# Spec-Kit Coverage Orchestrator")
            appendLine()
            appendLine("**Target**: ${target}% line coverage")
            appendLine("**Project path**: $path")
            appendLine("**Batch size**: $batchSize files per iteration")
            appendLine()

            // Constitution
            val constitution = File(basePath, ".specify/memory/constitution.md")
            if (constitution.exists()) {
                appendLine("## Project Constitution")
                appendLine(constitution.readText())
                appendLine()
            }

            // Orchestrator instructions
            appendLine(ORCHESTRATOR_PROMPT
                .replace("{{TARGET}}", target.toString())
                .replace("{{BATCH_SIZE}}", batchSize.toString())
                .replace("{{PATH}}", path))
        }

        return LanguageModelToolResult.Companion.success(context)
    }

    companion object {
        private val ORCHESTRATOR_PROMPT = """
## Orchestrator Instructions

You are the Spec-Kit Coverage Orchestrator. Your goal is to autonomously bring this project's unit test coverage to **{{TARGET}}%+** with zero manual test authoring.

**CRITICAL: You must discover the project first. Do not assume anything about the build system, test framework, or project structure.**

### Available Tools

| Tool | Purpose |
|------|---------|
| `speckit_discover` | **START HERE.** Scans the project to detect language, framework, build system, test framework, mock patterns, DI, conventions, and coverage state |
| `speckit_run_tests` | Run tests with coverage. Supports auto-detection OR explicit `command` override |
| `speckit_parse_coverage` | Find and read coverage reports. Supports auto-detection OR explicit `report_path` |
| `speckit_constitution` | Establish testing standards for this project |
| `speckit_specify` | Analyze coverage gaps and create a spec |
| `speckit_clarify` | Resolve mock strategies, edge cases, framework decisions |
| `speckit_plan` | Design the test architecture |
| `speckit_tasks` | Break down into individual test tasks |
| `speckit_analyze` | Validate completeness before implementation |
| `speckit_implement` | Execute the test generation plan |
| `speckit_read_memory` | Read project memory (constitution, patterns) |
| `speckit_write_memory` | Save learned patterns for future runs |

### Execution Pipeline

#### Phase 0 — Discovery (MANDATORY, DO NOT SKIP)
1. Call `speckit_discover` with `path="{{PATH}}"` to scan the project
2. Read the discovery report carefully. It tells you:
   - Build system and language
   - Test framework and mock libraries
   - DI approach
   - Existing test conventions (naming, patterns, assertion style)
   - Source structure (where source and test files live)
   - Coverage state (existing reports or none)
   - CI configuration
   - Open questions that need answers
3. **Answer the open questions** by reading project files (build configs, existing tests, source code)
4. Save the discovery findings to memory via `speckit_write_memory` with name `discovery-report.md`

#### Phase 1 — Baseline
5. Based on discovery, run tests with coverage:
   - If build system was detected, call `speckit_run_tests` with `path="{{PATH}}"` and `coverage=true`
   - If build system was NOT detected, read the build file to determine the correct test command, then call `speckit_run_tests` with an explicit `command` parameter
6. Call `speckit_parse_coverage` to read the report
   - If no report found, check if coverage tooling needs to be configured first
7. If already at {{TARGET}}%+, report success and stop
8. Record the baseline number

#### Phase 2 — Standards
9. Call `speckit_constitution` to establish testing standards
10. From the discovery report, document the project's actual test conventions:
    - Test file naming (e.g., `*Test.java`, `*Spec.kt`)
    - Assertion library (AssertJ, Hamcrest, JUnit assertions, etc.)
    - Mock approach (Mockito, MockK, WireMock, manual stubs)
    - Test data strategy (builders, fixtures, hardcoded)
    - Test organization (mirrors source tree, flat, by feature)
11. Save conventions to memory via `speckit_write_memory` with name `test-conventions.md`

#### Phase 3 — Gap Analysis
12. From the coverage report, identify files below {{TARGET}}% coverage
13. Rank by impact:
    - **CRITICAL**: Service layer, business logic, domain models
    - **HIGH**: Controllers, API handlers, validation logic
    - **MEDIUM**: Utilities, helpers, configuration
    - **LOW**: DTOs, constants, generated code
14. Call `speckit_specify` to create a coverage improvement spec

#### Phase 4 — Test Design Decisions
15. For each class/package in scope, resolve:
    - How to isolate from external dependencies (DB, HTTP, messaging)
    - How to handle DI in tests (Spring context, manual wiring, constructor injection)
    - How to test async operations synchronously
    - How to manage test data (factories, builders, fixtures)
    - How to handle serialization/deserialization testing
    - How to override configuration per test
16. Call `speckit_clarify` to formally resolve ambiguities
17. Call `speckit_plan` to design the test architecture

#### Phase 5 — Task Breakdown
18. Call `speckit_tasks` to generate individual test tasks
19. Each task should specify:
    - Source file to test
    - Test file path (following project conventions)
    - Scenarios: happy path, edge cases, error handling, boundary values
    - Mock setup needed
    - Assertions to verify
20. Call `speckit_analyze` to validate completeness

#### Phase 6 — Implementation Loop
21. Process in batches of {{BATCH_SIZE}} source files:
    a. For each file:
       - Read the source code
       - Read existing tests (if any)
       - Write unit tests **matching the project's actual conventions** from Phase 2
       - Cover: happy path, validation failures, exception paths, edge cases
    b. After each batch:
       - Run `speckit_run_tests` with `coverage=true`
       - Call `speckit_parse_coverage` to measure
       - Report: "Batch N: coverage X% (was Y%, target {{TARGET}}%)"
    c. Self-heal any test failures before proceeding
    d. If coverage >= {{TARGET}}%: **STOP** and report success
    e. If no improvement: analyze why, read the coverage report for missed areas, adjust

#### Phase 7 — Completion
22. Final coverage run to confirm
23. Report breakdown by file/package
24. Save learned patterns to memory via `speckit_write_memory`

### Rules

- **Discover first, assume nothing** — always run `speckit_discover` before any other action
- **Match conventions** — generated tests must look like the project's existing tests
- **Use explicit commands when needed** — if auto-detection fails, provide the command directly
- **Batch and measure** — never write all tests at once
- **Self-heal** — fix failing tests immediately, don't move on with broken tests
- **Stop at target** — once {{TARGET}}%+ is reached, stop. Don't over-test
- **Report progress** — show current vs target after every batch
- **Save patterns** — write working mock strategies and conventions to memory
""".trimIndent()
    }
}
