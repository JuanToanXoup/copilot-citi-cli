package com.speckit.plugin.service

import com.speckit.plugin.model.ArtifactCheck
import com.speckit.plugin.model.ChecklistStatus
import com.speckit.plugin.model.CheckResult
import com.speckit.plugin.model.FeatureEntry
import com.speckit.plugin.model.PipelineStepDef
import com.speckit.plugin.model.PipelineStepState
import com.speckit.plugin.model.StepStatus
import java.io.File

/**
 * Canonical pipeline step definitions. Both PipelinePanel and PipelineDemoPanel
 * reference this single source of truth.
 */
object PipelineStepRegistry {
    val steps: List<PipelineStepDef> = listOf(
        PipelineStepDef(
            number = 1, id = "constitution", name = "Constitution",
            description = "Create or update project governance principles.",
            isOptional = false,
            prerequisites = listOf(
                ArtifactCheck(".specify/templates/constitution-template.md", "constitution-template.md", isRepoRelative = true)
            ),
            outputs = listOf(
                ArtifactCheck(".specify/memory/constitution.md", "constitution.md", isRepoRelative = true)
            ),
            handsOffTo = listOf("specify"),
            agentFileName = "speckit.constitution.agent.md",
            defaultArgs = "Refer to the `./specify/memory/discovery.md` for project properties"
        ),
        PipelineStepDef(
            number = 2, id = "specify", name = "Specify",
            description = "Feature spec from natural language description.",
            isOptional = false,
            prerequisites = listOf(
                ArtifactCheck(".specify/scripts/bash/create-new-feature.sh", "create-new-feature script", isRepoRelative = true,
                    altRelativePath = ".specify/scripts/powershell/create-new-feature.ps1"),
                ArtifactCheck(".specify/templates/spec-template.md", "spec-template.md", isRepoRelative = true)
            ),
            outputs = listOf(
                ArtifactCheck("spec.md", "spec.md"),
                ArtifactCheck("checklists/requirements.md", "checklists/requirements.md")
            ),
            handsOffTo = listOf("clarify", "plan"),
            agentFileName = "speckit.specify.agent.md",
            defaultArgs = "100% Unit Test Case coverage for all microservices code."
        ),
        PipelineStepDef(
            number = 3, id = "clarify", name = "Clarify",
            description = "Identify and resolve underspecified areas in the spec.",
            isOptional = true,
            prerequisites = listOf(
                ArtifactCheck("spec.md", "spec.md")
            ),
            outputs = emptyList(),
            handsOffTo = listOf("plan"),
            agentFileName = "speckit.clarify.agent.md",
            parentId = "specify"
        ),
        PipelineStepDef(
            number = 4, id = "plan", name = "Plan",
            description = "Generate the technical design \u2014 data models, contracts, research.",
            isOptional = false,
            prerequisites = listOf(
                ArtifactCheck("spec.md", "spec.md"),
                ArtifactCheck(".specify/memory/constitution.md", "constitution.md", isRepoRelative = true),
                ArtifactCheck(".specify/scripts/bash/setup-plan.sh", "setup-plan script", isRepoRelative = true,
                    altRelativePath = ".specify/scripts/powershell/setup-plan.ps1"),
                ArtifactCheck(".specify/templates/plan-template.md", "plan-template.md", isRepoRelative = true)
            ),
            outputs = listOf(
                ArtifactCheck("plan.md", "plan.md"),
                ArtifactCheck("research.md", "research.md"),
                ArtifactCheck("data-model.md", "data-model.md"),
                ArtifactCheck("contracts", "contracts/", isDirectory = true),
                ArtifactCheck("quickstart.md", "quickstart.md")
            ),
            handsOffTo = listOf("tasks", "checklist"),
            agentFileName = "speckit.plan.agent.md"
        ),
        PipelineStepDef(
            number = 5, id = "tasks", name = "Tasks",
            description = "Generate an actionable, dependency-ordered task list.",
            isOptional = false,
            prerequisites = listOf(
                ArtifactCheck("plan.md", "plan.md"),
                ArtifactCheck("spec.md", "spec.md"),
                ArtifactCheck(".specify/templates/tasks-template.md", "tasks-template.md", isRepoRelative = true)
            ),
            outputs = listOf(
                ArtifactCheck("tasks.md", "tasks.md")
            ),
            handsOffTo = listOf("analyze", "implement"),
            agentFileName = "speckit.tasks.agent.md"
        ),
        PipelineStepDef(
            number = 6, id = "checklist", name = "Checklist",
            description = "Validate requirement quality \u2014 completeness, clarity, consistency.",
            isOptional = true,
            prerequisites = listOf(
                ArtifactCheck("spec.md", "spec.md")
            ),
            outputs = listOf(
                ArtifactCheck("checklists", "checklists/", isDirectory = true, requireNonEmpty = true)
            ),
            handsOffTo = emptyList(),
            agentFileName = "speckit.checklist.agent.md"
        ),
        PipelineStepDef(
            number = 7, id = "analyze", name = "Analyze",
            description = "Non-destructive cross-artifact consistency analysis (read-only).",
            isOptional = false,
            prerequisites = listOf(
                ArtifactCheck("tasks.md", "tasks.md"),
                ArtifactCheck("spec.md", "spec.md"),
                ArtifactCheck("plan.md", "plan.md"),
                ArtifactCheck(".specify/memory/constitution.md", "constitution.md", isRepoRelative = true)
            ),
            outputs = emptyList(),
            handsOffTo = emptyList(),
            agentFileName = "speckit.analyze.agent.md"
        ),
        PipelineStepDef(
            number = 8, id = "implement", name = "Implement",
            description = "Execute the implementation plan \u2014 TDD, checklist gating, progress tracking.",
            isOptional = false,
            prerequisites = listOf(
                ArtifactCheck("tasks.md", "tasks.md"),
                ArtifactCheck("plan.md", "plan.md")
            ),
            outputs = emptyList(),
            handsOffTo = listOf("taskstoissues"),
            agentFileName = "speckit.implement.agent.md",
            defaultArgs = "all remaining tasks"
        ),
        PipelineStepDef(
            number = 9, id = "taskstoissues", name = "Tasks \u2192 Issues",
            description = "Convert tasks into issues (GitHub, Bitbucket, or GitLab).",
            isOptional = false,
            prerequisites = listOf(
                ArtifactCheck("tasks.md", "tasks.md")
            ),
            outputs = emptyList(),
            handsOffTo = emptyList(),
            agentFileName = "speckit.taskstoissues.agent.md",
            defaultArgs = "all tasks"
        )
    )
}

