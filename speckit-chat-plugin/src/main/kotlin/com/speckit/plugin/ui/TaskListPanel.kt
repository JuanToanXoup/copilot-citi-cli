package com.speckit.plugin.ui

import com.github.copilot.api.CopilotChatService
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.speckit.plugin.persistence.SessionPersistenceManager
import com.speckit.plugin.tools.TaskItem
import com.speckit.plugin.tools.TaskPhase
import com.speckit.plugin.tools.TasksFile
import com.speckit.plugin.tools.TasksParser
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListSelectionModel

import com.intellij.ui.components.JBList
import javax.swing.DefaultListModel
import javax.swing.ListCellRenderer

class TaskListPanel(
    private val project: Project,
    private val chatPanel: SessionPanel,
    private val persistenceManager: SessionPersistenceManager?,
    private val enableActions: Boolean = false
) : JPanel(BorderLayout()) {

    private var tasksFile: TasksFile? = null

    fun update(featureDir: String?) {
        removeAll()
        isOpaque = false

        if (featureDir == null) return
        val file = File(featureDir, "tasks.md")
        val parsed = TasksParser.parse(file) ?: return
        tasksFile = parsed

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // Summary header
        content.add(JLabel("Tasks:  ${parsed.completedTasks}/${parsed.totalTasks} complete").apply {
            font = font.deriveFont(Font.BOLD)
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 0, 4, 0)
        })

        val greenColor = JBColor(Color(0, 128, 0), Color(80, 200, 80))
        val orangeColor = JBColor(Color(200, 100, 0), Color(255, 160, 60))
        val grayColor = JBColor.GRAY
        val hoverColor = JBColor(Color(0, 100, 200), Color(100, 180, 255))

        for (phase in parsed.phases) {
            val phaseCompleted = phase.tasks.count { it.checked }
            val phaseTotal = phase.tasks.size

            // Phase header
            val phaseColor = when {
                phaseTotal == 0 -> grayColor
                phaseCompleted >= phaseTotal -> greenColor
                phaseCompleted > 0 -> orangeColor
                else -> grayColor
            }
            val mvpTag = if (phase.isMvp) " MVP" else ""
            val priorityTag = if (phase.priority != null) " (${phase.priority})" else ""
            content.add(JLabel("Phase ${phase.number}: ${phase.name}$priorityTag$mvpTag  ($phaseCompleted/$phaseTotal)").apply {
                font = font.deriveFont(Font.BOLD)
                foreground = phaseColor
                alignmentX = Component.LEFT_ALIGNMENT
                border = BorderFactory.createEmptyBorder(6, 0, 2, 0)
            })

            // Task list with multi-select
            val listModel = DefaultListModel<TaskItem>()
            phase.tasks.forEach { listModel.addElement(it) }

            val taskList = JBList(listModel).apply {
                selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
                cellRenderer = TaskCellRenderer(parsed.file, greenColor, grayColor, hoverColor)
                isOpaque = false
                visibleRowCount = phase.tasks.size
            }

            // Right-click context menu
            if (enableActions) {
                taskList.addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) { maybeShowPopup(e, taskList) }
                    override fun mouseReleased(e: MouseEvent) { maybeShowPopup(e, taskList) }
                })
            }

            taskList.alignmentX = Component.LEFT_ALIGNMENT
            // Constrain height to fit content without extra whitespace
            val cellHeight = 24
            taskList.fixedCellHeight = cellHeight
            val listHeight = phase.tasks.size * cellHeight
            taskList.maximumSize = Dimension(Int.MAX_VALUE, listHeight)
            taskList.preferredSize = Dimension(0, listHeight)
            content.add(taskList)
        }

        add(content, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    // ── Context menu ─────────────────────────────────────────────────────────

    private fun maybeShowPopup(e: MouseEvent, list: JBList<TaskItem>) {
        if (!e.isPopupTrigger) return
        // Ensure clicked row is included in selection
        val clickedIndex = list.locationToIndex(e.point)
        if (clickedIndex >= 0 && !list.isSelectedIndex(clickedIndex)) {
            list.selectedIndex = clickedIndex
        }
        val selected = list.selectedValuesList ?: return
        if (selected.isEmpty()) return

        val popup = JPopupMenu()
        val unchecked = selected.filter { !it.checked }
        val checked = selected.filter { it.checked }

        if (unchecked.isNotEmpty()) {
            popup.add(menuItem("Execute") { runTaskAction("execute", unchecked) })
        }
        if (checked.isNotEmpty()) {
            popup.add(menuItem("Retry") { runTaskAction("retry", checked) })
        }
        popup.add(menuItem("Audit") { runTaskAction("audit", selected) })

        popup.show(list, e.x, e.y)
    }

    private fun menuItem(text: String, action: () -> Unit): JMenuItem {
        return JMenuItem(text).apply {
            addActionListener { action() }
        }
    }

    // ── Task execution ───────────────────────────────────────────────────────

    private fun runTaskAction(action: String, tasks: List<TaskItem>) {
        val chatService = project.getService(CopilotChatService::class.java) ?: return
        val dataContext = SimpleDataContext.getProjectContext(project)

        // Sequential tasks run first (in order), then [P]-marked tasks launch in parallel
        val (parallel, sequential) = tasks.partition { it.parallel }
        if (sequential.isNotEmpty()) {
            launchSequential(chatService, dataContext, action, sequential, index = 0) {
                // After all sequential tasks complete, fire parallel batch
                for (task in parallel) {
                    launchTaskSession(chatService, dataContext, action, task)
                }
            }
        } else {
            for (task in parallel) {
                launchTaskSession(chatService, dataContext, action, task)
            }
        }
    }

    private fun launchSequential(
        chatService: CopilotChatService,
        dataContext: com.intellij.openapi.actionSystem.DataContext,
        action: String,
        tasks: List<TaskItem>,
        index: Int,
        onAllComplete: (() -> Unit)? = null
    ) {
        if (index >= tasks.size) {
            onAllComplete?.invoke()
            return
        }
        val task = tasks[index]
        val prompt = buildPrompt(action, task)

        val run = ChatRun(
            agent = "implement",
            prompt = "${task.id}: ${task.description}".take(80),
            branch = chatPanel.currentGitBranch()
        )
        chatPanel.registerRun(run)

        chatService.query(dataContext) {
            withInput(prompt)
            withAgentMode()
            withNewSession()
            withSessionIdReceiver { sessionId ->
                invokeLater {
                    run.sessionId = sessionId
                    chatPanel.notifyRunChanged()
                }
                persistenceManager?.createRun(sessionId, run.agent, run.prompt, run.branch, run.startTimeMillis)
            }

            onComplete {
                invokeLater {
                    run.status = ChatRunStatus.COMPLETED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    chatPanel.notifyRunChanged()
                }
                run.sessionId?.let { persistenceManager?.completeRun(it, System.currentTimeMillis() - run.startTimeMillis) }
                launchSequential(chatService, dataContext, action, tasks, index + 1, onAllComplete)
            }

            onError { message, _, _, _, _ ->
                invokeLater {
                    run.status = ChatRunStatus.FAILED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    run.errorMessage = message
                    chatPanel.notifyRunChanged()
                }
                run.sessionId?.let { persistenceManager?.failRun(it, System.currentTimeMillis() - run.startTimeMillis, message) }
                // Stop chain on error
            }

            onCancel {
                invokeLater {
                    run.status = ChatRunStatus.CANCELLED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    chatPanel.notifyRunChanged()
                }
                run.sessionId?.let { persistenceManager?.cancelRun(it, System.currentTimeMillis() - run.startTimeMillis) }
                // Stop chain on cancel
            }
        }
    }

    private fun launchTaskSession(
        chatService: CopilotChatService,
        dataContext: com.intellij.openapi.actionSystem.DataContext,
        action: String,
        task: TaskItem
    ) {
        val prompt = buildPrompt(action, task)

        val run = ChatRun(
            agent = "implement",
            prompt = "${task.id}: ${task.description}".take(80),
            branch = chatPanel.currentGitBranch()
        )
        chatPanel.registerRun(run)

        chatService.query(dataContext) {
            withInput(prompt)
            withAgentMode()
            withNewSession()
            withSessionIdReceiver { sessionId ->
                invokeLater {
                    run.sessionId = sessionId
                    chatPanel.notifyRunChanged()
                }
                persistenceManager?.createRun(sessionId, run.agent, run.prompt, run.branch, run.startTimeMillis)
            }

            onComplete {
                invokeLater {
                    run.status = ChatRunStatus.COMPLETED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    chatPanel.notifyRunChanged()
                }
                run.sessionId?.let { persistenceManager?.completeRun(it, System.currentTimeMillis() - run.startTimeMillis) }
            }

            onError { message, _, _, _, _ ->
                invokeLater {
                    run.status = ChatRunStatus.FAILED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    run.errorMessage = message
                    chatPanel.notifyRunChanged()
                }
                run.sessionId?.let { persistenceManager?.failRun(it, System.currentTimeMillis() - run.startTimeMillis, message) }
            }

            onCancel {
                invokeLater {
                    run.status = ChatRunStatus.CANCELLED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    chatPanel.notifyRunChanged()
                }
                run.sessionId?.let { persistenceManager?.cancelRun(it, System.currentTimeMillis() - run.startTimeMillis) }
            }
        }
    }

    private fun buildPrompt(action: String, task: TaskItem): String {
        return when (action) {
            "execute" -> "Implement task ${task.id}: ${task.description}\n\nRead tasks.md, plan.md, and spec.md for full context. Mark this task as [x] in tasks.md when complete."
            "retry" -> "Re-implement task ${task.id}: ${task.description}\n\nRead tasks.md and review the existing implementation. Fix any issues and mark as [x] when done."
            "audit" -> "Audit task ${task.id}: ${task.description}\n\nRead the implementation files referenced in this task. Check for bugs, missing edge cases, and adherence to spec.md and plan.md. Report findings."
            else -> task.description
        }
    }

    // ── Cell renderer ────────────────────────────────────────────────────────

    private inner class TaskCellRenderer(
        private val tasksFile: File,
        private val greenColor: Color,
        private val grayColor: Color,
        private val hoverColor: Color
    ) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)), ListCellRenderer<TaskItem> {

        private val checkBox = JCheckBox().apply { isEnabled = false }
        private val idLabel = JLabel()
        private val tagsLabel = JLabel()
        private val descLabel = JLabel()

        init {
            isOpaque = true
            add(checkBox)
            add(idLabel)
            add(tagsLabel)
            add(descLabel)
        }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out TaskItem>,
            value: TaskItem,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            checkBox.isSelected = value.checked

            idLabel.text = value.id
            idLabel.foreground = if (isSelected) list.selectionForeground
                else if (value.checked) greenColor else list.foreground
            idLabel.font = idLabel.font.deriveFont(Font.PLAIN)

            // Tags: [P] and [US*] in gray
            val tags = buildString {
                if (value.parallel) append("[P] ")
                if (value.story != null) append("[${value.story}] ")
            }.trim()
            tagsLabel.text = tags
            tagsLabel.foreground = if (isSelected) list.selectionForeground else grayColor
            tagsLabel.isVisible = tags.isNotEmpty()

            descLabel.text = value.description
            descLabel.foreground = if (isSelected) list.selectionForeground
                else if (value.checked) greenColor else list.foreground

            background = if (isSelected) list.selectionBackground else list.background

            return this
        }
    }
}
