package com.speckit.plugin.ui.pipeline

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.speckit.plugin.model.ChatRun
import com.speckit.plugin.model.ChatRunStatus
import com.speckit.plugin.model.FeatureEntry
import com.speckit.plugin.model.FeaturePaths
import com.speckit.plugin.model.PipelineStepDef
import com.speckit.plugin.model.PipelineStepState
import com.speckit.plugin.model.StepStatus
import com.speckit.plugin.service.ChatRunLauncher
import com.speckit.plugin.service.PipelineService
import com.speckit.plugin.persistence.SessionPersistenceManager
import com.speckit.plugin.ui.SessionPanel
import com.speckit.plugin.ui.TaskListPanel
import com.speckit.plugin.ui.component.PipelineUiHelpers
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Renders the right-side detail view for a selected pipeline step or feature.
 *
 * Extracted from [com.speckit.plugin.ui.PipelinePanel] to keep each file
 * under ~400 lines for AI agent comprehension.
 */
class StepDetailPanel(
    private val project: Project,
    private val chatPanel: SessionPanel,
    private val persistenceManager: SessionPersistenceManager?,
    private val launcher: ChatRunLauncher?
) : JPanel(BorderLayout()) {

    // ── Callbacks wired by PipelinePanel ──────────────────────────────────

    var onRunStep: ((PipelineStepDef, String) -> Unit)? = null
    var onReplyToSession: ((String, String) -> Unit)? = null
    var displayNumber: ((PipelineStepDef) -> String) = { it.number.toString() }
    var loadArgs: ((String) -> String?) = { null }

    // ── Public API ───────────────────────────────────────────────────────────

    fun showStep(
        step: PipelineStepDef,
        state: PipelineStepState?,
        paths: FeaturePaths?,
        lastImplementRun: ChatRun?
    ) {
        removeAll()
        if (state == null) { revalidate(); repaint(); return }

        val content = contentPanel()

        // Header
        content.add(JLabel("Step ${displayNumber(step)}: ${step.name}").apply {
            font = font.deriveFont(Font.BOLD, font.size + 4f)
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
            border = BorderFactory.createEmptyBorder(4, 0, 12, 0)
        })

        // Status
        content.add(JLabel("Status: ${PipelineUiHelpers.statusText(state.status)}").apply {
            foreground = PipelineUiHelpers.statusColor(state.status)
            font = font.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
        })

        // Clarification markers (clarify step only)
        if (step.id == "clarify" && paths != null) {
            addClarificationMarkers(content, paths)
        }

        // Prerequisites
        if (step.prerequisites.isNotEmpty()) {
            content.add(PipelineUiHelpers.sectionHeader("Prerequisites:"))
            for (result in state.prerequisiteResults) {
                content.add(PipelineUiHelpers.checkResultLabel(result, project))
            }
            content.add(PipelineUiHelpers.verticalSpacer(8))
        }

        // Outputs
        if (step.outputs.isNotEmpty()) {
            content.add(PipelineUiHelpers.sectionHeader("Outputs:"))
            for (result in state.outputResults) {
                content.add(PipelineUiHelpers.checkResultLabel(result, project))

                // For the checklist step, list individual files under the directory output
                if (step.id == "checklist" && result.artifact.isDirectory && result.exists && result.resolvedFile != null) {
                    addChecklistFiles(content, result.resolvedFile)
                }
            }
            content.add(PipelineUiHelpers.verticalSpacer(8))
        }

        // Checklists (implement step only)
        if (step.id == "implement" && paths != null) {
            addChecklists(content, paths)
        }

        // Task list (tasks, implement, taskstoissues steps)
        if (step.id in setOf("tasks", "implement", "taskstoissues") && paths != null) {
            val taskListPanel = TaskListPanel(
                project, chatPanel, persistenceManager,
                enableActions = step.id == "implement",
                launcher = launcher
            )
            taskListPanel.update(paths.featureDir)
            taskListPanel.alignmentX = Component.LEFT_ALIGNMENT
            content.add(taskListPanel)
            content.add(PipelineUiHelpers.verticalSpacer(8))
        }

        // "Yes, proceed" reply button (implement step, running with session)
        val implRun = lastImplementRun
        if (step.id == "implement" && implRun != null
            && implRun.status == ChatRunStatus.RUNNING && implRun.sessionId != null) {

            val replyButton = JButton("Yes, proceed with implementation").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener { onReplyToSession?.invoke(implRun.sessionId!!, "yes, proceed with implementation") }
            }
            content.add(replyButton)
            content.add(PipelineUiHelpers.verticalSpacer(8))
        }

        // Hands off to
        if (step.handsOffTo.isNotEmpty()) {
            content.add(JLabel("Hands off to \u2192 ${step.handsOffTo.joinToString(", ")}").apply {
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
                border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
            })
        }

        // Arguments
        val isReadOnly = step.id == "specify"
        val argsField = JBTextArea(3, 0).apply {
            lineWrap = true
            wrapStyleWord = true
            margin = java.awt.Insets(6, 8, 6, 8)
            text = loadArgs(step.id) ?: step.defaultArgs
            isEditable = !isReadOnly
            if (isReadOnly) background = JBColor(Color(245, 245, 245), Color(60, 63, 65))
        }
        val argsHeader = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 24)
            add(JLabel("Arguments:").apply {
                font = font.deriveFont(Font.BOLD)
            }, BorderLayout.WEST)
            if (!isReadOnly) {
                add(JButton("Default").apply {
                    isBorderPainted = false
                    isContentAreaFilled = false
                    foreground = JBColor.BLUE
                    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                    font = font.deriveFont(font.size - 1f)
                    toolTipText = "Reset to default"
                    addActionListener { argsField.text = step.defaultArgs }
                }, BorderLayout.EAST)
            }
            border = BorderFactory.createEmptyBorder(0, 0, 4, 0)
        }
        content.add(argsHeader)
        val argsScroll = JBScrollPane(argsField).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 80)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 0, 8, 0),
                RoundedLineBorder(JBColor.GRAY, 6)
            )
        }
        content.add(argsScroll)

        // Run button (hidden for Specify — use "Add New Specification" instead)
        if (!isReadOnly) {
            val runButton = JButton("Run ${step.name} \u25B7").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener { onRunStep?.invoke(step, argsField.text.trim()) }
            }
            content.add(runButton)
        }

        content.add(javax.swing.Box.createVerticalGlue())
        add(content, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    fun showFeature(
        entry: FeatureEntry,
        states: Map<PipelineStepDef, PipelineStepState>?,
        steps: List<PipelineStepDef>
    ) {
        removeAll()
        val content = contentPanel()

        content.add(JLabel(entry.dirName).apply {
            font = font.deriveFont(Font.BOLD, font.size + 4f)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        content.add(JLabel("${entry.completedSteps}/${entry.totalOutputSteps} output steps completed").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(4, 0, 12, 0)
        })

        if (states != null) {
            content.add(PipelineUiHelpers.sectionHeader("Steps:"))
            for (step in steps) {
                val status = states[step]?.status ?: StepStatus.NOT_STARTED
                content.add(JLabel("  ${PipelineUiHelpers.statusIcon(status)}  ${displayNumber(step)}. ${step.name} \u2014 ${PipelineUiHelpers.statusText(status)}").apply {
                    foreground = PipelineUiHelpers.statusColor(status)
                    alignmentX = Component.LEFT_ALIGNMENT
                })
            }
        }

        content.add(javax.swing.Box.createVerticalGlue())
        add(content, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    fun clear() {
        removeAll()
        revalidate()
        repaint()
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun contentPanel() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
    }

    private fun addClarificationMarkers(content: JPanel, paths: FeaturePaths) {
        val specFile = PipelineService.resolveArtifactFile("spec.md", false, paths.basePath, paths.featureDir)
        if (specFile != null && specFile.isFile) {
            val markerCount = Regex("\\[NEEDS CLARIFICATION").findAll(specFile.readText()).count()
            val markerLabel = if (markerCount == 0) {
                JLabel("No clarification markers in spec.md").apply {
                    foreground = PipelineUiHelpers.greenColor
                }
            } else {
                JLabel("$markerCount [NEEDS CLARIFICATION] marker(s) in spec.md").apply {
                    foreground = PipelineUiHelpers.orangeColor
                    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                    addMouseListener(object : java.awt.event.MouseAdapter() {
                        override fun mouseClicked(e: java.awt.event.MouseEvent) {
                            PipelineUiHelpers.openFileInEditor(project, specFile)
                        }
                    })
                    PipelineUiHelpers.addHoverEffect(this)
                }
            }
            markerLabel.alignmentX = Component.LEFT_ALIGNMENT
            markerLabel.border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
            content.add(markerLabel)
        }
    }

    private fun addChecklistFiles(content: JPanel, directory: File) {
        val files = directory.listFiles()
            ?.filter { it.isFile && it.extension == "md" }
            ?.sortedBy { it.name }
            ?: emptyList()
        for (file in files) {
            val fileLabel = JLabel("      ${file.name}").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                foreground = PipelineUiHelpers.greenColor
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        PipelineUiHelpers.openFileInEditor(project, file)
                    }
                })
                PipelineUiHelpers.addHoverEffect(this)
            }
            content.add(fileLabel)
        }
    }

    private fun addChecklists(content: JPanel, paths: FeaturePaths) {
        val checklists = PipelineService.parseChecklists(paths.featureDir ?: "")
        if (checklists.isEmpty()) return

        val totalItems = checklists.sumOf { it.total }
        val completedItems = checklists.sumOf { it.completed }
        content.add(PipelineUiHelpers.sectionHeader("Checklists:  $completedItems/$totalItems complete"))

        for (cl in checklists) {
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }
            val cb = javax.swing.JCheckBox().apply {
                isSelected = cl.isComplete
                isEnabled = false
            }
            row.add(cb)
            val label = JLabel("${cl.fileName}  (${cl.completed}/${cl.total})").apply {
                foreground = if (cl.isComplete) PipelineUiHelpers.greenColor else PipelineUiHelpers.orangeColor
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        PipelineUiHelpers.openFileInEditor(project, cl.file)
                    }
                })
                PipelineUiHelpers.addHoverEffect(this)
            }
            row.add(label)
            content.add(row)
        }
        content.add(PipelineUiHelpers.verticalSpacer(8))
    }
}
