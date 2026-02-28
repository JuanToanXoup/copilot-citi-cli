package com.speckit.plugin.ui.onboarding

import com.github.copilot.api.CopilotChatService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.speckit.plugin.tools.ResourceLoader
import com.speckit.plugin.persistence.SessionPersistenceManager
import com.speckit.plugin.ui.ChatRun
import com.speckit.plugin.ui.ChatRunStatus
import com.speckit.plugin.ui.SessionPanel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableModel

/**
 * Compact, embeddable Discovery panel for the onboarding flow.
 *
 * Two-pane layout: category list (left) + editable attribute table (right).
 * Reuses parsing and file I/O patterns from [com.speckit.plugin.ui.DiscoveryPanel]
 * but omits VFS/Document listeners, template dropdown, and focus listeners
 * to keep the embedded demo lightweight.
 */
class DiscoveryDemoPanel(
    private val project: Project,
    private val sessionPanel: SessionPanel,
    private val persistenceManager: SessionPersistenceManager? = null
) : JPanel(BorderLayout()) {

    private val categoryTables = mutableListOf<CategoryTable>()
    private val categoryListModel = DefaultListModel<String>()
    private val categoryList = JBList(categoryListModel)
    private val detailPanel = JPanel(BorderLayout())

    @Volatile
    private var syncing = false

    private val memoryFilePath: String
        get() = "${project.basePath}/.specify/memory/discovery.md"

    init {
        border = BorderFactory.createCompoundBorder(
            RoundedLineBorder(JBColor.border(), 6),
            BorderFactory.createEmptyBorder(0, 0, 0, 0)
        )
        preferredSize = Dimension(0, 320)
        minimumSize = Dimension(0, 240)

        // Compact toolbar: template combo + Load | Generate Constitution
        val templateCombo = JComboBox<String>()
        val basePath = project.basePath
        if (basePath != null) {
            val templates = ResourceLoader.listDiscoveries(basePath)
            templateCombo.model = DefaultComboBoxModel(templates.toTypedArray())
        }
        val loadButton = JButton(AllIcons.Actions.Download).apply {
            toolTipText = "Load selected template into memory"
            addActionListener { loadSelectedTemplate(templateCombo) }
        }
        val generateButton = JButton("Generate Constitution").apply {
            addActionListener { generateConstitution() }
        }
        val toolbar = JPanel(BorderLayout(4, 0)).apply {
            border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
            add(templateCombo, BorderLayout.CENTER)
            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                add(loadButton)
                add(generateButton)
            }
            add(buttons, BorderLayout.EAST)
        }

        // Category list renderer
        categoryList.cellRenderer = CategoryListRenderer()
        categoryList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val selected = categoryList.selectedValue
                if (selected != null) updateDetailPanel(selected)
            }
        }

        // Two-pane splitter
        val splitter = OnePixelSplitter(false, 0.25f).apply {
            firstComponent = JBScrollPane(categoryList)
            secondComponent = JBScrollPane(detailPanel).apply {
                border = BorderFactory.createEmptyBorder()
            }
        }

        add(toolbar, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        // Load content: memory file first, then first available template
        loadInitialContent()
    }

    // ── Initial load ────────────────────────────────────────────────────────────

    private fun loadInitialContent() {
        // If memory file exists, load it
        val memFile = File(memoryFilePath)
        if (memFile.exists()) {
            val content = memFile.readText()
            val rows = parseDiscovery(content)
            if (rows.isNotEmpty()) {
                buildCategoryTables(rows)
                return
            }
        }

        // Auto-load the first available template so the demo is immediately
        // interactive — categories and "Ask Copilot" buttons are visible.
        val basePath = project.basePath ?: return
        val templates = ResourceLoader.listDiscoveries(basePath)
        if (templates.isNotEmpty()) {
            val content = ResourceLoader.readDiscovery(basePath, templates.first()) ?: return
            val rows = parseDiscovery(extractBody(content))
            buildCategoryTables(rows)
            writeMemoryFile()
        }
    }

    private fun loadSelectedTemplate(combo: JComboBox<String>) {
        val basePath = project.basePath ?: return
        val fileName = combo.selectedItem as? String ?: return
        val content = ResourceLoader.readDiscovery(basePath, fileName) ?: return

        val rows = parseDiscovery(extractBody(content))
        buildCategoryTables(rows)
        writeMemoryFile()
    }

    // ── Build tables ────────────────────────────────────────────────────────────

    private fun buildCategoryTables(rows: List<TableRow>) {
        syncing = true
        try {
            categoryTables.clear()
            categoryListModel.clear()
            detailPanel.removeAll()
            detailPanel.revalidate()
            detailPanel.repaint()

            val grouped = rows.groupBy { it.category }
            for ((category, catRows) in grouped) {
                val data = catRows.map { arrayOf<Any>(it.attribute, it.answer) }.toTypedArray()
                val tableModel = object : DefaultTableModel(data, arrayOf<Any>("Attribute", "Answer")) {
                    override fun isCellEditable(row: Int, column: Int) = column == 1
                }

                val cellPadding = JBUI.Borders.empty(4, 10)

                val table = JBTable(tableModel).apply {
                    setShowGrid(false)
                    showHorizontalLines = true
                    showVerticalLines = false
                    gridColor = JBColor.border()
                    autoResizeMode = javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN
                    tableHeader.isVisible = false
                    tableHeader.preferredSize = Dimension(0, 0)
                    rowHeight = JBUI.scale(32)
                    intercellSpacing = Dimension(0, 1)
                    setSelectionBackground(JBColor(0xD4E2FF, 0x2D4F7B))
                    setSelectionForeground(JBColor.foreground())

                    // Attribute column — muted label
                    columnModel.getColumn(0).cellRenderer = object : javax.swing.table.DefaultTableCellRenderer() {
                        override fun getTableCellRendererComponent(
                            table: javax.swing.JTable, value: Any?, isSelected: Boolean,
                            hasFocus: Boolean, row: Int, column: Int
                        ): java.awt.Component {
                            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                            if (!isSelected) foreground = JBColor(0x666666, 0x999999)
                            border = cellPadding
                            return this
                        }
                    }
                    // Answer column — input-field style
                    val inputBorder = JBColor(0xC0C0C0, 0x5E6366)
                    columnModel.getColumn(1).cellRenderer = object : javax.swing.table.DefaultTableCellRenderer() {
                        override fun getTableCellRendererComponent(
                            table: javax.swing.JTable, value: Any?, isSelected: Boolean,
                            hasFocus: Boolean, row: Int, column: Int
                        ): java.awt.Component {
                            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                            border = BorderFactory.createCompoundBorder(
                                BorderFactory.createCompoundBorder(
                                    JBUI.Borders.empty(3, 4, 3, 4),
                                    RoundedLineBorder(inputBorder, 4)
                                ),
                                JBUI.Borders.empty(0, 6)
                            )
                            return this
                        }
                    }
                }

                // Auto-fit attribute column
                val fm = table.getFontMetrics(table.font)
                val maxAttrWidth = catRows.maxOfOrNull { fm.stringWidth(it.attribute) } ?: 0
                val attrColWidth = maxAttrWidth + JBUI.scale(28)
                table.columnModel.getColumn(0).apply {
                    preferredWidth = attrColWidth
                    maxWidth = attrColWidth + JBUI.scale(40)
                }

                tableModel.addTableModelListener {
                    if (!syncing) {
                        writeMemoryFile()
                        categoryList.repaint()
                    }
                }

                categoryTables.add(CategoryTable(category, tableModel, table))
                categoryListModel.addElement(category)
            }

            if (categoryListModel.size() > 0) {
                categoryList.selectedIndex = 0
            }
        } finally {
            syncing = false
        }
    }

    // ── Detail panel ────────────────────────────────────────────────────────────

    private fun updateDetailPanel(category: String) {
        detailPanel.removeAll()
        val ct = categoryTables.find { it.category == category } ?: return

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        }

        // Header
        content.add(JLabel(category).apply {
            font = font.deriveFont(Font.BOLD, font.size + 3f)
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
        })

        // Table
        val h = ct.tableModel.rowCount * ct.table.rowHeight + (ct.tableModel.rowCount - 1)
        val tableWrapper = JPanel(BorderLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(ct.table, BorderLayout.CENTER)
            preferredSize = Dimension(0, h)
            maximumSize = Dimension(Int.MAX_VALUE, h)
        }
        content.add(tableWrapper)

        // Ask Copilot button for this category
        content.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
            add(JButton("Ask Copilot \u25B7").apply {
                addActionListener { askCopilotCategory(category) }
            })
        })

        content.add(javax.swing.Box.createVerticalGlue())

        detailPanel.add(content, BorderLayout.CENTER)
        detailPanel.revalidate()
        detailPanel.repaint()
    }

    // ── Category list renderer ──────────────────────────────────────────────────

    private inner class CategoryListRenderer : JPanel(BorderLayout()), ListCellRenderer<String> {
        private val iconLabel = JLabel().apply { horizontalAlignment = SwingConstants.CENTER }
        private val nameLabel = JLabel()

        init {
            isOpaque = true
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            val inner = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false; add(iconLabel); add(nameLabel) }
            add(inner, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out String>, value: String,
            index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val ct = categoryTables.find { it.category == value }
            val allFilled = ct != null && (0 until ct.tableModel.rowCount).all {
                ct.tableModel.getValueAt(it, 1).toString().isNotBlank()
            }

            iconLabel.text = if (allFilled) "\u2713" else "\u25CB"
            iconLabel.foreground = if (allFilled)
                JBColor(Color(0, 128, 0), Color(80, 200, 80))
            else
                JBColor.GRAY
            nameLabel.text = value
            nameLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
            background = if (isSelected) list.selectionBackground else list.background
            return this
        }
    }

    // ── File I/O ────────────────────────────────────────────────────────────────

    private fun writeMemoryFile() {
        val basePath = project.basePath ?: return

        val sb = StringBuilder()
        var firstCategory = true
        for (ct in categoryTables) {
            if (!firstCategory) sb.appendLine()
            firstCategory = false
            sb.appendLine("## ${ct.category}")
            for (i in 0 until ct.tableModel.rowCount) {
                val attribute = ct.tableModel.getValueAt(i, 0).toString()
                val answer = ct.tableModel.getValueAt(i, 1).toString()
                sb.appendLine("- $attribute = $answer")
            }
        }

        syncing = true
        val text = sb.toString()
        WriteCommandAction.runWriteCommandAction(project) {
            try {
                val dir = File(basePath, ".specify/memory")
                dir.mkdirs()
                val ioFile = File(dir, "discovery.md")
                ioFile.writeText(text)
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            } finally {
                invokeLater { syncing = false }
            }
        }
    }

    // ── Copilot actions ─────────────────────────────────────────────────────────

    private fun askCopilotCategory(category: String) {
        val chatService = project.getService(CopilotChatService::class.java) ?: return
        val dataContext = SimpleDataContext.getProjectContext(project)
        val prompt = "Using your tools and this project as your source of truth, " +
            "update only the \"$category\" section in the `.specify/memory/discovery.md` file " +
            "with your answers. The file uses `## Category` headings and `- Attribute = Answer` bullet lines. " +
            "Keep this exact format — do not change delimiters or structure. Example: `- Service name = order-service`. " +
            "If you cannot find concrete evidence for an attribute, leave the value empty after the `=`. " +
            "Do not write \"Unknown\" or guess."

        val run = ChatRun(
            agent = "discovery",
            prompt = "Ask Copilot: $category",
            branch = sessionPanel.currentGitBranch()
        )
        sessionPanel.registerRun(run)

        chatService.query(dataContext) {
            withInput(prompt)
            withAgentMode()
            withNewSession()
            withSessionIdReceiver { sessionId ->
                invokeLater {
                    run.sessionId = sessionId
                    sessionPanel.notifyRunChanged()
                }
                persistenceManager?.createRun(sessionId, run.agent, run.prompt, run.branch, run.startTimeMillis)
            }
            onComplete {
                invokeLater {
                    run.status = ChatRunStatus.COMPLETED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    sessionPanel.notifyRunChanged()
                }
                run.sessionId?.let { persistenceManager?.completeRun(it, System.currentTimeMillis() - run.startTimeMillis) }
            }
            onError { message, _, _, _, _ ->
                invokeLater {
                    run.status = ChatRunStatus.FAILED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    run.errorMessage = message
                    sessionPanel.notifyRunChanged()
                }
                run.sessionId?.let { persistenceManager?.failRun(it, System.currentTimeMillis() - run.startTimeMillis, message) }
            }
            onCancel {
                invokeLater {
                    run.status = ChatRunStatus.CANCELLED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    sessionPanel.notifyRunChanged()
                }
                run.sessionId?.let { persistenceManager?.cancelRun(it, System.currentTimeMillis() - run.startTimeMillis) }
            }
        }
    }

    private fun generateConstitution() {
        val basePath = project.basePath ?: return
        val chatService = project.getService(CopilotChatService::class.java) ?: return

        val arguments = mutableListOf<String>()
        for (ct in categoryTables) {
            for (i in 0 until ct.tableModel.rowCount) {
                val attr = ct.tableModel.getValueAt(i, 0).toString()
                val answer = ct.tableModel.getValueAt(i, 1).toString()
                arguments.add("${ct.category} / $attr: ${answer.ifEmpty { "(unanswered)" }}")
            }
        }

        val agentContent = ResourceLoader.readAgent(basePath, "speckit.constitution.agent.md") ?: return
        val prompt = agentContent.replace("\$ARGUMENTS", arguments.joinToString("\n"))
        val dataContext = SimpleDataContext.getProjectContext(project)

        val run = ChatRun(
            agent = "constitution",
            prompt = "Generate Constitution",
            branch = sessionPanel.currentGitBranch()
        )
        sessionPanel.registerRun(run)

        chatService.query(dataContext) {
            withInput(prompt)
            withAgentMode()
            withNewSession()
            withSessionIdReceiver { sessionId ->
                invokeLater {
                    run.sessionId = sessionId
                    sessionPanel.notifyRunChanged()
                }
                persistenceManager?.createRun(sessionId, run.agent, run.prompt, run.branch, run.startTimeMillis)
            }
            onComplete {
                invokeLater {
                    run.status = ChatRunStatus.COMPLETED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    sessionPanel.notifyRunChanged()
                }
                run.sessionId?.let { persistenceManager?.completeRun(it, System.currentTimeMillis() - run.startTimeMillis) }
            }
            onError { message, _, _, _, _ ->
                invokeLater {
                    run.status = ChatRunStatus.FAILED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    run.errorMessage = message
                    sessionPanel.notifyRunChanged()
                }
                run.sessionId?.let { persistenceManager?.failRun(it, System.currentTimeMillis() - run.startTimeMillis, message) }
            }
            onCancel {
                invokeLater {
                    run.status = ChatRunStatus.CANCELLED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    sessionPanel.notifyRunChanged()
                }
                run.sessionId?.let { persistenceManager?.cancelRun(it, System.currentTimeMillis() - run.startTimeMillis) }
            }
        }
    }

    // ── Parsing ─────────────────────────────────────────────────────────────────

    private data class TableRow(val category: String, val attribute: String, val answer: String)

    private fun extractBody(content: String): String {
        val match = Regex("^---\\s*\\n.*?\\n---\\s*\\n?", RegexOption.DOT_MATCHES_ALL).find(content) ?: return content
        return content.substring(match.range.last + 1)
    }

    private fun parseDiscovery(content: String): List<TableRow> {
        val rows = mutableListOf<TableRow>()
        var currentCategory = ""
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            if (trimmed.startsWith("## ")) {
                currentCategory = trimmed.removePrefix("## ").trim()
                continue
            }

            if (trimmed.startsWith("- ") && currentCategory.isNotEmpty()) {
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx > 0) {
                    val attribute = trimmed.substring(2, eqIdx).trim()
                    val answer = trimmed.substring(eqIdx + 1).trim()
                    rows.add(TableRow(currentCategory, attribute, answer))
                }
            }
        }
        return rows
    }

    // ── Data ────────────────────────────────────────────────────────────────────

    private class CategoryTable(
        val category: String,
        val tableModel: DefaultTableModel,
        val table: JBTable
    )
}
