package com.citigroup.copilotchat.workingset

import com.citigroup.copilotchat.config.CopilotChatSettings
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class WorkingSetPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(WorkingSetPanel::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val service = WorkingSetService.getInstance(project)
    private val listModel = DefaultListModel<FileChange>()
    private val fileList = JBList(listModel)
    private val countLabel = JBLabel("No changes")

    init {
        buildUi()
        collectEvents()
    }

    private fun buildUi() {
        // Toolbar
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        toolbar.border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())

        val commitBtn = JButton("Commit")
        commitBtn.toolTipText = "Commit Copilot changes as a dedicated Copilot-authored commit"
        commitBtn.addActionListener { commitCopilotChanges() }

        val acceptAllBtn = JButton("Accept All")
        acceptAllBtn.toolTipText = "Accept all changes (files remain on disk, no commit)"
        acceptAllBtn.addActionListener { service.acceptAll() }

        val revertAllBtn = JButton("Revert All")
        revertAllBtn.toolTipText = "Revert all files to their original content"
        revertAllBtn.addActionListener {
            val count = listModel.size()
            if (count == 0) return@addActionListener
            val confirm = JOptionPane.showConfirmDialog(
                this,
                "Revert all $count changed file(s)? This will restore original content on disk.",
                "Revert All Changes",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (confirm == JOptionPane.OK_OPTION) {
                service.revertAll()
            }
        }

        toolbar.add(commitBtn)
        toolbar.add(acceptAllBtn)
        toolbar.add(revertAllBtn)
        toolbar.add(Box.createHorizontalStrut(12))
        toolbar.add(countLabel)

        add(toolbar, BorderLayout.NORTH)

        // File list
        fileList.cellRenderer = FileChangeCellRenderer()
        fileList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fileList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = fileList.locationToIndex(e.point)
                if (index < 0) return
                val change = listModel.getElementAt(index)

                if (e.clickCount == 2) {
                    showDiff(change)
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    showContextMenu(change, e)
                } else {
                    val cellBounds = fileList.getCellBounds(index, index)
                    if (cellBounds != null && e.x > cellBounds.width - 60) {
                        revertSingle(change)
                    }
                }
            }
        })

        add(JBScrollPane(fileList), BorderLayout.CENTER)

        fileList.emptyText.setText("No file changes tracked yet")

        // Refresh the list whenever this panel becomes visible (e.g. user clicks "Changes" tab)
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) {
                refreshList()
            }
        }
    }

    private fun commitCopilotChanges() {
        val changes = service.getChanges()
        if (changes.isEmpty()) return

        val filesSummary = changes.joinToString("\n") { "  ${if (it.isNew) "A" else "M"}  ${it.relativePath}" }
        val defaultMsg = if (changes.size == 1) {
            "${if (changes[0].isNew) "Add" else "Update"} ${changes[0].relativePath}"
        } else {
            "Copilot: update ${changes.size} files"
        }

        val msgPanel = JPanel(BorderLayout(0, 8))
        msgPanel.add(JLabel("<html>Commit ${changes.size} file(s) as <b>GitHub Copilot</b>:<br><pre>$filesSummary</pre></html>"), BorderLayout.NORTH)
        val msgField = JTextField(defaultMsg, 40)
        val msgRow = JPanel(BorderLayout(4, 0))
        msgRow.add(JLabel("Message:"), BorderLayout.WEST)
        msgRow.add(msgField, BorderLayout.CENTER)
        msgPanel.add(msgRow, BorderLayout.CENTER)

        val result = JOptionPane.showConfirmDialog(
            this, msgPanel, "Commit Copilot Changes",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (result != JOptionPane.OK_OPTION) return

        val commitMsg = msgField.text.trim().ifEmpty { defaultMsg }
        scope.launch(Dispatchers.IO) {
            try {
                val ok = runGitCommit(changes, commitMsg)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        service.acceptAll()
                        JOptionPane.showMessageDialog(
                            this@WorkingSetPanel,
                            "Committed ${changes.size} file(s) as GitHub Copilot.",
                            "Commit Successful",
                            JOptionPane.INFORMATION_MESSAGE
                        )
                    }
                }
            } catch (e: Exception) {
                log.error("Copilot commit failed", e)
                withContext(Dispatchers.Main) {
                    JOptionPane.showMessageDialog(
                        this@WorkingSetPanel,
                        "Commit failed: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private fun runGitCommit(changes: List<FileChange>, message: String): Boolean {
        val workDir = project.basePath ?: return false
        val model = CopilotChatSettings.getInstance().defaultModel.ifBlank { "unknown" }
        val author = "GitHub Copilot ($model) <noreply@github.com>"

        // Collect unique tool names used across all changes
        val tools = changes.map { it.toolName }.distinct().sorted().joinToString(", ")

        // Build commit message with trailers
        val fullMessage = buildString {
            append(message)
            append("\n\n")
            append("Generated-by: github-copilot\n")
            append("Model: $model\n")
            append("Tools: $tools")
        }

        // Stage only the Copilot-changed files
        val addCmd = mutableListOf("git", "add", "--")
        addCmd.addAll(changes.map { it.absolutePath })

        val addProcess = ProcessBuilder(addCmd)
            .directory(java.io.File(workDir))
            .redirectErrorStream(true)
            .start()
        val addExit = addProcess.waitFor()
        if (addExit != 0) {
            val err = addProcess.inputStream.bufferedReader().readText()
            throw RuntimeException("git add failed (exit $addExit): $err")
        }

        // Commit with Copilot as the author and trailers
        val commitProcess = ProcessBuilder(
            "git", "commit",
            "--author", author,
            "-m", fullMessage
        )
            .directory(java.io.File(workDir))
            .redirectErrorStream(true)
            .start()
        val commitExit = commitProcess.waitFor()
        val commitOutput = commitProcess.inputStream.bufferedReader().readText()
        if (commitExit != 0) {
            throw RuntimeException("git commit failed (exit $commitExit): $commitOutput")
        }

        log.info("Copilot commit created: $commitOutput")

        // Refresh VFS so IntelliJ sees the new commit state
        LocalFileSystem.getInstance().refresh(true)
        return true
    }

    private fun collectEvents() {
        scope.launch {
            service.events.collect { event ->
                SwingUtilities.invokeLater {
                    when (event) {
                        is WorkingSetEvent.FileChanged -> refreshList()
                        is WorkingSetEvent.FileReverted -> refreshList()
                        is WorkingSetEvent.Cleared -> refreshList()
                    }
                }
            }
        }
    }

    private fun refreshList() {
        listModel.clear()
        val changes = service.getChanges()
        for (change in changes) {
            listModel.addElement(change)
        }
        val count = changes.size
        countLabel.text = if (count == 0) "No changes" else "$count file(s) changed"
        revalidate()
        repaint()
    }

    private fun showDiff(change: FileChange) {
        val dcf = DiffContentFactory.getInstance()
        val original = dcf.create(change.originalContent ?: "")
        val current = dcf.create(change.currentContent)
        val request = SimpleDiffRequest(
            "Changes: ${change.relativePath}",
            original, current,
            "Original", "Modified"
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun showContextMenu(change: FileChange, e: MouseEvent) {
        val menu = JPopupMenu()

        val diffItem = JMenuItem("Show Diff")
        diffItem.addActionListener { showDiff(change) }
        menu.add(diffItem)

        val openItem = JMenuItem("Open in Editor")
        openItem.addActionListener {
            val vf = LocalFileSystem.getInstance().findFileByPath(change.absolutePath)
            if (vf != null) {
                FileEditorManager.getInstance(project).openFile(vf, true)
            }
        }
        menu.add(openItem)

        menu.addSeparator()

        val revertItem = JMenuItem("Revert File")
        revertItem.addActionListener { revertSingle(change) }
        menu.add(revertItem)

        menu.show(fileList, e.x, e.y)
    }

    private fun revertSingle(change: FileChange) {
        val confirm = JOptionPane.showConfirmDialog(
            this,
            "Revert ${change.relativePath}?${if (change.isNew) " The file will be deleted." else ""}",
            "Revert File",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (confirm == JOptionPane.OK_OPTION) {
            service.revert(change.absolutePath)
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    private class FileChangeCellRenderer : ListCellRenderer<FileChange> {
        private val panel = JPanel(BorderLayout(8, 0))
        private val badgeLabel = JLabel()
        private val pathLabel = JLabel()
        private val revertLabel = JLabel("Revert")

        init {
            panel.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            badgeLabel.font = badgeLabel.font.deriveFont(Font.BOLD)
            badgeLabel.preferredSize = Dimension(20, 20)
            badgeLabel.horizontalAlignment = SwingConstants.CENTER
            revertLabel.foreground = JBColor.BLUE
            revertLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            revertLabel.font = revertLabel.font.deriveFont(Font.PLAIN, 11f)

            panel.add(badgeLabel, BorderLayout.WEST)
            panel.add(pathLabel, BorderLayout.CENTER)
            panel.add(revertLabel, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out FileChange>,
            value: FileChange,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            badgeLabel.text = if (value.isNew) "A" else "M"
            badgeLabel.foreground = if (value.isNew) {
                JBColor(Color(0, 128, 0), Color(80, 200, 80))
            } else {
                JBColor(Color(0, 80, 180), Color(100, 160, 255))
            }
            pathLabel.text = value.relativePath

            if (isSelected) {
                panel.background = list.selectionBackground
                pathLabel.foreground = list.selectionForeground
            } else {
                panel.background = list.background
                pathLabel.foreground = list.foreground
            }

            return panel
        }
    }
}
