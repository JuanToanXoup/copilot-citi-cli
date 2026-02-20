package com.citigroup.copilotchat.ui

import com.citigroup.copilotchat.config.CopilotChatSettings
import com.citigroup.copilotchat.config.CopilotChatSettings.WorkerEntry
import com.citigroup.copilotchat.conversation.ChatEvent
import com.citigroup.copilotchat.conversation.ConversationManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.awt.*
import javax.swing.*

/**
 * Panel for managing worker agents and orchestrating tasks across them.
 * Each worker is an independent conversation with a specialized role.
 */
class WorkerPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Worker list on the left
    private val workerListModel = DefaultListModel<WorkerState>()
    private val workerList = JList(workerListModel)

    // Orchestrator input at top
    private val orchestrateField = JBTextField()
    private val orchestrateButton = JButton("Orchestrate")

    // Worker output area on the right
    private val outputArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    }

    // Worker task input at bottom
    private val taskField = JBTextField()
    private val sendTaskButton = JButton(AllIcons.Actions.Execute)

    data class WorkerState(
        val entry: WorkerEntry,
        var status: String = "idle",
        val output: StringBuilder = StringBuilder(),
        var conversationManager: ConversationManager? = null,
    ) {
        override fun toString() = "[${status}] ${entry.role}"
    }

    init {
        border = JBUI.Borders.empty(4)

        // === Top: Orchestrator bar ===
        val orchestratorBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(JLabel("Goal: "), BorderLayout.WEST)
            add(orchestrateField, BorderLayout.CENTER)
            add(orchestrateButton, BorderLayout.EAST)
        }
        orchestrateButton.addActionListener { orchestrate() }

        // === Left: Worker list ===
        workerList.cellRenderer = WorkerCellRenderer()
        workerList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        workerList.addListSelectionListener { showWorkerOutput() }

        val workerListPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(200, 0)
            val header = JPanel(BorderLayout()).apply {
                add(JLabel("Workers"), BorderLayout.WEST)
                val btns = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    add(JButton(AllIcons.General.Add).apply {
                        toolTipText = "Add Worker"
                        addActionListener { addWorker() }
                    })
                    add(JButton(AllIcons.General.Remove).apply {
                        toolTipText = "Remove Selected"
                        addActionListener { removeWorker() }
                    })
                }
                add(btns, BorderLayout.EAST)
            }
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(workerList), BorderLayout.CENTER)
        }

        // === Right: Output + task input ===
        val outputPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(outputArea), BorderLayout.CENTER)
            val taskBar = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
                add(taskField, BorderLayout.CENTER)
                add(sendTaskButton, BorderLayout.EAST)
            }
            add(taskBar, BorderLayout.SOUTH)
        }
        sendTaskButton.addActionListener { sendTaskToWorker() }

        // === Layout ===
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, workerListPanel, outputPanel).apply {
            dividerLocation = 200
        }

        add(orchestratorBar, BorderLayout.NORTH)
        add(splitPane, BorderLayout.CENTER)

        loadWorkersFromSettings()
    }

    private fun loadWorkersFromSettings() {
        workerListModel.clear()
        CopilotChatSettings.getInstance().workers.filter { it.enabled }.forEach {
            workerListModel.addElement(WorkerState(it))
        }
    }

    private fun addWorker() {
        val dialog = WorkerDialog(null)
        if (dialog.showAndGet()) {
            val entry = dialog.result!!
            CopilotChatSettings.getInstance().workers.add(entry)
            workerListModel.addElement(WorkerState(entry))
        }
    }

    private fun removeWorker() {
        val idx = workerList.selectedIndex
        if (idx < 0) return
        val ws = workerListModel.getElementAt(idx)
        if (Messages.showYesNoDialog(
                "Remove worker '${ws.entry.role}'?",
                "Remove Worker",
                Messages.getQuestionIcon()
            ) == Messages.YES
        ) {
            CopilotChatSettings.getInstance().workers.remove(ws.entry)
            workerListModel.remove(idx)
        }
    }

    private fun showWorkerOutput() {
        val idx = workerList.selectedIndex
        if (idx < 0) {
            outputArea.text = ""
            return
        }
        val ws = workerListModel.getElementAt(idx)
        outputArea.text = ws.output.toString()
    }

    private fun sendTaskToWorker() {
        val idx = workerList.selectedIndex
        if (idx < 0) {
            Messages.showWarningDialog("Select a worker first.", "No Worker Selected")
            return
        }
        val task = taskField.text.trim()
        if (task.isEmpty()) return
        taskField.text = ""

        val ws = workerListModel.getElementAt(idx)
        ws.status = "working"
        ws.output.append("\n--- Task: $task ---\n")
        outputArea.text = ws.output.toString()
        workerList.repaint()

        // Use the project's ConversationManager to send a prefixed message
        val cm = ConversationManager.getInstance(project)
        val prompt = buildWorkerPrompt(ws.entry, task)

        scope.launch {
            cm.events.collectLatest { event ->
                when (event) {
                    is ChatEvent.Delta -> {
                        ws.output.append(event.text)
                        if (workerList.selectedIndex == idx) {
                            outputArea.text = ws.output.toString()
                            outputArea.caretPosition = outputArea.document.length
                        }
                    }
                    is ChatEvent.ToolCall -> {
                        ws.output.append("\n[tool] ${event.name}\n")
                    }
                    is ChatEvent.Done -> {
                        ws.status = "idle"
                        ws.output.append("\n--- Done ---\n")
                        workerList.repaint()
                        if (workerList.selectedIndex == idx) {
                            outputArea.text = ws.output.toString()
                        }
                    }
                    is ChatEvent.Error -> {
                        ws.status = "error"
                        ws.output.append("\n[error] ${event.message}\n")
                        workerList.repaint()
                    }
                    else -> {}
                }
            }
        }

        cm.sendMessage(prompt)
    }

    private fun orchestrate() {
        val goal = orchestrateField.text.trim()
        if (goal.isEmpty()) return
        if (workerListModel.isEmpty) {
            Messages.showInfoMessage("Add workers first before orchestrating.", "No Workers")
            return
        }

        // Build an orchestration prompt that decomposes the goal into worker tasks
        val workerDescs = (0 until workerListModel.size()).map { i ->
            val w = workerListModel.getElementAt(i).entry
            "- ${w.role}: ${w.description}"
        }.joinToString("\n")

        val orchestrationPrompt = """You are an orchestrator. Break down this goal into tasks for the available workers.

Available workers:
$workerDescs

Goal: $goal

For each subtask, specify which worker role should handle it and what the task is.
Then execute each subtask by working through them sequentially."""

        val cm = ConversationManager.getInstance(project)
        cm.sendMessage(orchestrationPrompt)

        // Show output in first worker for now
        if (workerListModel.size() > 0) {
            workerList.selectedIndex = 0
        }
    }

    private fun buildWorkerPrompt(worker: WorkerEntry, task: String): String {
        val sb = StringBuilder()
        if (worker.systemPrompt.isNotBlank()) {
            sb.append("System instructions: ${worker.systemPrompt}\n\n")
        }
        sb.append("You are acting as a '${worker.role}' specialist. ${worker.description}\n\n")
        sb.append("Task: $task")
        return sb.toString()
    }

    private class WorkerCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val ws = value as? WorkerState ?: return this
            val statusColor = when (ws.status) {
                "working" -> "#FF9800"
                "error" -> "#F44336"
                else -> "#4CAF50"
            }
            text = "<html><b>${ws.entry.role}</b> <span style='color: $statusColor'>[${ws.status}]</span></html>"
            icon = when (ws.status) {
                "working" -> AllIcons.Process.Step_1
                "error" -> AllIcons.General.Error
                else -> AllIcons.Nodes.Deploy
            }
            return this
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}

