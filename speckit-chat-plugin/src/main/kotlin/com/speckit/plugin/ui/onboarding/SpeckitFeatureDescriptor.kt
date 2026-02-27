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

// ── Specify Workflow ────────────────────────────────────────────────────────

object SpecifyFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Specify"
    override val icon: Icon = AllIcons.Actions.Edit
    override val title = "Create feature specifications"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Describe any feature in natural language and Speckit generates a structured spec:",
        steps = """
            Select the <b>Speckit Specify</b> agent from the Chat tab
            Enter a feature description in the prompt field
            Speckit generates <code>spec.md</code> with structured requirements
        """.trimIndent(),
        afterSteps = "The generated spec becomes the source of truth for all downstream artifacts."
    )
}

object ClarifyFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Clarify"
    override val icon: Icon = AllIcons.Actions.Help
    override val title = "Clarify underspecified requirements"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Speckit identifies gaps in your spec and asks targeted questions:",
        steps = """
            Select the <b>Speckit Clarify</b> agent
            Speckit analyzes the current spec for ambiguities
            Answer up to 5 clarification questions
            Answers are encoded back into the spec automatically
        """.trimIndent()
    )
}

object PlanFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Plan"
    override val icon: Icon = AllIcons.Actions.ListFiles
    override val title = "Generate implementation plans"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Create a technical design plan from your feature spec:",
        steps = """
            Select the <b>Speckit Plan</b> agent
            Speckit reads the spec and generates <code>plan.md</code>
            The plan includes architecture decisions, component design, and data flows
        """.trimIndent()
    )
}

object TasksFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Tasks"
    override val icon: Icon = AllIcons.Vcs.Changelist
    override val title = "Break plans into ordered tasks"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Generate dependency-ordered implementation tasks:",
        steps = """
            Select the <b>Speckit Tasks</b> agent
            Speckit reads plan.md and generates <code>tasks.md</code>
            Tasks are ordered by dependency and grouped into phases
        """.trimIndent()
    )
}

// ── Quality & Analysis ──────────────────────────────────────────────────────

object AnalyzeFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Analyze"
    override val icon: Icon = AllIcons.Actions.Find
    override val title = "Analyze artifact consistency"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Run a non-destructive consistency check across all artifacts:",
        steps = """
            Select the <b>Speckit Analyze</b> agent
            Speckit cross-references spec.md, plan.md, and tasks.md
            Get a report of inconsistencies, gaps, and quality issues
        """.trimIndent()
    )
}

object ChecklistFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Checklist"
    override val icon: Icon = AllIcons.Actions.Checked
    override val title = "Generate requirement checklists"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Create a custom checklist for your feature:",
        steps = """
            Select the <b>Speckit Checklist</b> agent
            Provide the domain or area to generate a checklist for
            Speckit creates a structured requirements checklist
        """.trimIndent()
    )
}

// ── Implementation ──────────────────────────────────────────────────────────

object ImplementFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Implement"
    override val icon: Icon = AllIcons.Actions.Execute
    override val title = "Execute implementation tasks"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Automatically execute all tasks defined in tasks.md:",
        steps = """
            Select the <b>Speckit Implement</b> agent
            Speckit processes tasks phase by phase
            Code is generated, files are created and edited
        """.trimIndent(),
        afterSteps = "Implementation follows the exact plan and task ordering."
    )
}

object CoverageFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Coverage"
    override val icon: Icon = AllIcons.RunConfigurations.TestState.Run
    override val title = "Drive test coverage to target"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Autonomous coverage orchestrator:",
        steps = """
            Select the <b>Speckit Coverage</b> agent
            Speckit discovers the project and measures baseline coverage
            Drives the full speckit pipeline to reach target coverage
        """.trimIndent()
    )
}

// ── Project Setup ───────────────────────────────────────────────────────────

object ConstitutionFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Constitution"
    override val icon: Icon = AllIcons.Nodes.HomeFolder
    override val title = "Define project principles"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Create or update your project's engineering constitution:",
        steps = """
            Select the <b>Speckit Constitution</b> agent
            Provide engineering principles interactively
            Speckit generates a constitution that guides all agents
        """.trimIndent()
    )
}

object IssuesFeatureDescriptor : SpeckitFeatureDescriptor {
    override val id = "Issues"
    override val icon: Icon = AllIcons.Vcs.Branch
    override val title = "Create GitHub issues from tasks"
    override val description = SpeckitFeatureDescriptor.Description(
        beforeSteps = "Convert tasks into actionable GitHub issues:",
        steps = """
            Select the <b>Speckit Issues</b> agent
            Speckit reads tasks.md and creates dependency-ordered issues
            Issues are created directly in your GitHub repository
        """.trimIndent()
    )
}
