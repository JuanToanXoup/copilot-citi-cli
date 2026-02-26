package com.speckit.plugin.ui

import com.github.copilot.agent.session.CopilotAgentSessionManager
import com.github.copilot.api.CopilotChatService
import com.github.copilot.chat.window.ShowChatToolWindowsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.speckit.plugin.tools.ResourceLoader
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SpeckitChatPanel(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val agentCombo: JComboBox<AgentEntry>
    private val argField: JBTextArea
    private val sendButton: JButton
    private val refreshButton: JButton
    private val scope = CoroutineScope(Dispatchers.Default)

    private val runs = mutableListOf<ChatRun>()
    private val tableModel = ChatRunTableModel(runs)
    private val table = JBTable(tableModel)

    init {
        Disposer.register(parentDisposable, this)

        agentCombo = JComboBox<AgentEntry>().apply {
            preferredSize = Dimension(500, preferredSize.height)
        }
        argField = JBTextArea(3, 0).apply {
            lineWrap = true
            wrapStyleWord = true
        }
        sendButton = JButton(com.intellij.icons.AllIcons.Actions.Execute)
        refreshButton = JButton(com.intellij.icons.AllIcons.Actions.Refresh)

        // Top: text area + send button
        val argScrollPane = JBScrollPane(argField).apply {
            preferredSize = Dimension(0, 60)
        }
        val topBar = JPanel(BorderLayout(4, 0))
        topBar.add(argScrollPane, BorderLayout.CENTER)
        topBar.add(sendButton, BorderLayout.EAST)

        // Controls bar: agent dropdown + refresh
        val controlsBar = JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)
            add(JLabel("Agent:"))
            add(agentCombo)
            add(refreshButton)
        }

        val topPanel = JPanel(BorderLayout(0, 4))
        topPanel.add(topBar, BorderLayout.NORTH)
        topPanel.add(controlsBar, BorderLayout.SOUTH)

        // Table setup
        table.setShowGrid(false)
        table.autoResizeMode = JBTable.AUTO_RESIZE_ALL_COLUMNS
        table.setAutoCreateColumnsFromModel(true)
        val leftAligned = DefaultTableCellRenderer().apply {
            horizontalAlignment = SwingConstants.LEFT
        }
        for (i in 0 until table.columnCount) {
            if (i == 4) {
                table.columnModel.getColumn(i).cellRenderer = StatusCellRenderer()
            } else {
                table.columnModel.getColumn(i).cellRenderer = leftAligned
            }
        }
        table.tableHeader.defaultRenderer = DefaultTableCellRenderer().apply {
            horizontalAlignment = SwingConstants.LEFT
        }

        // Double-click to activate session in Copilot Chat
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = table.rowAtPoint(e.point)
                    if (row >= 0) activateRun(runs[row])
                }
            }
        })

        add(topPanel, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)

        sendButton.addActionListener { sendMessage() }
        val ctrlEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK)
        argField.getInputMap().put(ctrlEnter, "send")
        argField.getActionMap().put("send", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { sendMessage() }
        })
        refreshButton.addActionListener { loadAgents() }

        loadAgents()
    }

    private fun loadAgents() {
        val basePath = project.basePath ?: return
        val agentFiles = ResourceLoader.listAgents(basePath)
        val entries = agentFiles.map { fileName ->
            val slug = fileName.removePrefix("speckit.").removeSuffix(".agent.md")
            val content = ResourceLoader.readAgent(basePath, fileName)
            val description = content?.let { parseDescription(it) } ?: ""
            AgentEntry(fileName, slug, description)
        }
        agentCombo.model = DefaultComboBoxModel(entries.toTypedArray())
    }

    private fun currentGitBranch(): String {
        val basePath = project.basePath ?: return ""
        return try {
            val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .directory(java.io.File(basePath))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0) output else ""
        } catch (_: Exception) { "" }
    }

    private fun sendMessage() {
        val agent = agentCombo.selectedItem as? AgentEntry ?: return
        val argument = argField.text.trim()
        val basePath = project.basePath ?: return

        val agentContent = ResourceLoader.readAgent(basePath, agent.fileName)
        if (agentContent == null) return

        val prompt = agentContent.replace("\$ARGUMENTS", argument)
        argField.text = ""

        val chatService = project.getService(CopilotChatService::class.java) ?: return

        val run = ChatRun(
            agent = agent.slug,
            prompt = argument.ifEmpty { "(no arguments)" },
            branch = currentGitBranch()
        )
        runs.add(0, run)
        tableModel.fireTableDataChanged()

        val dataContext = SimpleDataContext.getProjectContext(project)

        chatService.query(dataContext) {
            withInput(prompt)
            withAgentMode()
            withNewSession()
            withSessionIdReceiver { sessionId ->
                invokeLater {
                    run.sessionId = sessionId
                    tableModel.fireTableDataChanged()
                }
            }

            onComplete {
                invokeLater {
                    run.status = ChatRunStatus.COMPLETED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    tableModel.fireTableDataChanged()
                }
            }

            onError { message, _, _, _, _ ->
                invokeLater {
                    run.status = ChatRunStatus.FAILED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    run.errorMessage = message
                    tableModel.fireTableDataChanged()
                }
            }

            onCancel {
                invokeLater {
                    run.status = ChatRunStatus.CANCELLED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    tableModel.fireTableDataChanged()
                }
            }
        }
    }

    private fun activateRun(run: ChatRun) {
        val sessionId = run.sessionId ?: return
        val sessionManager = project.service<CopilotAgentSessionManager>()
        scope.launch {
            try {
                sessionManager.activateSession(sessionId)
                invokeLater {
                    if (project.isDisposed) return@invokeLater
                    project.messageBus
                        .syncPublisher(ShowChatToolWindowsListener.TOPIC)
                        .showChatToolWindow()
                }
            } catch (_: Exception) {}
        }
    }

    override fun dispose() {}
}

