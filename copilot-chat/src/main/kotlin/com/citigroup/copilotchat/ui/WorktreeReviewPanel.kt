package com.citigroup.copilotchat.ui

import com.citigroup.copilotchat.agent.AgentService
import com.citigroup.copilotchat.agent.WorktreeFileChange
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Inline review panel shown in the Agent chat when a worktree-isolated subagent
 * finishes with file changes. Allows the user to inspect diffs and approve or
 * discard the changes before they touch the main workspace.
 */
class WorktreeReviewPanel(
    private val agentId: String,
    private val changes: List<WorktreeFileChange>,
    private val project: Project,
) : JPanel(BorderLayout(0, 4)) {

    private val listModel = DefaultListModel<WorktreeFileChange>()
    private val fileList = JBList(listModel)
    private val statusLabel = JBLabel()

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 1, 0, JBColor.border()),
            BorderFactory.createEmptyBorder(8, 12, 8, 12),
        )

        // Header
        val header = JBLabel("Worktree changes (${changes.size} file${if (changes.size != 1) "s" else ""})")
        header.font = header.font.deriveFont(Font.BOLD)
        add(header, BorderLayout.NORTH)

        // File list
        for (change in changes) listModel.addElement(change)
        fileList.cellRenderer = ChangeCellRenderer()
        fileList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fileList.visibleRowCount = changes.size.coerceAtMost(6)
        fileList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val index = fileList.locationToIndex(e.point)
                    if (index >= 0) showDiff(listModel.getElementAt(index))
                }
            }
        })
        add(JBScrollPane(fileList), BorderLayout.CENTER)

        // Buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))

        val applyBtn = JButton("Apply Changes")
        applyBtn.toolTipText = "Copy changes to your main workspace"
        applyBtn.addActionListener { applyChanges(applyBtn) }

        val discardBtn = JButton("Discard")
        discardBtn.toolTipText = "Delete the worktree and discard all changes"
        discardBtn.addActionListener { discardChanges(discardBtn) }

        buttonPanel.add(applyBtn)
        buttonPanel.add(discardBtn)
        buttonPanel.add(statusLabel)
        add(buttonPanel, BorderLayout.SOUTH)
    }

    private fun showDiff(change: WorktreeFileChange) {
        val dcf = DiffContentFactory.getInstance()
        val original = dcf.create(change.originalContent ?: "")
        val modified = dcf.create(change.newContent)
        val request = SimpleDiffRequest(
            "Worktree: ${change.relativePath}",
            original, modified,
            "Original", "Worktree"
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun applyChanges(button: JButton) {
        button.isEnabled = false
        try {
            AgentService.getInstance(project).approveWorktreeChanges(agentId)
            statusLabel.text = "Applied"
            statusLabel.foreground = JBColor(Color(0, 128, 0), Color(80, 200, 80))
            disableAll()
        } catch (e: Exception) {
            statusLabel.text = "Error: ${e.message}"
            statusLabel.foreground = JBColor.RED
            button.isEnabled = true
        }
    }

    private fun discardChanges(button: JButton) {
        val confirm = JOptionPane.showConfirmDialog(
            this,
            "Discard ${changes.size} changed file(s) from worktree?",
            "Discard Worktree Changes",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
        if (confirm != JOptionPane.OK_OPTION) return

        button.isEnabled = false
        try {
            AgentService.getInstance(project).rejectWorktreeChanges(agentId)
            statusLabel.text = "Discarded"
            statusLabel.foreground = JBColor.GRAY
            disableAll()
        } catch (e: Exception) {
            statusLabel.text = "Error: ${e.message}"
            statusLabel.foreground = JBColor.RED
            button.isEnabled = true
        }
    }

    private fun disableAll() {
        for (c in (getComponent(2) as JPanel).components) {
            if (c is JButton) c.isEnabled = false
        }
    }

    private class ChangeCellRenderer : ListCellRenderer<WorktreeFileChange> {
        private val panel = JPanel(BorderLayout(8, 0))
        private val badgeLabel = JLabel()
        private val pathLabel = JLabel()

        init {
            panel.border = BorderFactory.createEmptyBorder(3, 6, 3, 6)
            badgeLabel.font = badgeLabel.font.deriveFont(Font.BOLD)
            badgeLabel.preferredSize = Dimension(20, 20)
            badgeLabel.horizontalAlignment = SwingConstants.CENTER
            panel.add(badgeLabel, BorderLayout.WEST)
            panel.add(pathLabel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out WorktreeFileChange>,
            value: WorktreeFileChange,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            badgeLabel.text = if (value.isNew) "A" else "M"
            badgeLabel.foreground = if (value.isNew) {
                JBColor(Color(0, 128, 0), Color(80, 200, 80))
            } else {
                JBColor(Color(0, 0, 180), Color(100, 150, 255))
            }
            pathLabel.text = value.relativePath

            panel.background = if (isSelected) list.selectionBackground else list.background
            pathLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
            return panel
        }
    }
}
