package com.speckit.plugin.ui.pipeline

import com.intellij.ui.JBColor
import com.speckit.plugin.model.FeatureEntry
import com.speckit.plugin.model.PipelineStepDef
import com.speckit.plugin.model.PipelineStepState
import com.speckit.plugin.model.StepStatus
import com.speckit.plugin.ui.component.ConnectorPanel
import com.speckit.plugin.ui.component.PipelineUiHelpers
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer

// ── Tree node data ──────────────────────────────────────────────────────────

sealed interface PipelineNodeData

data class FeatureNodeData(val entry: FeatureEntry) : PipelineNodeData {
    override fun toString() = entry.dirName
}

data class StepNodeData(val step: PipelineStepDef, val featureEntry: FeatureEntry) : PipelineNodeData {
    override fun toString() = step.name
}

// ── Data provider interface ─────────────────────────────────────────────────

interface PipelineTreeDataProvider {
    fun getStepStates(featureDirName: String): Map<PipelineStepDef, PipelineStepState>?
    fun getCurrentBranch(): String
    fun getPipelineSteps(): List<PipelineStepDef>
}

// ── Renderer ────────────────────────────────────────────────────────────────

class PipelineTreeRenderer(
    private val dataProvider: PipelineTreeDataProvider
) : JPanel(BorderLayout()), TreeCellRenderer {

    private val connectorPanel = ConnectorPanel()
    private val connectorWrapper = JPanel(BorderLayout()).apply {
        isOpaque = false
        preferredSize = Dimension(14, 0)
        add(connectorPanel, BorderLayout.CENTER)
    }
    private val iconLabel = JLabel().apply { horizontalAlignment = SwingConstants.CENTER }
    private val nameLabel = JLabel()

    init {
        isOpaque = true
        val textPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(iconLabel)
            add(javax.swing.Box.createHorizontalStrut(4))
            add(nameLabel)
        }
        add(connectorWrapper, BorderLayout.WEST)
        add(textPanel, BorderLayout.CENTER)
    }

    override fun getTreeCellRendererComponent(
        tree: JTree, value: Any?, selected: Boolean,
        expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
    ): Component {
        val node = value as? DefaultMutableTreeNode
        when (val data = node?.userObject) {
            is FeatureNodeData -> renderFeature(data, tree, selected)
            is StepNodeData -> renderStep(data, node, tree, selected)
            else -> { nameLabel.text = value?.toString() ?: "" }
        }

        background = if (selected) {
            UIManager.getColor("Tree.selectionBackground") ?: tree.background
        } else {
            tree.background
        }
        return this
    }

    private fun renderFeature(data: FeatureNodeData, tree: JTree, selected: Boolean) {
        connectorWrapper.isVisible = false
        border = BorderFactory.createEmptyBorder(2, 0, 2, 4)

        val entry = data.entry
        val icon: String
        val iconColor: Color
        when {
            entry.totalOutputSteps > 0 && entry.completedSteps >= entry.totalOutputSteps -> {
                icon = "\u2713"; iconColor = PipelineUiHelpers.greenColor
            }
            entry.completedSteps > 0 -> {
                icon = "\u25D0"; iconColor = JBColor.BLUE
            }
            else -> {
                icon = "\u25CB"; iconColor = JBColor.GRAY
            }
        }
        iconLabel.text = icon
        iconLabel.foreground = iconColor

        nameLabel.text = entry.dirName
        nameLabel.foreground = if (selected) {
            UIManager.getColor("Tree.selectionForeground") ?: tree.foreground
        } else tree.foreground

        val currentBranch = dataProvider.getCurrentBranch()
        val branchPrefix = Regex("^(\\d{3})-").find(currentBranch)?.groupValues?.get(1)
        val isBranchMatch = branchPrefix != null && entry.dirName.startsWith("$branchPrefix-")
        nameLabel.font = nameLabel.font.deriveFont(if (isBranchMatch) Font.BOLD else Font.PLAIN)
    }

    private fun renderStep(data: StepNodeData, node: DefaultMutableTreeNode, tree: JTree, selected: Boolean) {
        connectorWrapper.isVisible = true
        val isSubStep = data.step.parentId != null
        border = BorderFactory.createEmptyBorder(2, if (isSubStep) 12 else 0, 2, 4)

        val states = dataProvider.getStepStates(data.featureEntry.dirName)
        val status = states?.get(data.step)?.status ?: StepStatus.NOT_STARTED

        iconLabel.text = PipelineUiHelpers.statusIcon(status)
        iconLabel.foreground = PipelineUiHelpers.statusColor(status)
        nameLabel.text = "${displayNumber(data.step)}. ${data.step.name}"
        nameLabel.foreground = if (selected) {
            UIManager.getColor("Tree.selectionForeground") ?: tree.foreground
        } else tree.foreground
        nameLabel.font = nameLabel.font.deriveFont(Font.PLAIN)

        val parent = node.parent as? DefaultMutableTreeNode
        val siblingIndex = parent?.getIndex(node) ?: 0
        val siblingCount = parent?.childCount ?: 0
        connectorPanel.isFirst = siblingIndex == 0
        connectorPanel.isLast = siblingIndex == siblingCount - 1
        connectorPanel.isSubStep = isSubStep
        connectorPanel.color = JBColor.border()
    }

    private fun displayNumber(step: PipelineStepDef): String {
        if (step.parentId == null) return step.number.toString()
        val steps = dataProvider.getPipelineSteps()
        val parent = steps.firstOrNull { it.id == step.parentId }
            ?: return step.number.toString()
        val siblingIndex = steps.filter { it.parentId == step.parentId }
            .indexOf(step) + 1
        return "${parent.number}.$siblingIndex"
    }
}
