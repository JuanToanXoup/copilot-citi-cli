package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import java.io.File

class SpeckitCoverageAgent : AgentTool(
    toolName = "speckit_coverage",
    toolDescription = "Autonomous coverage orchestrator. Runs discovery first to understand the project, then drives the speckit pipeline to bring unit test coverage to 100%. No hardcoded assumptions — learns the project before writing a single test.",
    agentFileName = "speckit.coverage.agent.md",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "target" to mapOf("type" to "integer", "description" to "Coverage target percentage (default: 100)"),
            "path" to mapOf("type" to "string", "description" to "Service directory — absolute path or relative to project root (default: '.')"),
            "batch_size" to mapOf("type" to "integer", "description" to "Number of source files to cover per batch (default: 5)")
        ),
        "required" to listOf<String>()
    )
) {
    override fun gatherExtraContext(request: ToolInvocationRequest, basePath: String): String {
        val target = request.input?.get("target")?.asInt ?: 100
        val path = request.input?.get("path")?.asString ?: "."
        val batchSize = request.input?.get("batch_size")?.asInt ?: 5

        return buildString {
            appendLine("## Your Mission")
            appendLine()
            val pathClause = if (path != ".") " in `$path`" else ""
            appendLine("Autonomously write unit tests for this project$pathClause until line coverage reaches **${target}%**.")
            appendLine("You will discover the project, measure its current coverage, identify untested code,")
            appendLine("and generate test files — all without manual intervention. Process files in batches of $batchSize.")
            appendLine()


            // Read memory files to determine current phase
            val discoveryReport = readMemoryFile(basePath, "discovery-report.md")
            val baselineCoverage = readMemoryFile(basePath, "baseline-coverage.md")
            val testConventions = readMemoryFile(basePath, "test-conventions.md")
            val scopingPlan = readMemoryFile(basePath, "scoping-plan.md")
            val coverageProgression = readMemoryFile(basePath, "coverage-progression.md")

            appendLine("## Current State")
            appendLine()

            when {
                discoveryReport == null -> {
                    appendLine("**YOU ARE AT: Phase 0 — Discovery**")
                    appendLine()
                    appendLine("You are starting from scratch. Before you can write any tests, you need to")
                    appendLine("understand this project — its language, build system, test framework, and conventions.")
                    appendLine()
                    appendLine("**Steps:**")
                    appendLine("1. Call `speckit_discover` with `path=\"$path\"`")
                    appendLine("2. From the result, extract: language, build system (Gradle/Maven/npm/etc),")
                    appendLine("   framework, test framework, mock library, DI approach, source root, test root,")
                    appendLine("   naming conventions, assertion style, and existing coverage setup")
                    appendLine("3. If any details are unclear, read project files (build.gradle, pom.xml, package.json, etc.) to confirm")
                    appendLine("4. Save everything you learned: call `speckit_write_memory` with name `discovery-report.md`")
                    appendLine()
                    appendLine("**When done:** Proceed immediately to Phase 1 — measure the baseline coverage.")
                }
                baselineCoverage == null -> {
                    appendLine("**YOU ARE AT: Phase 1 — Baseline**")
                    appendLine()
                    appendLine("Discovery is complete. Now you need to measure where coverage stands today")
                    appendLine("so you know how much work is needed to reach ${target}%.")
                    appendLine()
                    appendLine("**Steps:**")
                    appendLine("1. Call `speckit_run_tests` with `coverage=true` — this returns the shell command to run tests with coverage")
                    appendLine("2. Execute that command with `run_in_terminal` and wait for it to finish")
                    appendLine("3. Call `speckit_parse_coverage` to read the coverage report")
                    appendLine("4. If coverage is already >= ${target}% → report success and **STOP**")
                    appendLine("5. Otherwise, list every file below ${target}% with its coverage % and missed line ranges")
                    appendLine("6. Save the full breakdown: call `speckit_write_memory` with name `baseline-coverage.md`")
                    appendLine()
                    appendLine("**When done:** Proceed to Phase 2 — establish testing conventions.")
                    appendLine()
                    appendLine("### Discovery Report (from memory)")
                    appendLine(discoveryReport)
                }
                testConventions == null -> {
                    appendLine("**YOU ARE AT: Phase 2 — Constitution**")
                    appendLine()
                    appendLine("You know the project structure and its coverage gaps. Now establish the testing")
                    appendLine("conventions so every test you generate looks like it belongs in this codebase.")
                    appendLine()
                    appendLine("**Steps:**")
                    appendLine("1. Check the `## Constitution` section above in this prompt. If it is present, a constitution already exists — skip to step 3.")
                    appendLine("2. If no constitution exists, call `speckit_constitution` to generate one. This creates `.specify/memory/constitution.md` with project governance rules.")
                    appendLine("3. Read 2-3 existing test files from the project to learn the patterns:")
                    appendLine("   naming style, assertion library, mock patterns, test data setup, file organization")
                    appendLine("4. Document the conventions you found (naming, assertions, mocks, DI, test data, organization)")
                    appendLine("5. Save: call `speckit_write_memory` with name `test-conventions.md`")
                    appendLine()
                    appendLine("**When done:** Proceed to Phase 3 — scope the uncovered files into feature specs.")
                    appendLine()
                    appendLine("### Discovery Report (from memory)")
                    appendLine(discoveryReport)
                    appendLine()
                    appendLine("### Baseline Coverage (from memory)")
                    appendLine(baselineCoverage)
                }
                scopingPlan == null -> {
                    appendLine("**YOU ARE AT: Phase 3 — Scope Feature Specs**")
                    appendLine()
                    appendLine("You have conventions established. Now group the uncovered files into manageable")
                    appendLine("feature specs (3-8 files each, grouped by package) so you can tackle them systematically.")
                    appendLine()
                    appendLine("**Steps:**")
                    appendLine("1. From the baseline coverage data, identify all files below ${target}%")
                    appendLine("2. Classify each by impact tier:")
                    appendLine("   - CRITICAL: service layer, business logic, domain models")
                    appendLine("   - HIGH: controllers, API handlers, validators")
                    appendLine("   - MEDIUM: utilities, helpers, mappers")
                    appendLine("   - LOW: DTOs, constants, generated code")
                    appendLine("3. Exclude files that don't need tests (generated code, framework boilerplate, simple DTOs)")
                    appendLine("4. Group remaining files into feature specs of 3-8 files by package affinity")
                    appendLine("5. Order specs by: impact tier → gap size → dependency depth")
                    appendLine("6. Save as a table with columns: spec name, files, impact tier, estimated gain, status=PENDING")
                    appendLine("   Call `speckit_write_memory` with name `scoping-plan.md`")
                    appendLine()
                    appendLine("**When done:** Proceed to Phase 4 — start the feature spec pipeline.")
                    appendLine()
                    appendLine("### Baseline Coverage (from memory)")
                    appendLine(baselineCoverage)
                    appendLine()
                    appendLine("### Test Conventions (from memory)")
                    appendLine(testConventions)
                }
                else -> {
                    appendLine("**YOU ARE AT: Phase 4 — Feature Spec Pipeline**")
                    appendLine()
                    appendLine("You have a scoping plan with feature specs. Pick the first PENDING spec and")
                    appendLine("run the full test generation pipeline for it.")
                    appendLine()
                    appendLine("**Steps for this spec:**")
                    appendLine("1. **Specify**: Read each source file in the spec. Document every public method,")
                    appendLine("   uncovered lines/branches, external dependencies, and complexity")
                    appendLine("2. **Clarify**: For each dependency → decide mock strategy. For async code → sync test approach.")
                    appendLine("   For config reads → override strategy. Document edge cases and failure modes")
                    appendLine("3. **Plan**: Build dependency graph. Order tests leaf-first. Identify shared fixtures")
                    appendLine("4. **Tasks**: For each method → list test scenarios (happy path, validation, error, edge cases).")
                    appendLine("   Specify mock setup, action, and assertion for each scenario")
                    appendLine("5. **Analyze**: Verify every public method has >= 1 test. Every catch block has a test.")
                    appendLine("   Check naming matches conventions. If gaps exist → go back to step 4")
                    appendLine("6. **Implement**: Write the test files. Run them. If they fail, fix and retry (max 3 attempts)")
                    appendLine("7. **Measure**: Run full test suite with coverage. Call `speckit_parse_coverage`.")
                    appendLine("   Save results to `coverage-progression.md`. Update this spec's status to DONE in `scoping-plan.md`")
                    appendLine()
                    appendLine("**After this spec:**")
                    appendLine("- If coverage >= ${target}% → go to Phase 5: run final coverage, save `coverage-patterns.md`, report summary")
                    appendLine("- If coverage < ${target}% but improved → pick next PENDING spec and repeat")
                    appendLine("- If coverage didn't improve → retry once with adjusted scenarios, then mark SKIPPED")
                    appendLine("- If all specs are DONE/SKIPPED → re-scope from fresh coverage data (max 2 re-scope cycles)")
                    appendLine()
                    appendLine("### Scoping Plan (from memory)")
                    appendLine(scopingPlan)
                    appendLine()
                    appendLine("### Test Conventions (from memory)")
                    appendLine(testConventions)
                    if (coverageProgression != null) {
                        appendLine()
                        appendLine("### Coverage Progression (from memory)")
                        appendLine(coverageProgression)
                    }
                }
            }
        }
    }

    private fun readMemoryFile(basePath: String, name: String): String? {
        val file = LocalFileSystem.getInstance()
            .findFileByIoFile(File(basePath, ".specify/memory/$name"))
        return if (file != null && !file.isDirectory) VfsUtilCore.loadText(file) else null
    }
}
