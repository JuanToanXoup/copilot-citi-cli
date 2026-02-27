package com.speckit.plugin.ui

import com.github.copilot.agent.session.CopilotAgentSessionManager
import com.github.copilot.api.CopilotChatService
import com.github.copilot.chat.window.ShowChatToolWindowsListener
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.speckit.plugin.tools.ResourceLoader
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
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
    private val moreButton: JButton
    private val scope = CoroutineScope(Dispatchers.Default)

    private val runs = mutableListOf<ChatRun>()
    private val tableModel = ChatRunTableModel(runs)
    private val table = JBTable(tableModel)

    init {
        Disposer.register(parentDisposable, this)

        agentCombo = JComboBox<AgentEntry>()
        argField = JBTextArea(3, 0).apply {
            lineWrap = true
            wrapStyleWord = true
            margin = java.awt.Insets(6, 8, 6, 8)
        }
        sendButton = JButton(com.intellij.icons.AllIcons.Actions.Execute)
        moreButton = JButton(com.intellij.icons.AllIcons.Actions.More).apply {
            toolTipText = "More actions"
            addActionListener { e ->
                val popup = javax.swing.JPopupMenu().apply {
                    border = BorderFactory.createCompoundBorder(
                        RoundedLineBorder(JBColor.border(), 8),
                        BorderFactory.createEmptyBorder(4, 0, 4, 0)
                    )
                }
                popup.add(createMenuItem("Refresh Agents", com.intellij.icons.AllIcons.Actions.Refresh) { loadAgents() })
                popup.add(createMenuItem("Download Latest Speckit", com.intellij.icons.AllIcons.Actions.Download) { installSpeckit() })
                val src = e.source as java.awt.Component
                popup.show(src, 0, src.height)
            }
        }

        // Agent bar: dropdown (fills width) + more + send icons
        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            add(moreButton)
            add(sendButton)
        }
        val agentBar = JPanel(BorderLayout(4, 0))
        agentBar.add(agentCombo, BorderLayout.CENTER)
        agentBar.add(buttonsPanel, BorderLayout.EAST)

        // Prompt area: grows with content up to 10 lines, then scrolls
        val lineHeight = argField.getFontMetrics(argField.font).height
        val padding = argField.margin.top + argField.margin.bottom + argField.insets.top + argField.insets.bottom
        val minHeight = lineHeight + padding
        val maxHeight = lineHeight * 10 + padding
        val promptPanel = JBScrollPane(argField).apply {
            preferredSize = Dimension(0, minHeight)
            verticalScrollBarPolicy = javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        argField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = resize()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = resize()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = resize()
            private fun resize() {
                invokeLater {
                    val textHeight = argField.preferredSize.height
                    val newHeight = textHeight.coerceIn(minHeight, maxHeight)
                    if (promptPanel.preferredSize.height != newHeight) {
                        promptPanel.preferredSize = Dimension(0, newHeight)
                        promptPanel.revalidate()
                        this@SpeckitChatPanel.revalidate()
                    }
                }
            }
        })

        val topPanel = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 4, 8)
        }
        topPanel.add(agentBar, BorderLayout.NORTH)
        topPanel.add(promptPanel, BorderLayout.SOUTH)

        // Table setup
        table.setShowGrid(false)
        table.autoResizeMode = JBTable.AUTO_RESIZE_OFF
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

        tableModel.addTableModelListener { packColumns() }

        // Double-click to activate session in Copilot Chat
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = table.rowAtPoint(e.point)
                    if (row >= 0) activateRun(runs[row])
                }
            }
        })

        val tableScrollPane = JBScrollPane(table).apply {
            border = BorderFactory.createEmptyBorder(0, 8, 8, 8)
        }

        add(topPanel, BorderLayout.NORTH)
        add(tableScrollPane, BorderLayout.CENTER)

        sendButton.addActionListener { sendMessage() }
        val ctrlEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK)
        argField.getInputMap().put(ctrlEnter, "send")
        argField.getActionMap().put("send", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { sendMessage() }
        })
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

    internal fun currentGitBranch(): String {
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

    private fun packColumns() {
        val spacing = 12
        for (col in 0 until table.columnCount) {
            val column = table.columnModel.getColumn(col)
            var width = table.tableHeader.defaultRenderer
                .getTableCellRendererComponent(table, column.headerValue, false, false, -1, col)
                .preferredSize.width
            for (row in 0 until table.rowCount) {
                val renderer = table.getCellRenderer(row, col)
                val comp = table.prepareRenderer(renderer, row, col)
                width = maxOf(width, comp.preferredSize.width)
            }
            column.preferredWidth = width + spacing
        }
    }

    private fun createMenuItem(text: String, icon: javax.swing.Icon, action: () -> Unit): javax.swing.JMenuItem {
        return object : javax.swing.JMenuItem(text, icon) {
            private var hovered = false

            init {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
                addActionListener { action() }
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
                    override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
                })
            }

            override fun paintComponent(g: java.awt.Graphics) {
                if (hovered) {
                    val g2 = g.create() as java.awt.Graphics2D
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = JBColor(Color(0, 0, 0, 20), Color(255, 255, 255, 20))
                    g2.fillRoundRect(4, 0, width - 8, height, 8, 8)
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }
    }

    private fun installSpeckit() {
        val basePath = project.basePath ?: return
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val shellType = if (isWindows) "ps" else "sh"

        val cmd = if (isWindows) {
            val psScript = """
                ${"$"}ErrorActionPreference = 'Stop'
                Write-Host 'Fetching latest spec-kit release...'
                ${"$"}release = Invoke-RestMethod -Uri 'https://api.github.com/repos/github/spec-kit/releases/latest'
                ${"$"}asset = ${"$"}release.assets | Where-Object { ${"$"}_.name -like '*copilot-${shellType}*' } | Select-Object -First 1
                if (-not ${"$"}asset) { Write-Error 'No copilot-${shellType} asset found'; exit 1 }
                ${"$"}tmpZip = Join-Path ${"$"}env:TEMP ('speckit-' + [guid]::NewGuid().ToString('N') + '.zip')
                Write-Host "Downloading ${"$"}(${"$"}asset.browser_download_url)"
                Invoke-WebRequest -Uri ${"$"}asset.browser_download_url -OutFile ${"$"}tmpZip
                Write-Host 'Extracting to ${basePath.replace("\\", "\\\\")}...'
                Expand-Archive -Path ${"$"}tmpZip -DestinationPath '${basePath.replace("'", "''")}' -Force
                Remove-Item ${"$"}tmpZip -Force
                Write-Host 'Done.'
            """.trimIndent()
            GeneralCommandLine("powershell", "-NoProfile", "-Command", psScript)
        } else {
            val shScript = """
                set -e
                TMPZIP=${"$"}(mktemp /tmp/speckit-XXXXXX.zip)
                echo "Fetching latest spec-kit release..."
                URL=${"$"}(curl -sL https://api.github.com/repos/github/spec-kit/releases/latest \
                  | grep -o '"browser_download_url":[^,]*copilot-${shellType}[^"]*' \
                  | cut -d'"' -f4)
                if [ -z "${"$"}URL" ]; then echo "ERROR: No copilot-${shellType} asset found"; exit 1; fi
                echo "Downloading ${"$"}URL"
                curl -Lo "${"$"}TMPZIP" "${"$"}URL"
                echo "Extracting to ${basePath}..."
                unzip -o "${"$"}TMPZIP" -d "${basePath}"
                rm -f "${"$"}TMPZIP"
                echo "Done."
            """.trimIndent()
            GeneralCommandLine("bash", "-c", shScript)
        }

        cmd.withWorkDirectory(basePath)
        val handler = OSProcessHandler(cmd)

        RunContentExecutor(project, handler)
            .withTitle("Speckit Install")
            .withAfterCompletion { invokeLater { loadAgents() } }
            .run()
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

    fun registerRun(run: ChatRun) {
        runs.add(0, run)
        tableModel.fireTableDataChanged()
    }

    fun notifyRunChanged() {
        tableModel.fireTableDataChanged()
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