/**
 * Pure business logic for pipeline artifact checking, feature scanning,
 * and status derivation. No Swing dependencies.
 */
object PipelineService {

    fun resolveArtifactFile(
        relativePath: String,
        isRepoRelative: Boolean,
        basePath: String,
        featureDir: String?
    ): File? {
        return if (isRepoRelative) File(basePath, relativePath)
        else featureDir?.let { File(it, relativePath) }
    }

    fun checkArtifact(
        artifact: ArtifactCheck,
        basePath: String,
        featureDir: String?,
        mockArtifacts: Set<String>? = null
    ): CheckResult {
        val file = resolveArtifactFile(artifact.relativePath, artifact.isRepoRelative, basePath, featureDir)
        val effective = if (file != null && !file.exists() && artifact.altRelativePath != null) {
            resolveArtifactFile(artifact.altRelativePath, artifact.isRepoRelative, basePath, featureDir) ?: file
        } else file

        // Mock check — artifact is in the mock set
        if (mockArtifacts != null && isMockPresent(artifact, mockArtifacts)) {
            val realExists = effective != null && (if (artifact.isDirectory) effective.isDirectory else effective.isFile)
            return CheckResult(artifact, true, "simulated", resolvedFile = if (realExists) effective else null)
        }

        // Real filesystem check
        if (effective == null) return CheckResult(artifact, false, "no feature dir")

        if (artifact.isDirectory) {
            if (!effective.isDirectory) return CheckResult(artifact, false, "missing")
            val count = effective.listFiles()?.size ?: 0
            if (artifact.requireNonEmpty && count == 0) return CheckResult(artifact, false, "empty dir")
            return CheckResult(artifact, true, "$count file(s)", resolvedFile = effective)
        }

        if (!effective.isFile) return CheckResult(artifact, false, "missing")
        val size = effective.length()
        val detail = if (size < 1024) "${size} B" else String.format("%.1f KB", size / 1024.0)
        return CheckResult(artifact, true, detail, resolvedFile = effective)
    }