/**
 * Dialog for adding/editing a worker agent.
 */
class WorkerDialog(private val existing: WorkerEntry?) : DialogWrapper(true) {
    var result: WorkerEntry? = null
        private set

    private val roleField = JBTextField(existing?.role ?: "")
    private val descField = JBTextField(existing?.description ?: "")
    private val modelField = JBTextField(existing?.model ?: "")
    private val promptArea = JTextArea(existing?.systemPrompt ?: "", 5, 40)

    init {
        title = if (existing != null) "Edit Worker" else "Add Worker"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
            weightx = 1.0
        }

        var row = 0
        fun addRow(label: String, comp: JComponent) {
            gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
            panel.add(JLabel(label), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            panel.add(comp, gbc)
            row++
        }

        addRow("Role:", roleField)
        addRow("Description:", descField)
        addRow("Model:", modelField)

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("System Prompt:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        val promptScroll = JBScrollPane(promptArea)
        promptScroll.preferredSize = Dimension(400, 100)
        panel.add(promptScroll, gbc)
        row++

        // Presets hint
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2
        panel.add(JLabel("<html><i style='color: gray'>Examples: bug_fixer, test_writer, code_reviewer, playwright_tester</i></html>"), gbc)

        return panel
    }

    override fun doOKAction() {
        if (roleField.text.isBlank()) {
            Messages.showErrorDialog("Worker role is required.", "Validation Error")
            return
        }
        result = WorkerEntry(
            role = roleField.text.trim(),
            description = descField.text.trim(),
            model = modelField.text.trim(),
            systemPrompt = promptArea.text.trim(),
            enabled = true,
        )
        super.doOKAction()
    }
}
