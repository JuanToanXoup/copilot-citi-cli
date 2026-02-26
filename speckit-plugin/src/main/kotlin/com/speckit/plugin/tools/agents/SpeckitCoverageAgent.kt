package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import com.intellij.openapi.vfs.LocalFileSystem
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

            // Check which memory files exist to determine current phase
            val hasDiscovery = hasMemoryFile(basePath, "discovery-report.md")
            val hasBaseline = hasMemoryFile(basePath, "baseline-coverage.md")
            val hasConventions = hasMemoryFile(basePath, "test-conventions.md")
            val hasScoping = hasMemoryFile(basePath, "scoping-plan.md")
            val hasCoverageProgression = hasMemoryFile(basePath, "coverage-progression.md")

            appendLine("## Current State")
            appendLine()

            // List available memory files so the model knows what to read
            val available = mutableListOf<String>()
            if (hasDiscovery) available.add("discovery-report.md")
            if (hasBaseline) available.add("baseline-coverage.md")
            if (hasConventions) available.add("test-conventions.md")
            if (hasScoping) available.add("scoping-plan.md")
            if (hasCoverageProgression) available.add("coverage-progression.md")
            if (available.isNotEmpty()) {
                appendLine("**Available memory files** (read with `speckit_read_memory`): ${available.joinToString(", ") { "`$it`" }}")
                appendLine()
            }

            when {
                !hasDiscovery -> {
                    appendLine("**YOU ARE AT: Phase 0 — Discovery**")
                    appendLine()
                    appendLine("You are starting from scratch. Before you can write any tests, you need to")
                    appendLine("understand this project — its language, build system, test framework, and conventions.")
                    appendLine()
                    appendLine("**Steps:**")
                    appendLine("1. Call `speckit_discover` with `path=\"$path\"` — this reads the build file and test files for you")
                    appendLine("2. Save the discover output directly: call `speckit_write_memory` with name `discovery-report.md`")
                    appendLine("3. Do NOT read pom.xml, build.gradle, or package.json — speckit_discover already extracted everything")
                    appendLine()
                    appendLine("**When done:** Call `speckit_coverage` to proceed to Phase 1.")
                }
                !hasBaseline -> {
                    appendLine("**YOU ARE AT: Phase 1 — Baseline**")
                    appendLine()
                    appendLine("Discovery is complete. Now you need to measure where coverage stands today")
                    appendLine("so you know how much work is needed to reach ${target}%.")
                    appendLine()
                    appendLine("**First:** Call `speckit_read_memory` with name `discovery-report.md` to review the project details.")
                    appendLine()
                    appendLine("**Steps:**")
                    appendLine("1. Call `speckit_run_tests` with `coverage=true` — this returns the shell command to run tests with coverage")
                    appendLine("2. Execute that command with `run_in_terminal` and wait for it to finish")
                    appendLine("3. Call `speckit_parse_coverage` to read the coverage report")
                    appendLine("4. If coverage is already >= ${target}% → report success and **STOP**")
                    appendLine("5. Otherwise, list every file below ${target}% with its coverage % and missed line ranges")
                    appendLine("6. Save the full breakdown: call `speckit_write_memory` with name `baseline-coverage.md`")
                    appendLine()
                    appendLine("**When done:** Call `speckit_coverage` to proceed to Phase 2.")
                }
                !hasConventions -> {
                    appendLine("**YOU ARE AT: Phase 2 — Constitution**")
                    appendLine()
                    appendLine("You know the project structure and its coverage gaps. Now establish the testing")
                    appendLine("conventions so every test you generate looks like it belongs in this codebase.")
                    appendLine()
                    appendLine("**First:** Call `speckit_read_memory` for `discovery-report.md` and `baseline-coverage.md` to review prior findings.")
                    appendLine()
                    appendLine("**Steps:**")
                    appendLine("1. Check the `## Constitution` section above in this prompt. If it is present, a constitution already exists — skip to step 3.")
                    appendLine("2. If no constitution exists, call `speckit_constitution` to generate one. This creates `.specify/memory/constitution.md` with project governance rules.")
                    appendLine("3. Read 2-3 existing test files from the project to learn the patterns:")
                    appendLine("   naming style, assertion library, mock patterns, test data setup, file organization")
                    appendLine("4. Document the conventions you found (naming, assertions, mocks, DI, test data, organization)")
                    appendLine("5. Save: call `speckit_write_memory` with name `test-conventions.md`")
                    appendLine()
                    appendLine("**When done:** Call `speckit_coverage` to proceed to Phase 3.")
                }
                !hasScoping -> {
                    appendLine("**YOU ARE AT: Phase 3 — Scope Feature Specs**")
                    appendLine()
                    appendLine("You have conventions established. Now group the uncovered files into manageable")
                    appendLine("feature specs (3-8 files each, grouped by package) so you can tackle them systematically.")
                    appendLine()
                    appendLine("**First:** Call `speckit_read_memory` for `baseline-coverage.md` and `test-conventions.md` to review prior findings.")
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
                    appendLine("**When done:** Call `speckit_coverage` to proceed to Phase 4.")
                }
                else -> {
                    appendLine("**YOU ARE AT: Phase 4 — Feature Spec Pipeline**")
                    appendLine()
                    appendLine("You have a scoping plan with feature specs. Pick the first PENDING spec and")
                    appendLine("run the full test generation pipeline for it.")
                    appendLine()
                    appendLine("**First:** Call `speckit_read_memory` for `scoping-plan.md` and `test-conventions.md` to review the plan and conventions.")
                    if (hasCoverageProgression) {
                        appendLine("Also read `coverage-progression.md` to see progress so far.")
                    }
                    appendLine()
                    appendLine("**Steps for this spec:**")
                    appendLine()
                    appendLine("First, derive a feature directory name from the spec name in scoping-plan.md")
                    appendLine("(e.g., spec \"Auth Services\" → feature `coverage-auth-services`). Pass this as the")
                    appendLine("`feature` parameter to every pipeline tool call below so they all share the same `specs/{feature}/` directory.")
                    appendLine()
                    appendLine("1. Call `speckit_specify` with `description` summarizing the test coverage goal for this spec's files,")
                    appendLine("   and `feature` set to the directory name. This creates `specs/{feature}/spec.md` without a git branch.")
                    appendLine("2. Call `speckit_clarify` with `feature` — resolves mock strategies, async test approaches,")
                    appendLine("   config overrides, edge cases, and failure modes")
                    appendLine("3. Call `speckit_plan` with `feature` — builds dependency graph, orders tests leaf-first, identifies shared fixtures")
                    appendLine("4. Call `speckit_tasks` with `feature` — generates test scenarios per method (happy path, validation, error, edge cases)")
                    appendLine("   with mock setup, action, and assertion for each scenario")
                    appendLine("5. Call `speckit_analyze` with `feature` — verifies every public method has >= 1 test, every catch block has a test,")
                    appendLine("   naming matches conventions. If gaps exist, call `speckit_tasks` again with `feature`")
                    appendLine("6. Call `speckit_implement` with `feature` — writes the test files, runs them, fixes failures (max 3 retries)")
                    appendLine("7. **Measure**: Run `speckit_run_tests` with coverage, then `speckit_parse_coverage`.")
                    appendLine("   Save results to `coverage-progression.md`. Update this spec's status to DONE in `scoping-plan.md`")
                    appendLine()
                    appendLine("**After this spec:**")
                    appendLine("- If coverage >= ${target}% → go to Phase 5: run final coverage, save `coverage-patterns.md`, report summary")
                    appendLine("- If coverage < ${target}% but improved → pick next PENDING spec and repeat")
                    appendLine("- If coverage didn't improve → retry once with adjusted scenarios, then mark SKIPPED")
                    appendLine("- If all specs are DONE/SKIPPED → re-scope from fresh coverage data (max 2 re-scope cycles)")
                }
            }
        }
    }

    override fun getPromptSuffix(request: ToolInvocationRequest, basePath: String): String {
        return buildString {
            appendLine("## CRITICAL — DO NOT STOP")
            appendLine()
            appendLine("You have just read the phase instructions above. Now execute that phase,")
            appendLine("save the results to memory, and **immediately call `speckit_coverage` again**")
            appendLine("to continue to the next phase. Do not summarize. Do not report back.")
            appendLine("Do not say \"the next step is...\". Just do it.")
            appendLine()
            appendLine("The only reasons to stop are:")
            appendLine("1. Coverage has reached or exceeded the target percentage")
            appendLine("2. All feature specs are DONE or SKIPPED after 2 re-scope cycles")
        }
    }

    private fun hasMemoryFile(basePath: String, name: String): Boolean {
        val f = LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(File(basePath, ".specify/memory/$name"))
        return f != null && !f.isDirectory
    }
}
