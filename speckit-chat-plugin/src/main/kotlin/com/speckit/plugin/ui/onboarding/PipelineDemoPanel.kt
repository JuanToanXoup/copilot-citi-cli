package com.speckit.plugin.ui.onboarding

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.speckit.plugin.model.CheckResult
import com.speckit.plugin.model.FeatureEntry
import com.speckit.plugin.model.PipelineStepDef
import com.speckit.plugin.model.StepStatus
import com.speckit.plugin.tools.ResourceLoader
import com.speckit.plugin.persistence.SessionPersistenceManager
import com.speckit.plugin.service.ChatRunLauncher
import com.speckit.plugin.service.PipelineService
import com.speckit.plugin.service.PipelineStepRegistry
import com.speckit.plugin.ui.component.ConnectorPanel
import com.speckit.plugin.ui.component.PipelineUiHelpers
import com.speckit.plugin.ui.SessionPanel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
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
    private val initialStepIndex: Int = 0,
    private val persistenceManager: SessionPersistenceManager? = null,
    private val launcher: ChatRunLauncher? = null
) : JPanel(BorderLayout()) {

    // ── Pipeline step definitions (see service/PipelineService.kt) ──────────

    private val steps = PipelineStepRegistry.steps

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

    private val stepListModel = DefaultListModel<PipelineStepDef>().apply {
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
        featureListModel.clear()

        val features = PipelineService.scanFeatures(basePath, steps, mockExistingArtifacts).toMutableList()

        if (features.isEmpty()) {
            // No real features — add a mock feature so the demo isn't empty
            val mockEntry = FeatureEntry("001-sample-feature", "$basePath/specs/001-sample-feature")
            PipelineService.computeFeatureStatus(mockEntry, basePath, steps, mockExistingArtifacts)
            features.add(mockEntry)
        }

        features.forEach { featureListModel.addElement(it) }
        featureList.selectedIndex = 0
    }

    // ── Status derivation ─────────────────────────────────────────────────────

    private fun recomputeStatuses() {
        val basePath = project.basePath ?: return
        val featureDir = selectedFeatureEntry?.path

        for ((i, step) in steps.withIndex()) {
            val prereqs = step.prerequisites.map { PipelineService.checkArtifact(it, basePath, featureDir, mockExistingArtifacts) }
            val outputs = step.outputs.map { PipelineService.checkArtifact(it, basePath, featureDir, mockExistingArtifacts) }
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

    private fun updateDetailPanel(step: PipelineStepDef) {
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

    private fun runStep(step: PipelineStepDef, arguments: String) {
        val basePath = project.basePath ?: return
        val agentContent = ResourceLoader.readAgent(basePath, step.agentFileName) ?: return
        val prompt = agentContent.replace("\$ARGUMENTS", arguments)

        launcher?.launch(
            prompt = prompt,
            agent = step.id,
            promptSummary = arguments.ifEmpty { "(no arguments)" },
            onDone = { refreshFeatures() }
        )
    }

    // ── Helpers (delegated to PipelineUiHelpers) ───────────────────────────

    private fun sectionHeader(text: String) = PipelineUiHelpers.sectionHeader(text)
    private fun checkResultLabel(result: CheckResult) = PipelineUiHelpers.checkResultLabel(result, project)
    private fun verticalSpacer(height: Int) = PipelineUiHelpers.verticalSpacer(height)

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

    private inner class StepListRenderer : JPanel(BorderLayout()), ListCellRenderer<PipelineStepDef> {
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
            list: javax.swing.JList<out PipelineStepDef>, value: PipelineStepDef,
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

    // ── Status helpers (delegated to PipelineUiHelpers) ─────────────────────

    private fun statusIcon(s: StepStatus) = PipelineUiHelpers.statusIcon(s)
    private fun statusText(s: StepStatus) = PipelineUiHelpers.statusText(s)
    private fun statusColor(s: StepStatus) = PipelineUiHelpers.statusColor(s)
}
