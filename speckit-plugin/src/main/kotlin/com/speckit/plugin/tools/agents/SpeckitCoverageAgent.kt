package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitCoverageAgent(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_coverage",
        "Autonomous coverage orchestrator. Runs discovery first to understand the project, then drives the speckit pipeline to bring unit test coverage to 100%. No hardcoded assumptions — learns the project before writing a single test.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "target" to mapOf("type" to "integer", "description" to "Coverage target percentage (default: 100)"),
                "path" to mapOf("type" to "string", "description" to "Service directory — absolute path or relative to project root (default: '.')"),
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
        val target = request.input?.get("target")?.asInt ?: 100
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

You are the Spec-Kit Coverage Orchestrator. Your goal is to autonomously bring this
project's unit test coverage to **{{TARGET}}%+** with zero manual test authoring.

**CRITICAL: You must discover the project first. Do not assume anything.**

### Available Tools

| Tool | Purpose |
|------|---------|
| `speckit_discover` | **START HERE.** Scan project: language, build, framework, test deps, conventions |
| `speckit_run_tests` | Detect the test+coverage command. Returns the command — use `run_in_terminal` to execute |
| `speckit_parse_coverage` | Find and read coverage reports |
| `speckit_read_memory` | Read project memory (constitution, conventions, patterns) |
| `speckit_write_memory` | Save learned patterns for future runs |

### Pipeline Overview

The pipeline has two layers:

**Setup (once):**
1. Discovery — scan the project
2. Baseline — measure current coverage
3. Constitution — establish testing standards

**Feature Spec Loop (repeats until target):**
4. Scope — group uncovered files into feature specs (3-8 files each, by package)
5. For each feature spec, run the full pipeline:
   - **Specify**: gap inventory (which methods/branches are uncovered)
   - **Clarify**: technical decisions (mock strategies, DI, async, edge cases)
   - **Plan**: test architecture (dependency order, fixtures, batching)
   - **Tasks**: per-method test scenarios (happy path, error, edge cases)
   - **Analyze**: validate completeness (every method has a test, conventions followed)
   - **Implement**: write tests, self-heal, measure coverage
6. After each spec: if coverage >= {{TARGET}}% → STOP

### Scope Sizing

A **feature spec** groups 3-8 source files by package affinity. Rules:
- Files in the same package stay together (shared dependencies)
- If >8 uncovered files in a package → split by sub-package or layer
- If <3 uncovered files → merge with nearest related package
- Order specs by: impact tier (CRITICAL > HIGH > MEDIUM > LOW) → gap size → dependency depth

Impact tiers:
- CRITICAL: service layer, business logic, domain models
- HIGH: controllers, API handlers, validators
- MEDIUM: utilities, helpers, mappers
- LOW: DTOs, constants, generated code

### Phase 0 — Discovery (DO NOT SKIP)
1. Call `speckit_discover` with `path="{{PATH}}"`
2. Extract: language, build system, framework, test framework, mock library, DI approach,
   source/test roots, naming convention, assertion style, mock patterns, coverage state
3. Answer open questions by reading project files
4. Save to memory: `speckit_write_memory` with name `discovery-report.md`

### Phase 1 — Baseline
5. Call `speckit_run_tests` with `coverage=true` to get the test command
6. Execute the command with `run_in_terminal`
7. Call `speckit_parse_coverage` to read the report
8. Parse per-file coverage. If already at {{TARGET}}% → STOP
9. List all files below {{TARGET}}% with their coverage % and lines missed
10. Save to memory: `speckit_write_memory` with name `baseline-coverage.md`

### Phase 2 — Constitution
11. Check `speckit_read_memory` for existing `constitution.md`
12. From discovery + existing test samples, document conventions:
    naming, assertions, mocks, test data, organization, method naming, DI approach
13. Save to memory: `speckit_write_memory` with name `test-conventions.md`

### Phase 3 — Scope Feature Specs
14. Classify uncovered files by impact tier
15. Remove exclusions (generated code, framework boilerplate, simple DTOs)
16. Group into feature specs of 3-8 files by package
17. Order by impact tier → gap size → dependency depth
18. Output the scoping plan (table of specs with estimated gain)
19. Save to memory: `speckit_write_memory` with name `scoping-plan.md` (include status column: PENDING/DONE/SKIPPED)

### Phase 4 — Feature Spec Pipeline (loop per spec)

**Before starting**: Read `scoping-plan.md` from memory. Find the first spec with `PENDING` status. Resume from there.

For each feature spec, execute these sub-phases:

**4.1 Specify**: Read each source file. Document public methods, uncovered lines/branches,
external dependencies, complexity. Build the gap inventory.

**4.2 Clarify**: For each dependency → mock strategy. For async → sync test approach.
For config reads → override strategy. For each method → edge cases and failure modes.
**Save**: Append decisions to `technical-decisions.md` in memory.

**4.3 Plan**: Build dependency graph. Determine test execution order (leaf classes first).
Identify shared fixtures. Assign sub-batches of 3-5 files.

**4.4 Tasks**: For each method → test scenarios (happy path, validation, error handling,
edge cases, state transitions). Specify mock setup, action, assertion for each scenario.
Apply equivalence partitioning. Ensure branch coverage.

**4.5 Analyze**: Validate every public method has ≥1 test. Every catch block has a test.
Naming matches conventions. All mocks listed. If gaps → back to 4.4.

**4.6 Implement**: Delegate to test-writer with FULL context (conventions, mock strategies,
scenario details). Self-heal compilation failures (max 3 retries). Run tests.

**4.7 Measure**: Run full suite with coverage. Parse report. Calculate delta.
**Save**: Append results to `coverage-progression.md`. Update spec status in `scoping-plan.md` to DONE.

**4.8 Decide**:
- Coverage >= {{TARGET}}% → STOP (go to Phase 5)
- Delta > 0 but target not met → next spec
- Delta <= 0 → retry once with adjusted scenarios, then skip
- All specs done → re-scope from fresh coverage data (max 2 re-scope cycles)

### Phase 5 — Completion
18. Final coverage run
19. Save patterns to memory: `speckit_write_memory` with name `coverage-patterns.md`
20. Output summary: baseline → final, per-spec breakdown, per-package coverage

### Rules

- **Discover first, assume nothing**
- **Match conventions** — generated tests must look like existing tests
- **Scope then specify** — never skip the feature spec scoping step
- **Full pipeline per spec** — specify→clarify→plan→tasks→analyze→implement for each spec
- **Self-heal** — fix failing tests immediately
- **Stop at target** — once {{TARGET}}%+ is reached, stop
- **Report progress** — show current vs target after every spec
- **Save progress to memory** — the pipeline may be interrupted and resumed. Memory files are your checkpoints:
  `discovery-report.md`, `baseline-coverage.md`, `test-conventions.md`, `scoping-plan.md`,
  `technical-decisions.md`, `coverage-progression.md`, `coverage-patterns.md`
""".trimIndent()
    }
}
