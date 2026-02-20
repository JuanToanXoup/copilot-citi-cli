package com.citigroup.copilotchat.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.*

/**
 * Port of the official Copilot plugin's StepPanelComponent.
 *
 * Extends CollapsiblePanel to show a list of tool call steps (agent actions).
 * The header shows a summary like "3 steps completed" with a status icon.
 * Expanding reveals the individual steps with their statuses.
 *
 * Step statuses: running, completed, failed, cancelled
 */
class StepPanelComponent : JPanel() {

    data class Step(
        val id: String,
        val title: String,
        var status: String = "running",
    )

    private val steps = mutableListOf<Step>()
    private val stepsListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    private val headerLabel = JLabel()
    private val headerIcon = JLabel()

    private val collapsible: CollapsiblePanel

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 0)

        // Create header trigger
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(headerIcon)
            add(headerLabel)
        }

        collapsible = CollapsiblePanel(
            trigger = headerPanel,
            contentPanel = stepsListPanel,
            initiallyExpanded = false,
        )

        add(collapsible)

        // Initially hidden until steps are added
        isVisible = false
        updateHeader()
    }

    fun addStep(toolName: String, status: String = "running") {
        val step = Step(
            id = toolName,
            title = toolName,
            status = status,
        )
        steps.add(step)
        isVisible = true
        rebuildStepsList()
        updateHeader()
    }

    fun updateStep(toolName: String, status: String) {
        val step = steps.find { it.id == toolName } ?: return
        step.status = status
        rebuildStepsList()
        updateHeader()
    }

    fun updateSteps(newSteps: List<Step>) {
        steps.clear()
        steps.addAll(newSteps)
        isVisible = steps.isNotEmpty()
        rebuildStepsList()
        updateHeader()
    }

    private fun updateHeader() {
        val completedCount = steps.count { it.status == "completed" }
        val runningCount = steps.count { it.status == "running" }
        val failedCount = steps.count { it.status == "failed" }
        val total = steps.size

        if (total == 0) {
            headerLabel.text = ""
            headerIcon.icon = null
            return
        }

        val color = JBColor(0x6A737D, 0x8B949E)
        headerLabel.foreground = color

        when {
            runningCount > 0 -> {
                headerIcon.icon = AllIcons.Process.Step_1
                val stepWord = if (total == 1) "step" else "steps"
                headerLabel.text = "$total $stepWord ($runningCount running...)"
            }
            failedCount > 0 -> {
                headerIcon.icon = AllIcons.General.Warning
                val stepWord = if (total == 1) "step" else "steps"
                headerLabel.text = "$total $stepWord ($failedCount failed)"
            }
            else -> {
                headerIcon.icon = AllIcons.Actions.Checked
                val stepWord = if (completedCount == 1) "step" else "steps"
                headerLabel.text = "$completedCount $stepWord completed"
            }
        }
    }

    private fun rebuildStepsList() {
        stepsListPanel.removeAll()

        // Sort: running first, then completed, then failed/cancelled
        val sorted = steps.sortedWith(
            compareBy<Step> {
                when (it.status) {
                    "running" -> 0
                    "completed" -> 1
                    "failed" -> 2
                    "cancelled" -> 3
                    else -> 4
                }
            }
        )

        for (step in sorted) {
            val icon = when (step.status) {
                "running" -> AllIcons.Process.Step_1
                "completed" -> AllIcons.Actions.Checked
                "failed" -> AllIcons.General.Error
                "cancelled" -> AllIcons.Actions.Cancel
                else -> AllIcons.General.Information
            }

            val textColor = when (step.status) {
                "failed" -> JBColor.RED
                "cancelled" -> JBColor(0x999999, 0x666666)
                else -> JBColor.foreground()
            }

            val stepRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 1)).apply {
                isOpaque = false
                add(JLabel(icon))
                add(JLabel(step.title).apply {
                    foreground = textColor
                })
            }
            stepsListPanel.add(stepRow)
        }

        stepsListPanel.revalidate()
        stepsListPanel.repaint()
    }
}