// ── Data model ──────────────────────────────────────────────────────────────

enum class ChatRunStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

class ChatRun(
    val agent: String,
    val prompt: String,
    val branch: String,
    val startTimeMillis: Long = System.currentTimeMillis()
) {
    @Volatile var status: ChatRunStatus = ChatRunStatus.RUNNING
    @Volatile var durationMs: Long = 0
    @Volatile var sessionId: String? = null
    @Volatile var errorMessage: String? = null
}

// ── Table model ─────────────────────────────────────────────────────────────

private class ChatRunTableModel(private val runs: List<ChatRun>) : AbstractTableModel() {

    private val columns = arrayOf("Time", "Branch", "Agent", "Prompt", "Status", "Duration", "Session ID")

    override fun getRowCount(): Int = runs.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        if (rowIndex >= runs.size) return ""
        val run = runs[rowIndex]
        return when (columnIndex) {
            0 -> SimpleDateFormat("MM/dd hh:mm:ss a").format(Date(run.startTimeMillis))
            1 -> run.branch
            2 -> run.agent
            3 -> run.prompt.take(80)
            4 -> run.status
            5 -> if (run.status == ChatRunStatus.RUNNING) "..."
                 else String.format("%.1fs", run.durationMs / 1000.0)
            6 -> run.sessionId?.take(8) ?: "..."
            else -> ""
        }
    }
}

// ── Status cell renderer ────────────────────────────────────────────────────

private class StatusCellRenderer : DefaultTableCellRenderer() {
    init { horizontalAlignment = SwingConstants.LEFT }
    override fun getTableCellRendererComponent(
        table: javax.swing.JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        val status = value as? ChatRunStatus ?: return component
        text = when (status) {
            ChatRunStatus.RUNNING -> "Running..."
            ChatRunStatus.COMPLETED -> "Completed"
            ChatRunStatus.FAILED -> "Failed"
            ChatRunStatus.CANCELLED -> "Cancelled"
        }
        if (!isSelected) {
            foreground = when (status) {
                ChatRunStatus.RUNNING -> JBColor.BLUE
                ChatRunStatus.COMPLETED -> JBColor(Color(0, 128, 0), Color(80, 200, 80))
                ChatRunStatus.FAILED -> JBColor.RED
                ChatRunStatus.CANCELLED -> JBColor.GRAY
            }
        }
        return component
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun parseDescription(content: String): String {
    val match = Regex("^description:\\s*(.+)$", RegexOption.MULTILINE).find(content)
    return match?.groupValues?.get(1)?.trim() ?: ""
}

private data class AgentEntry(
    val fileName: String,
    val slug: String,
    val description: String
) {
    override fun toString(): String {
        return if (description.isNotEmpty()) "$slug - $description" else slug
    }
}
