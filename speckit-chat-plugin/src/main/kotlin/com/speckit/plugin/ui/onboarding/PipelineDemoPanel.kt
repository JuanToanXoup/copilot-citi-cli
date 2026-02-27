package com.speckit.plugin.ui.onboarding

import com.github.copilot.api.CopilotChatService
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.speckit.plugin.tools.ResourceLoader
import com.speckit.plugin.ui.ChatRun
import com.speckit.plugin.ui.ChatRunStatus
import com.speckit.plugin.ui.SessionPanel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/**
 * Embeddable Pipeline demo that reuses the three-pane layout from
 * [com.speckit.plugin.ui.PipelinePanel] with real artifact checking
 * and a working Run button.
 *
 * Embedded inside [SpeckitFeaturePanel] for the Constitution feature
 * so new users can try the pipeline from the Onboarding tab.
 */
class PipelineDemoPanel(
    private val project: Project,
    private val sessionPanel: SessionPanel,
    /** Index of the step to pre-select (0-based). */
    private val initialStepIndex: Int = 0
) : JPanel(BorderLayout()) {

    // ── Data model (mirrors PipelinePanel) ────────────────────────────────────

    private enum class StepStatus { NOT_STARTED, READY, IN_PROGRESS, COMPLETED, BLOCKED }

    private data class ArtifactCheck(
        val relativePath: String,
        val label: String,
        val isDirectory: Boolean = false,
        val requireNonEmpty: Boolean = false,
        val isRepoRelative: Boolean = false,
        val altRelativePath: String? = null
    )

    private data class CheckResult(val artifact: ArtifactCheck, val exists: Boolean, val detail: String)

    private data class StepDef(
        val number: Int,
        val id: String,
        val name: String,
        val description: String,
        val isOptional: Boolean,
        val prerequisites: List<ArtifactCheck>,
        val outputs: List<ArtifactCheck>,
        val handsOffTo: List<String>,
        val agentFileName: String,
        val defaultArgs: String = ""
    )

    private data class FeatureEntry(
        val dirName: String,
        val path: String,
        var completedSteps: Int = 0,
        var totalOutputSteps: Int = 0
    )

    // ── Pipeline step definitions (same as PipelinePanel) ─────────────────────

    private val steps = listOf(
        StepDef(1, "constitution", "Constitution",
            "Create or update project governance principles.", false,
            listOf(ArtifactCheck(".specify/templates/constitution-template.md", "constitution-template.md", isRepoRelative = true)),
            listOf(ArtifactCheck(".specify/memory/constitution.md", "constitution.md", isRepoRelative = true)),
            listOf("specify"), "speckit.constitution.agent.md",
            "Refer to the `./specify/memory/discovery.md` for project properties"),
        StepDef(2, "specify", "Specify",
            "Feature spec from natural language description.", false,
            listOf(ArtifactCheck(".specify/scripts/bash/create-new-feature.sh", "create-new-feature script", isRepoRelative = true,
                altRelativePath = ".specify/scripts/powershell/create-new-feature.ps1"),
                ArtifactCheck(".specify/templates/spec-template.md", "spec-template.md", isRepoRelative = true)),
            listOf(ArtifactCheck("spec.md", "spec.md"), ArtifactCheck("checklists/requirements.md", "checklists/requirements.md")),
            listOf("clarify", "plan"), "speckit.specify.agent.md"),
        StepDef(3, "clarify", "Clarify",
            "Identify and resolve underspecified areas in the spec.", true,
            listOf(ArtifactCheck("spec.md", "spec.md")), emptyList(),
            listOf("plan"), "speckit.clarify.agent.md"),
        StepDef(4, "plan", "Plan",
            "Generate the technical design \u2014 data models, contracts, research.", false,
            listOf(ArtifactCheck("spec.md", "spec.md"),
                ArtifactCheck(".specify/memory/constitution.md", "constitution.md", isRepoRelative = true),
                ArtifactCheck(".specify/scripts/bash/setup-plan.sh", "setup-plan script", isRepoRelative = true,
                    altRelativePath = ".specify/scripts/powershell/setup-plan.ps1"),
                ArtifactCheck(".specify/templates/plan-template.md", "plan-template.md", isRepoRelative = true)),
            listOf(ArtifactCheck("plan.md", "plan.md"), ArtifactCheck("research.md", "research.md"),
                ArtifactCheck("data-model.md", "data-model.md"), ArtifactCheck("contracts", "contracts/", isDirectory = true),
                ArtifactCheck("quickstart.md", "quickstart.md")),
            listOf("tasks", "checklist"), "speckit.plan.agent.md"),
        StepDef(5, "tasks", "Tasks",
            "Generate an actionable, dependency-ordered task list.", false,
            listOf(ArtifactCheck("plan.md", "plan.md"), ArtifactCheck("spec.md", "spec.md"),
                ArtifactCheck(".specify/templates/tasks-template.md", "tasks-template.md", isRepoRelative = true)),
            listOf(ArtifactCheck("tasks.md", "tasks.md")),
            listOf("analyze", "implement"), "speckit.tasks.agent.md"),
        StepDef(6, "checklist", "Checklist",
            "Validate requirement quality \u2014 completeness, clarity, consistency.", true,
            listOf(ArtifactCheck("spec.md", "spec.md")),
            listOf(ArtifactCheck("checklists", "checklists/", isDirectory = true, requireNonEmpty = true)),
            emptyList(), "speckit.checklist.agent.md"),
        StepDef(7, "analyze", "Analyze",
            "Non-destructive cross-artifact consistency analysis (read-only).", false,
            listOf(ArtifactCheck("tasks.md", "tasks.md"), ArtifactCheck("spec.md", "spec.md"),
                ArtifactCheck("plan.md", "plan.md"),
                ArtifactCheck(".specify/memory/constitution.md", "constitution.md", isRepoRelative = true)),
            emptyList(), emptyList(), "speckit.analyze.agent.md"),
        StepDef(8, "implement", "Implement",
            "Execute the implementation plan \u2014 TDD, checklist gating, progress tracking.", false,
            listOf(ArtifactCheck("tasks.md", "tasks.md"), ArtifactCheck("plan.md", "plan.md")),
            emptyList(), listOf("taskstoissues"), "speckit.implement.agent.md",
            "all remaining tasks"),
        StepDef(9, "taskstoissues", "Tasks \u2192 Issues",
            "Convert tasks into GitHub issues (requires GitHub remote).", false,
            listOf(ArtifactCheck("tasks.md", "tasks.md")),
            emptyList(), emptyList(), "speckit.taskstoissues.agent.md",
            "all tasks")
    )

    // ── Mock artifact set ────────────────────────────────────────────────────
    // Simulates the pipeline progression: all repo-relative prerequisites are
    // present (Init Speckit was run) and all outputs from steps prior to
    // initialStepIndex are treated as existing. This lets the user see the
    // dependency chain build as they navigate Next through feature pages.

    private val mockExistingArtifacts: Set<String> = buildSet {
        // All repo-relative prereqs (templates, scripts) — Init Speckit done
        for (step in steps) {
            for (a in step.prerequisites) {
                if (a.isRepoRelative) {
                    add(a.relativePath)
                    a.altRelativePath?.let { add(it) }
                }
            }
        }
        // Outputs from all completed steps (steps before the current one)
        for (i in 0 until initialStepIndex) {
            for (a in steps[i].outputs) {
                add(a.relativePath)
            }
        }
    }

    // ── UI state ──────────────────────────────────────────────────────────────

    private var selectedFeatureEntry: FeatureEntry? = null

    private val featureListModel = DefaultListModel<FeatureEntry>()
    private val featureList = JBList(featureListModel)

    private val stepListModel = DefaultListModel<StepDef>().apply {
        steps.forEach { addElement(it) }
    }
    private val stepList = JBList(stepListModel)

    private val detailPanel = JPanel(BorderLayout())

    /** Cached status per step, recomputed on feature selection. */
    private val stepStatuses = mutableMapOf<String, StepStatus>()
    private val stepPrereqs = mutableMapOf<String, List<CheckResult>>()
    private val stepOutputs = mutableMapOf<String, List<CheckResult>>()

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        border = BorderFactory.createCompoundBorder(
            RoundedLineBorder(JBColor.border(), 6),
            BorderFactory.createEmptyBorder(0, 0, 0, 0)
        )
        preferredSize = Dimension(0, 380)
        minimumSize = Dimension(0, 300)

        // Feature list (left pane)
        featureList.cellRenderer = FeatureListRenderer()
        featureList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                selectedFeatureEntry = featureList.selectedValue
                recomputeStatuses()
                stepList.repaint()
                val selected = stepList.selectedValue
                if (selected != null) updateDetailPanel(selected)
            }
        }

        // Step list (middle pane)
        stepList.cellRenderer = StepListRenderer()
        stepList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val selected = stepList.selectedValue
                if (selected != null) updateDetailPanel(selected)
            }
        }

        // Three-pane nested splitters
        val innerSplitter = OnePixelSplitter(false, 0.35f).apply {
            firstComponent = JBScrollPane(stepList)
            secondComponent = JBScrollPane(detailPanel).apply {
                border = BorderFactory.createEmptyBorder()
            }
        }
        val outerSplitter = OnePixelSplitter(false, 0.18f).apply {
            firstComponent = JBScrollPane(featureList)
            secondComponent = innerSplitter
        }

        add(outerSplitter, BorderLayout.CENTER)

        // Load features and pre-select
        refreshFeatures()
        stepList.selectedIndex = initialStepIndex.coerceAtMost(steps.size - 1)
    }

    // ── Feature scanning ────────────────────────────────────────────────────

    private fun refreshFeatures() {
        val basePath = project.basePath ?: return
        val specsDir = File(basePath, "specs")
        featureListModel.clear()

        if (specsDir.isDirectory) {
            specsDir.listFiles()
                ?.filter { it.isDirectory && it.name.matches(Regex("^\\d{3}-.*")) }
                ?.sortedBy { it.name }
                ?.forEach { dir ->
                    val entry = FeatureEntry(dir.name, dir.absolutePath)
                    computeFeatureCompletion(entry, basePath)
                    featureListModel.addElement(entry)
                }
        }

        if (featureListModel.size() == 0) {
            // No real features — add a mock feature so the demo isn't empty
            val mockEntry = FeatureEntry("001-sample-feature", "$basePath/specs/001-sample-feature")
            computeFeatureCompletion(mockEntry, basePath)
            featureListModel.addElement(mockEntry)
        }

        featureList.selectedIndex = 0
    }

    private fun computeFeatureCompletion(entry: FeatureEntry, basePath: String) {
        val outputSteps = steps.filter { it.outputs.isNotEmpty() }
        var completed = 0
        for (step in outputSteps) {
            if (step.outputs.all { artifactExists(it, basePath, entry.path) }) completed++
        }
        entry.completedSteps = completed
        entry.totalOutputSteps = outputSteps.size
    }

    // ── Artifact checking (mock-aware) ──────────────────────────────────────
    // Checks the mock set first; if the artifact is in mockExistingArtifacts it
    // is treated as present. Otherwise falls back to the real filesystem so
    // that real artifacts (e.g. after running Init Speckit) also show up.

    private fun artifactExists(artifact: ArtifactCheck, basePath: String, featureDir: String?): Boolean {
        if (isMockPresent(artifact)) return true
        val file = resolveArtifact(artifact.relativePath, artifact.isRepoRelative, basePath, featureDir) ?: return false
        val effective = if (!file.exists() && artifact.altRelativePath != null) {
            resolveArtifact(artifact.altRelativePath, artifact.isRepoRelative, basePath, featureDir) ?: file
        } else file

        return if (artifact.isDirectory) {
            effective.isDirectory && (!artifact.requireNonEmpty || (effective.listFiles()?.isNotEmpty() == true))
        } else {
            effective.isFile
        }
    }

    private fun resolveArtifact(path: String, isRepoRelative: Boolean, basePath: String, featureDir: String?): File? {
        return if (isRepoRelative) File(basePath, path)
        else featureDir?.let { File(it, path) }
    }

    private fun checkArtifact(artifact: ArtifactCheck, basePath: String, featureDir: String?): CheckResult {
        // Mock check first
        if (isMockPresent(artifact)) {
            val detail = if (artifact.isDirectory) "simulated" else "simulated"
            return CheckResult(artifact, true, detail)
        }

        // Fall back to real filesystem
        val file = resolveArtifact(artifact.relativePath, artifact.isRepoRelative, basePath, featureDir)
            ?: return CheckResult(artifact, false, "no feature dir")
        val effective = if (!file.exists() && artifact.altRelativePath != null) {
            resolveArtifact(artifact.altRelativePath, artifact.isRepoRelative, basePath, featureDir) ?: file
        } else file

        if (artifact.isDirectory) {
            if (!effective.isDirectory) return CheckResult(artifact, false, "missing")
            val count = effective.listFiles()?.size ?: 0
            if (artifact.requireNonEmpty && count == 0) return CheckResult(artifact, false, "empty dir")
            return CheckResult(artifact, true, "$count file(s)")
        }
        if (!effective.isFile) return CheckResult(artifact, false, "missing")
        val size = effective.length()
        val detail = if (size < 1024) "${size} B" else String.format("%.1f KB", size / 1024.0)
        return CheckResult(artifact, true, detail)
    }

    /** Returns true if the artifact is in the mock set (repo prereqs + prior step outputs). */
    private fun isMockPresent(artifact: ArtifactCheck): Boolean {
        if (artifact.relativePath in mockExistingArtifacts) return true
        if (artifact.altRelativePath != null && artifact.altRelativePath in mockExistingArtifacts) return true
        return false
    }

    // ── Status derivation ─────────────────────────────────────────────────────

    private fun recomputeStatuses() {
        val basePath = project.basePath ?: return
        val featureDir = selectedFeatureEntry?.path

        for ((i, step) in steps.withIndex()) {
            val prereqs = step.prerequisites.map { checkArtifact(it, basePath, featureDir) }
            val outputs = step.outputs.map { checkArtifact(it, basePath, featureDir) }
            stepPrereqs[step.id] = prereqs
            stepOutputs[step.id] = outputs

            val allPrereqs = prereqs.all { it.exists }
            val allOutputs = outputs.isNotEmpty() && outputs.all { it.exists }
            val someOutputs = outputs.any { it.exists }

            // In mock mode, steps before the current one that have already
            // "run" (all prereqs met) should show COMPLETED, even if they
            // produce no tracked output artifacts (e.g. Clarify, Analyze).
            val mockCompleted = i < initialStepIndex && allPrereqs

            stepStatuses[step.id] = when {
                mockCompleted -> StepStatus.COMPLETED
                step.outputs.isEmpty() && allPrereqs -> StepStatus.READY
                allOutputs -> StepStatus.COMPLETED
                !allPrereqs -> StepStatus.BLOCKED
                someOutputs -> StepStatus.IN_PROGRESS
                allPrereqs -> StepStatus.READY
                else -> StepStatus.NOT_STARTED
            }
        }
    }

    // ── Detail panel ──────────────────────────────────────────────────────────

    private fun updateDetailPanel(step: StepDef) {
        detailPanel.removeAll()
        val status = stepStatuses[step.id] ?: StepStatus.NOT_STARTED

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(10, 14, 10, 14)
        }

        // Header
        content.add(JLabel("Step ${step.number}: ${step.name}").apply {
            font = font.deriveFont(Font.BOLD, font.size + 3f)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        if (step.isOptional) {
            content.add(JLabel("(optional)").apply {
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
        content.add(JLabel(step.description).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(3, 0, 8, 0)
        })

        // Status
        content.add(JLabel("Status: ${statusText(status)}").apply {
            foreground = statusColor(status)
            font = font.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
        })

        // Prerequisites
        val prereqs = stepPrereqs[step.id] ?: emptyList()
        if (prereqs.isNotEmpty()) {
            content.add(sectionHeader("Prerequisites:"))
            prereqs.forEach { content.add(checkResultLabel(it)) }
            content.add(verticalSpacer(6))
        }

        // Outputs
        val outputs = stepOutputs[step.id] ?: emptyList()
        if (outputs.isNotEmpty()) {
            content.add(sectionHeader("Outputs:"))
            outputs.forEach { content.add(checkResultLabel(it)) }
            content.add(verticalSpacer(6))
        }

        // Hands off to
        if (step.handsOffTo.isNotEmpty()) {
            content.add(JLabel("Hands off to \u2192 ${step.handsOffTo.joinToString(", ")}").apply {
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
                border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
            })
        }

        // Arguments
        val argsField = JBTextArea(2, 0).apply {
            lineWrap = true
            wrapStyleWord = true
            margin = java.awt.Insets(4, 6, 4, 6)
            text = step.defaultArgs
        }
        content.add(sectionHeader("Arguments:"))
        val argsScroll = JBScrollPane(argsField).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 60)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 0, 6, 0),
                RoundedLineBorder(JBColor.GRAY, 6)
            )
        }
        content.add(argsScroll)

        // Run button
        content.add(JButton("Run ${step.name} \u25B7").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            addActionListener { runStep(step, argsField.text.trim()) }
        })

        content.add(javax.swing.Box.createVerticalGlue())
        detailPanel.add(content, BorderLayout.CENTER)
        detailPanel.revalidate()
        detailPanel.repaint()
    }

    // ── Run step (real execution) ─────────────────────────────────────────────

    private fun runStep(step: StepDef, arguments: String) {
        val basePath = project.basePath ?: return
        val chatService = project.getService(CopilotChatService::class.java) ?: return
        val agentContent = ResourceLoader.readAgent(basePath, step.agentFileName) ?: return

        val prompt = agentContent.replace("\$ARGUMENTS", arguments)
        val dataContext = SimpleDataContext.getProjectContext(project)

        val run = ChatRun(
            agent = step.id,
            prompt = arguments.ifEmpty { "(no arguments)" },
            branch = sessionPanel.currentGitBranch()
        )
        sessionPanel.registerRun(run)

        chatService.query(dataContext) {
            withInput(prompt)
            withAgentMode()
            withNewSession()
            withSessionIdReceiver { sessionId ->
                invokeLater {
                    run.sessionId = sessionId
                    sessionPanel.notifyRunChanged()
                }
            }
            onComplete {
                invokeLater {
                    run.status = ChatRunStatus.COMPLETED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    sessionPanel.notifyRunChanged()
                    refreshFeatures()
                }
            }
            onError { message, _, _, _, _ ->
                invokeLater {
                    run.status = ChatRunStatus.FAILED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    run.errorMessage = message
                    sessionPanel.notifyRunChanged()
                }
            }
            onCancel {
                invokeLater {
                    run.status = ChatRunStatus.CANCELLED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    sessionPanel.notifyRunChanged()
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sectionHeader(text: String) = JLabel(text).apply {
        font = font.deriveFont(Font.BOLD)
        alignmentX = Component.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(0, 0, 3, 0)
    }

    private fun checkResultLabel(result: CheckResult): JLabel {
        val icon = if (result.exists) "\u2713" else "\u2717"
        val detail = if (result.detail.isNotEmpty()) "  (${result.detail})" else ""
        return JLabel("  $icon  ${result.artifact.label}$detail").apply {
            foreground = if (result.exists)
                JBColor(Color(0, 128, 0), Color(80, 200, 80))
            else
                JBColor(Color(200, 100, 0), Color(255, 160, 60))
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    private fun verticalSpacer(height: Int) = JPanel().apply {
        maximumSize = Dimension(Int.MAX_VALUE, height)
        preferredSize = Dimension(0, height)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    // ── Feature list renderer ─────────────────────────────────────────────────

    private inner class FeatureListRenderer : JPanel(BorderLayout()), ListCellRenderer<FeatureEntry> {
        private val iconLabel = JLabel().apply { horizontalAlignment = SwingConstants.CENTER }
        private val nameLabel = JLabel()

        init {
            isOpaque = true
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            val inner = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false; add(iconLabel); add(nameLabel) }
            add(inner, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out FeatureEntry>, value: FeatureEntry,
            index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val (icon, color) = when {
                value.totalOutputSteps > 0 && value.completedSteps >= value.totalOutputSteps -> "\u2713" to JBColor(Color(0, 128, 0), Color(80, 200, 80))
                value.completedSteps > 0 -> "\u25D0" to JBColor.BLUE
                else -> "\u25CB" to JBColor.GRAY
            }
            iconLabel.text = icon; iconLabel.foreground = color
            nameLabel.text = value.dirName
            nameLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
            background = if (isSelected) list.selectionBackground else list.background
            return this
        }
    }

    // ── Step list renderer ────────────────────────────────────────────────────

    private inner class StepListRenderer : JPanel(BorderLayout()), ListCellRenderer<StepDef> {
        private val iconLabel = JLabel().apply { horizontalAlignment = SwingConstants.CENTER }
        private val nameLabel = JLabel()
        private val connectorPanel = ConnectorPanel()

        init {
            isOpaque = true
            border = BorderFactory.createEmptyBorder(2, 4, 2, 8)
            val leftPanel = JPanel(BorderLayout()).apply { isOpaque = false; preferredSize = Dimension(24, 0); add(connectorPanel, BorderLayout.CENTER) }
            val iconNamePanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false; add(iconLabel); add(nameLabel) }
            add(leftPanel, BorderLayout.WEST)
            add(iconNamePanel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out StepDef>, value: StepDef,
            index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val status = stepStatuses[value.id] ?: StepStatus.NOT_STARTED
            iconLabel.text = statusIcon(status); iconLabel.foreground = statusColor(status)
            nameLabel.text = "${value.number}. ${value.name}"
            nameLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
            connectorPanel.isFirst = index == 0
            connectorPanel.isLast = index == stepListModel.size() - 1
            connectorPanel.color = JBColor.border()
            background = if (isSelected) list.selectionBackground else list.background
            return this
        }
    }

    private class ConnectorPanel : JPanel() {
        var isFirst = false; var isLast = false; var color: Color = JBColor.border()
        init { isOpaque = false; preferredSize = Dimension(24, 0) }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            val cx = width / 2
            g2.drawLine(cx, if (isFirst) height / 2 else 0, cx, if (isLast) height / 2 else height)
            g2.fillOval(cx - 3, height / 2 - 3, 6, 6)
        }
    }

    // ── Status helpers ────────────────────────────────────────────────────────

    private fun statusIcon(s: StepStatus) = when (s) {
        StepStatus.COMPLETED -> "\u2713"; StepStatus.READY -> "\u25CB"
        StepStatus.IN_PROGRESS -> "\u25D0"; StepStatus.BLOCKED -> "\u2717"; StepStatus.NOT_STARTED -> "\u25CB"
    }
    private fun statusText(s: StepStatus) = when (s) {
        StepStatus.COMPLETED -> "Completed"; StepStatus.READY -> "Ready"
        StepStatus.IN_PROGRESS -> "In Progress"; StepStatus.BLOCKED -> "Blocked"; StepStatus.NOT_STARTED -> "Not Started"
    }
    private fun statusColor(s: StepStatus): Color = when (s) {
        StepStatus.COMPLETED -> JBColor(Color(0, 128, 0), Color(80, 200, 80))
        StepStatus.READY, StepStatus.IN_PROGRESS -> JBColor.BLUE
        StepStatus.BLOCKED -> JBColor(Color(200, 100, 0), Color(255, 160, 60))
        StepStatus.NOT_STARTED -> JBColor.GRAY
    }
}
