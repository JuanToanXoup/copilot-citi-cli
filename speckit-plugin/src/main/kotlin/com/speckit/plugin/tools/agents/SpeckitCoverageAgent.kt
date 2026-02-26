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
            appendLine("## Configuration")
            appendLine("- **Target**: ${target}% line coverage")
            appendLine("- **Project path**: $path")
            appendLine("- **Batch size**: $batchSize files per iteration")
            appendLine()

            appendLine("## File Discovery Rules")
            appendLine("- **NEVER construct file paths by guessing.** Always use `run_in_terminal` to locate files first.")
            appendLine("- To find a source file: `find <source_root> -name \"ClassName.java\" -type f`")
            appendLine("- To list all source files: `find <source_root> -type f -name \"*.java\" | sort`")
            appendLine("- To find files by package: `find <source_root> -path \"*/package/name/*\" -type f`")
            appendLine("- Only pass absolute paths from `find` output to `read_file`. Never assemble paths manually.")
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
                    appendLine("No discovery report found in memory. Execute Phase 0 now:")
                    appendLine("1. Call `speckit_discover` with `path=\"$path\"`")
                    appendLine("2. Extract all project details (language, build, framework, test deps, conventions)")
                    appendLine("3. Save the report: `speckit_write_memory` with name `discovery-report.md`")
                    appendLine("4. Then proceed to Phase 1 (Baseline)")
                }
                baselineCoverage == null -> {
                    appendLine("**YOU ARE AT: Phase 1 — Baseline**")
                    appendLine()
                    appendLine("Discovery is complete. Measure current coverage now:")
                    appendLine("1. Call `speckit_run_tests` with `coverage=true` to get the test command")
                    appendLine("2. Execute the command with `run_in_terminal`")
                    appendLine("3. Call `speckit_parse_coverage` to read the report")
                    appendLine("4. If already at ${target}% → report success and STOP")
                    appendLine("5. List all files below ${target}% with coverage % and lines missed")
                    appendLine("6. Save: `speckit_write_memory` with name `baseline-coverage.md`")
                    appendLine("7. Then proceed to Phase 2 (Constitution)")
                    appendLine()
                    appendLine("### Discovery Report (from memory)")
                    appendLine(discoveryReport)
                }
                testConventions == null -> {
                    appendLine("**YOU ARE AT: Phase 2 — Constitution**")
                    appendLine()
                    appendLine("Baseline measured. Establish testing conventions now:")
                    appendLine("1. Check `speckit_read_memory` for existing `constitution.md`")
                    appendLine("2. From discovery + existing test samples, document conventions")
                    appendLine("3. Save: `speckit_write_memory` with name `test-conventions.md`")
                    appendLine("4. Then proceed to Phase 3 (Scope Feature Specs)")
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
                    appendLine("Conventions established. Scope the uncovered files into feature specs now:")
                    appendLine("1. Classify uncovered files by impact tier (CRITICAL/HIGH/MEDIUM/LOW)")
                    appendLine("2. Remove exclusions (generated code, boilerplate, simple DTOs)")
                    appendLine("3. Group into feature specs of 3-8 files by package")
                    appendLine("4. Order by impact tier → gap size → dependency depth")
                    appendLine("5. Save: `speckit_write_memory` with name `scoping-plan.md` (include PENDING/DONE/SKIPPED status)")
                    appendLine("6. Then proceed to Phase 4 (Feature Spec Pipeline)")
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
                    appendLine("Read the scoping plan below and find the first spec with PENDING status.")
                    appendLine("Execute the full pipeline for that spec: specify→clarify→plan→tasks→analyze→implement→measure.")
                    appendLine("After measuring, update the spec status in `scoping-plan.md` to DONE.")
                    appendLine("If coverage >= ${target}% → go to Phase 5 (Completion).")
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
