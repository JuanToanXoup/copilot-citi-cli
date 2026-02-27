package com.speckit.plugin.ui.onboarding

import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * Mirrors WelcomeFeatureDescriptor from JetBrains AI Chat plugin.
 * Each descriptor represents one feature row in the Discover panel.
 */
interface SpeckitFeatureDescriptor {
    val id: String
    val icon: Icon
    val title: String
    val description: Description

    data class Description(
        val steps: String,
        val beforeSteps: String = "",
        val afterSteps: String = ""
    ) {
        val asHtml: String?
            get() {
                val parts = mutableListOf<String>()
                if (beforeSteps.isNotBlank()) parts += beforeSteps
                if (steps.isNotBlank()) parts += stepsAsHtml
                if (afterSteps.isNotBlank()) parts += afterSteps
                return parts.joinToString("").ifBlank { null }
            }

        private val stepsAsHtml: String
            get() {
                val items = steps.split("\n").filter { it.isNotBlank() }
                if (items.isEmpty()) return ""
                return "<ol>" + items.joinToString("") { "<li>$it</li>" } + "</ol>"
            }
    }
}

// ── Getting Started ─────────────────────────────────────────────────────────

object ConstitutionFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Constitution"
    override val icon: Icon = AllIcons.Nodes.HomeFolder
    override val title = "Define project principles"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "The constitution is the foundation of the Speckit pipeline — non-negotiable governance principles that all downstream agents must comply with:",
        steps = """
            Select the <b>Speckit Constitution</b> agent
            Provide engineering principles interactively
            Speckit generates <code>constitution.md</code> with semver versioning
        """.trimIndent(),
        afterSteps = "The constitution is referenced by Plan and Analyze to enforce alignment across all artifacts."
    )
}

// ── Specify & Design ────────────────────────────────────────────────────────

object SpecifyFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Specify"
    override val icon: Icon = AllIcons.Actions.Edit
    override val title = "Create feature specifications"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Describe any feature in natural language and Speckit generates a structured spec:",
        steps = """
            Select the <b>Speckit Specify</b> agent from the Sessions tab
            Enter a feature description in the prompt field
            Speckit creates a git branch, feature directory, and <code>spec.md</code>
        """.trimIndent(),
        afterSteps = "The generated spec becomes the source of truth for all downstream artifacts — Plan, Tasks, Checklist, Analyze, and Implement all consume it."
    )
}

object ClarifyFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Clarify"
    override val icon: Icon = AllIcons.Actions.Help
    override val title = "Clarify underspecified requirements"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Speckit identifies gaps in your spec across 9 categories (scope, data model, UX, non-functional, and more) and asks targeted questions:",
        steps = """
            Select the <b>Speckit Clarify</b> agent
            Speckit scans the spec and scores each area as Clear, Partial, or Missing
            Answer up to 5 prioritized clarification questions
            Answers are integrated back into the spec automatically
        """.trimIndent(),
        afterSteps = "Optional — run before Plan to resolve ambiguities. Specify auto-hands off here if the spec has unresolved markers."
    )
}

object PlanFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Plan"
    override val icon: Icon = AllIcons.Actions.ListFiles
    override val title = "Generate implementation plans"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Create a technical design from your feature spec. Requires <code>spec.md</code> and <code>constitution.md</code>:",
        steps = """
            Select the <b>Speckit Plan</b> agent
            Speckit resolves unknowns into <code>research.md</code>, then designs the architecture
            Produces <code>plan.md</code>, <code>data-model.md</code>, <code>contracts/</code>, and <code>quickstart.md</code>
        """.trimIndent(),
        afterSteps = "Plan auto-hands off to Tasks and Checklist when complete."
    )
}

// ── Tasks & Validation ──────────────────────────────────────────────────────

object TasksFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Tasks"
    override val icon: Icon = AllIcons.Vcs.Changelist
    override val title = "Break plans into ordered tasks"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Generate dependency-ordered implementation tasks. Requires <code>plan.md</code> and <code>spec.md</code>:",
        steps = """
            Select the <b>Speckit Tasks</b> agent
            Speckit maps entities and contracts to user stories
            Generates <code>tasks.md</code> with phased, dependency-ordered task IDs
        """.trimIndent(),
        afterSteps = "Tasks are grouped into phases (Setup → Foundational → User Stories → Polish) with parallelizable tasks marked. Auto-hands off to Analyze and Implement."
    )
}

object ChecklistFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Checklist"
    override val icon: Icon = AllIcons.Actions.Checked
    override val title = "Generate requirement checklists"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Create domain-specific checklists that gate implementation. Runs in parallel with Tasks:",
        steps = """
            Select the <b>Speckit Checklist</b> agent and provide a domain (e.g. UX, API, security)
            Speckit asks up to 5 clarifying questions, then generates checklist items
            Each item traces back to a spec section with ≥80% traceability
        """.trimIndent(),
        afterSteps = "Incomplete checklists block the Implement agent — it will stop and ask before proceeding."
    )
}

object AnalyzeFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Analyze"
    override val icon: Icon = AllIcons.Actions.Find
    override val title = "Analyze artifact consistency"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Read-only consistency check across all artifacts. Requires <code>tasks.md</code>, <code>spec.md</code>, <code>plan.md</code>, and <code>constitution.md</code>:",
        steps = """
            Select the <b>Speckit Analyze</b> agent
            Speckit runs 6 detection passes: Duplication, Ambiguity, Underspecification, Constitution Alignment, Coverage Gaps, Inconsistency
            Get a severity-ranked findings report (up to 50 findings)
        """.trimIndent(),
        afterSteps = "Analyze never writes files — it produces a report to stdout only. Constitution violations are always flagged as CRITICAL."
    )
}

// ── Implementation ──────────────────────────────────────────────────────────

object ImplementFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Implement"
    override val icon: Icon = AllIcons.Actions.Execute
    override val title = "Execute implementation tasks"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Execute the full implementation plan phase-by-phase with TDD and checklist gating:",
        steps = """
            Select the <b>Speckit Implement</b> agent
            Speckit checks all checklists — stops if any are incomplete
            Tasks execute in order: tests before code, parallel tasks run concurrently
        """.trimIndent(),
        afterSteps = "Completed tasks are marked <code>[X]</code> in tasks.md. Halts on non-parallel failure but continues successful parallel tasks."
    )
}

object CoverageFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Coverage"
    override val icon: Icon = AllIcons.RunConfigurations.TestState.Run
    override val title = "Drive test coverage to target"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Autonomous coverage orchestrator that drives the full pipeline:",
        steps = """
            Select the <b>Speckit Coverage</b> agent
            Speckit discovers the project and measures baseline coverage
            Drives the full speckit pipeline to reach target coverage
        """.trimIndent()
    )
}

object IssuesFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Issues"
    override val icon: Icon = AllIcons.Vcs.Branch
    override val title = "Create GitHub issues from tasks"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Convert tasks into actionable GitHub issues (requires a GitHub remote):",
        steps = """
            Select the <b>Speckit Issues</b> agent
            Speckit reads tasks.md and creates one issue per task via MCP
            Issues include task IDs, descriptions, labels, and dependency links
        """.trimIndent(),
        afterSteps = "This is the terminal step in the pipeline. Will not create issues in non-GitHub repos."
    )
}
