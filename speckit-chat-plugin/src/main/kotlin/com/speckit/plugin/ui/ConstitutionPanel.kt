package com.speckit.plugin.ui

import com.github.copilot.api.CopilotChatService
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.speckit.plugin.tools.ResourceLoader
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableModel

class ConstitutionPanel(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val templateCombo = javax.swing.JComboBox<String>()
    private val categoryTables = mutableListOf<CategoryTable>()
    private val categoryListModel = DefaultListModel<String>()
    private val categoryList = JBList(categoryListModel)
    private val detailPanel = JPanel(BorderLayout())

    @Volatile
    private var syncing = false

    private val memoryFilePath: String
        get() = "${project.basePath}/.specify/memory/discovery.md"

    init {
        Disposer.register(parentDisposable, this)

        // Top bar
        val loadButton = JButton(AllIcons.Actions.Download).apply {
            toolTipText = "Load selected template into memory"
            addActionListener { loadSelectedTemplate() }
        }
        val askAllButton = JButton(AllIcons.Actions.Execute).apply {
            toolTipText = "Ask Copilot to fill all answers"
            addActionListener { askCopilotAll() }
        }
        val generateButton = JButton("Generate Constitution").apply {
            addActionListener { generateConstitution() }
        }

        val topBar = JPanel(BorderLayout(4, 0)).apply {
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            add(templateCombo, BorderLayout.CENTER)
            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                add(askAllButton)
                add(loadButton)
                add(generateButton)
            }
            add(buttons, BorderLayout.EAST)
        }

        // Category list
        categoryList.cellRenderer = CategoryListRenderer()
        categoryList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val selected = categoryList.selectedValue
                if (selected != null) updateDetailPanel(selected)
            }
        }

        // Split pane
        val splitter = OnePixelSplitter(false, 0.25f).apply {
            firstComponent = JBScrollPane(categoryList)
            secondComponent = JBScrollPane(detailPanel).apply {
                border = BorderFactory.createEmptyBorder()
            }
        }

        add(topBar, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        // Listen for external changes to the memory file
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (syncing) return
                val memPath = memoryFilePath
                val relevant = events.any { e ->
                    val path = e.path ?: (e as? VFileContentChangeEvent)?.file?.path
                    path == memPath
                }
                if (relevant) {
                    invokeLater { loadFromMemoryFile() }
                }
            }
        })

        // App focus listener — reload when IDE regains focus (catches external writes)
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus
            .connect(this)
            .subscribe(com.intellij.openapi.application.ApplicationActivationListener.TOPIC,
                object : com.intellij.openapi.application.ApplicationActivationListener {
                    override fun applicationActivated(ideFrame: com.intellij.openapi.wm.IdeFrame) {
                        refreshFromDisk()
                    }
                })

        refreshTemplateList()

        val memFile = File(memoryFilePath)
        if (memFile.exists()) {
            loadFromMemoryFile()
        }
    }

    private fun refreshTemplateList() {
        val basePath = project.basePath ?: return
        val templates = ResourceLoader.listDiscoveries(basePath)
        templateCombo.model = javax.swing.DefaultComboBoxModel(templates.toTypedArray())
    }

    private fun loadSelectedTemplate() {
        val basePath = project.basePath ?: return
        val fileName = templateCombo.selectedItem as? String ?: return
        val content = ResourceLoader.readDiscovery(basePath, fileName) ?: return

        val rows = parseDiscovery(extractBody(content))
        buildCategoryTables(rows)
        writeMemoryFile()
    }

    private fun loadFromMemoryFile() {
        val memFile = File(memoryFilePath)
        // Refresh VFS so we pick up any disk changes (e.g. from Copilot agent writes)
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(memFile)
        val content = if (vFile != null) {
            vFile.refresh(false, false)
            val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            // If the Document has unsaved external changes, reload it from disk
            if (vFile.timeStamp != vFile.modificationStamp) {
                fdm.reloadFiles(vFile)
            }
            val doc = fdm.getDocument(vFile)
            doc?.text ?: if (memFile.exists()) memFile.readText() else return
        } else {
            if (!memFile.exists()) return
            memFile.readText()
        }
        val rows = parseDiscovery(content)
        val grouped = rows.groupBy { it.category }

        syncing = true
        try {
            // Check if structure matches — same categories in same order with same attributes
            val structureMatch = grouped.keys.toList() == categoryTables.map { it.category } &&
                categoryTables.all { ct ->
                    val catRows = grouped[ct.category] ?: return@all false
                    catRows.size == ct.tableModel.rowCount &&
                    catRows.indices.all { i -> ct.tableModel.getValueAt(i, 0).toString() == catRows[i].attribute }
                }

            if (structureMatch) {
                // Update answers only
                for (ct in categoryTables) {
                    val catRows = grouped[ct.category] ?: continue
                    for ((i, row) in catRows.withIndex()) {
                        if (ct.tableModel.getValueAt(i, 1).toString() != row.answer) {
                            ct.tableModel.setValueAt(row.answer, i, 1)
                        }
                    }
                }
                categoryList.repaint()
                val selected = categoryList.selectedValue
                if (selected != null) updateDetailPanel(selected)
            } else {
                buildCategoryTables(rows)
            }
        } finally {
            syncing = false
        }
    }

    private fun buildCategoryTables(rows: List<TableRow>) {
        syncing = true
        try {
            categoryTables.clear()
            categoryListModel.clear()

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

                    // Attribute column — muted label style
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
                    // Answer column — input-field style with border
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
                                    com.intellij.ui.RoundedLineBorder(inputBorder, 4)
                                ),
                                JBUI.Borders.empty(0, 6)
                            )
                            return this
                        }
                    }
                }

                // Auto-fit attribute column to text width
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

            // Select first category
            if (categoryListModel.size() > 0) {
                categoryList.selectedIndex = 0
            }
        } finally {
            syncing = false
        }
    }

    // ── Detail panel ─────────────────────────────────────────────────────────

    private fun updateDetailPanel(category: String) {
        detailPanel.removeAll()
        val ct = categoryTables.find { it.category == category } ?: return

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
        }

        // Header
        content.add(JLabel(category).apply {
            font = font.deriveFont(Font.BOLD, font.size + 4f)
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
        })

        // Table in rounded wrapper
        val tableWrapper = RoundedPanel(8, JBColor.border()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            layout = BorderLayout()
            add(ct.table, BorderLayout.CENTER)
            val h = ct.tableModel.rowCount * ct.table.rowHeight +
                (ct.tableModel.rowCount - 1) // horizontal grid lines
            preferredSize = Dimension(0, h)
            maximumSize = Dimension(Int.MAX_VALUE, h)
        }
        content.add(tableWrapper)

        // Ask Copilot button
        content.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(12, 0, 0, 0)
            add(JButton("Ask Copilot \u25B7").apply {
                addActionListener { askCopilotCategory(category) }
            })
        })

        content.add(javax.swing.Box.createVerticalGlue())

        detailPanel.add(content, BorderLayout.CENTER)
        detailPanel.revalidate()
        detailPanel.repaint()
    }

    // ── List cell renderer ───────────────────────────────────────────────────

    private inner class CategoryListRenderer : JPanel(BorderLayout()), ListCellRenderer<String> {
        private val iconLabel = JLabel().apply { horizontalAlignment = SwingConstants.CENTER }
        private val nameLabel = JLabel()

        init {
            isOpaque = true
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            val iconNamePanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(iconLabel)
                add(nameLabel)
            }
            add(iconNamePanel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out String>,
            value: String,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val ct = categoryTables.find { it.category == value }
            val hasAnswers = ct != null && hasFilledAnswers(ct)

            iconLabel.text = if (hasAnswers) "\u2713" else "\u25CB"
            iconLabel.foreground = if (hasAnswers)
                JBColor(Color(0, 128, 0), Color(80, 200, 80))
            else
                JBColor.GRAY
            nameLabel.text = value
            nameLabel.foreground = if (isSelected) list.selectionForeground else list.foreground

            background = if (isSelected) list.selectionBackground else list.background

            return this
        }
    }

    private fun hasFilledAnswers(ct: CategoryTable): Boolean {
        return (0 until ct.tableModel.rowCount).any {
            ct.tableModel.getValueAt(it, 1).toString().isNotBlank()
        }
    }

    // ── File I/O ─────────────────────────────────────────────────────────────

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
                // Ensure VFS knows about the file (create on disk if first time)
                if (!ioFile.exists()) ioFile.writeText("")
                val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile) ?: return@runWriteCommandAction
                // Write through the Document model so the Copilot agent sees the same content
                val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                val doc = fdm.getDocument(vFile)
                if (doc != null) {
                    doc.setText(text)
                    fdm.saveDocument(doc)
                } else {
                    // Fallback: no Document cached yet — write to disk directly
                    vFile.setBinaryContent(text.toByteArray(Charsets.UTF_8))
                }
            } finally {
                // Clear syncing flag on the next EDT cycle so VFS events from
                // this write are still suppressed when they arrive asynchronously.
                invokeLater { syncing = false }
            }
        }
    }

    // ── Disk refresh ─────────────────────────────────────────────────────────

    private fun refreshFromDisk() {
        invokeLater {
            val fdm = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(memoryFilePath))
            if (vFile == null) return@invokeLater

            // Sync VFS with disk so we see the latest content
            vFile.refresh(false, false)

            // If the Document is stale (disk is newer), reload from disk
            val doc = fdm.getDocument(vFile)
            if (doc != null) {
                if (fdm.isDocumentUnsaved(doc)) {
                    // Copilot agent may have written via Document — flush to disk first
                    fdm.saveDocument(doc)
                } else {
                    // Disk may have been updated externally — reload the Document
                    fdm.reloadFiles(vFile)
                }
            }

            loadFromMemoryFile()
        }
    }

    // ── Copilot actions ──────────────────────────────────────────────────────

    private fun askCopilotCategory(category: String) {
        val chatService = project.getService(CopilotChatService::class.java) ?: return
        val dataContext = SimpleDataContext.getProjectContext(project)
        val prompt = "Using your tools and this project as your source of truth, " +
            "update only the \"$category\" section in the `.specify/memory/discovery.md` file " +
            "with your answers. The file uses `## Category` headings and `- Attribute = Answer` bullet lines. " +
            "Keep this exact format — do not change delimiters or structure. Example: `- Service name = order-service`. " +
            "If you cannot find concrete evidence for an attribute, leave the value empty after the `=`. " +
            "Do not write \"Unknown\" or guess."

        chatService.query(dataContext) {
            withInput(prompt)
            withAgentMode()
            withNewSession()
            onComplete { refreshFromDisk() }
            onError { _, _, _, _, _ -> }
        }
    }

    private fun askCopilotAll() {
        val chatService = project.getService(CopilotChatService::class.java) ?: return
        val dataContext = SimpleDataContext.getProjectContext(project)
        val prompt = "Using your tools and this project as your source of truth, " +
            "update the `.specify/memory/discovery.md` file " +
            "with your answers. The file uses `## Category` headings and `- Attribute = Answer` bullet lines. " +
            "Keep this exact format — do not change delimiters or structure. Example: `- Service name = order-service`. " +
            "If you cannot find concrete evidence for an attribute, leave the value empty after the `=`. " +
            "Do not write \"Unknown\" or guess."

        chatService.query(dataContext) {
            withInput(prompt)
            withAgentMode()
            withNewSession()
            onComplete { refreshFromDisk() }
            onError { _, _, _, _, _ -> }
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

        chatService.query(dataContext) {
            withInput(prompt)
            withAgentMode()
            withNewSession()
            onComplete { }
            onError { _, _, _, _, _ -> }
        }
    }

    override fun dispose() {}

    // ── Parsing ──────────────────────────────────────────────────────────────

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

            // Category: ## heading
            if (trimmed.startsWith("## ")) {
                currentCategory = trimmed.removePrefix("## ").trim()
                continue
            }

            // Attribute: - Name = Answer
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
}

// ── Rounded border panel ─────────────────────────────────────────────────────

private class RoundedPanel(
    private val radius: Int,
    private val borderColor: Color
) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = background
        g2.fillRoundRect(0, 0, width, height, radius, radius)
        super.paintComponent(g)
    }

    override fun paintBorder(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = borderColor
        g2.drawRoundRect(0, 0, width - 1, height - 1, radius, radius)
    }
}

// ── Data ─────────────────────────────────────────────────────────────────────

private class CategoryTable(
    val category: String,
    val tableModel: DefaultTableModel,
    val table: JBTable
)