    private fun isMockPresent(artifact: ArtifactCheck, mockArtifacts: Set<String>): Boolean {
        if (artifact.relativePath in mockArtifacts) return true
        if (artifact.altRelativePath != null && artifact.altRelativePath in mockArtifacts) return true
        return false
    }

    fun scanFeatures(
        basePath: String,
        steps: List<PipelineStepDef> = PipelineStepRegistry.steps,
        mockArtifacts: Set<String>? = null
    ): List<FeatureEntry> {
        val specsDir = File(basePath, "specs")
        if (!specsDir.isDirectory) return emptyList()

        return specsDir.listFiles()
            ?.filter { it.isDirectory && it.name.matches(Regex("^\\d{3}-.*")) }
            ?.sortedBy { it.name }
            ?.map { dir ->
                val entry = FeatureEntry(dir.name, dir.absolutePath)
                computeFeatureStatus(entry, basePath, steps, mockArtifacts)
                entry
            }
            ?: emptyList()
    }

    fun computeFeatureStatus(
        entry: FeatureEntry,
        basePath: String,
        steps: List<PipelineStepDef> = PipelineStepRegistry.steps,
        mockArtifacts: Set<String>? = null
    ) {
        val outputSteps = steps.filter { it.outputs.isNotEmpty() }
        var completed = 0
        for (step in outputSteps) {
            if (step.outputs.all { checkArtifact(it, basePath, entry.path, mockArtifacts).exists }) completed++
        }
        entry.completedSteps = completed
        entry.totalOutputSteps = outputSteps.size
    }

    fun parseChecklists(featureDir: String): List<ChecklistStatus> {
        val checklistsDir = File(featureDir, "checklists")
        if (!checklistsDir.isDirectory) return emptyList()

        val completedPattern = Regex("""^- \[[xX]]""")
        val incompletePattern = Regex("""^- \[ ]""")

        return checklistsDir.listFiles()
            ?.filter { it.isFile && it.extension == "md" }
            ?.map { file ->
                var completed = 0
                var total = 0
                file.forEachLine { line ->
                    val trimmed = line.trimStart()
                    when {
                        completedPattern.containsMatchIn(trimmed) -> { completed++; total++ }
                        incompletePattern.containsMatchIn(trimmed) -> { total++ }
                    }
                }
                ChecklistStatus(file.name, total, completed, file)
            }
            ?.sortedBy { it.fileName }
            ?: emptyList()
    }

    fun deriveStatus(
        step: PipelineStepDef,
        basePath: String,
        featureDir: String?,
        state: PipelineStepState
    ): StepStatus {
        val prereqResults = step.prerequisites.map { checkArtifact(it, basePath, featureDir) }
        state.prerequisiteResults = prereqResults

        val outputResults = step.outputs.map { checkArtifact(it, basePath, featureDir) }
        state.outputResults = outputResults

        val allPrereqsMet = prereqResults.all { it.exists }
        val allOutputsExist = outputResults.all { it.exists }
        val someOutputsExist = outputResults.any { it.exists }

        // Clarify: complete when spec.md has no [NEEDS CLARIFICATION] markers
        if (step.id == "clarify" && allPrereqsMet) {
            val specFile = resolveArtifactFile("spec.md", false, basePath, featureDir)
            if (specFile != null && specFile.isFile) {
                val hasMarkers = specFile.readText().contains("[NEEDS CLARIFICATION")
                return if (hasMarkers) StepStatus.READY else StepStatus.COMPLETED
            }
        }

        return when {
            step.outputs.isEmpty() && allPrereqsMet -> StepStatus.READY
            allOutputsExist && step.outputs.isNotEmpty() -> StepStatus.COMPLETED
            !allPrereqsMet -> StepStatus.BLOCKED
            someOutputsExist -> StepStatus.IN_PROGRESS
            allPrereqsMet -> StepStatus.READY
            else -> StepStatus.NOT_STARTED
        }
    }
}
