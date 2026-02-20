package com.citigroup.copilotchat.ui

import com.citigroup.copilotchat.config.CopilotChatSettings
import com.citigroup.copilotchat.tools.BuiltInTools
import com.citigroup.copilotchat.tools.IdeIndexBridge
import com.citigroup.copilotchat.tools.psi.PsiTools
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import kotlinx.serialization.json.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Panel showing all registered tools grouped by source.
 * - IDE Tools: table section showing PSI-powered tools
 * - Registered Tools: list section for Built-in, IDE Index, and MCP tools
 */
class ToolsPanel(
    private val onToolToggled: (() -> Unit)? = null,
) : JPanel(BorderLayout()) {

    data class ToolInfo(
        val name: String,
        val description: String,
        val source: String,
        var enabled: Boolean = true,
    )

    // IDE Tools table
    private val ideTableModel = IdeToolsTableModel()
    private val ideTable = JBTable(ideTableModel)

    // Other tools list
    private val listModel = DefaultListModel<ToolInfo>()
    private val toolList = JList(listModel)
    private val statusLabel = JLabel("Loading tools...")
    private val json = Json { ignoreUnknownKeys = true }

    init {
        border = JBUI.Borders.empty(4)

        // ── Header bar with refresh button ──
        val header = JPanel(BorderLayout()).apply {
            add(JLabel("Registered Tools"), BorderLayout.WEST)
            val buttons = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(JButton(AllIcons.Actions.Refresh).apply {
                    toolTipText = "Refresh"
                    addActionListener { refreshTools() }
                })
            }
            add(buttons, BorderLayout.EAST)
        }

        // ── IDE Tools table section ──
        ideTable.setShowGrid(false)
        ideTable.intercellSpacing = Dimension(0, 0)
        ideTable.rowHeight = 28
        ideTable.tableHeader.reorderingAllowed = false
        ideTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION

        ideTable.columnModel.getColumn(0).apply {
            preferredWidth = 50; maxWidth = 50; minWidth = 50
        }
        ideTable.columnModel.getColumn(1).apply {
            preferredWidth = 160
            cellRenderer = IdeToolNameRenderer()
        }
        ideTable.columnModel.getColumn(2).apply {
            preferredWidth = 300
            cellRenderer = IdeToolDescRenderer()
        }

        // Checkbox toggle via click
        ideTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val row = ideTable.rowAtPoint(e.point)
                val col = ideTable.columnAtPoint(e.point)
                if (row >= 0 && col == 0) {
                    ideTableModel.toggleRow(row)
                    onToolToggled?.invoke()
                }
            }
        })

        // IDE table as a bordered card component
        ideTable.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        val ideSection = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.empty(4, 8),
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(0xD0D0D0, 0x3C3F41), 1, true),
                    JBUI.Borders.empty(4)
                )
            )
            add(ideTable.tableHeader, BorderLayout.NORTH)
            add(ideTable, BorderLayout.CENTER)
        }

        // ── Other tools list section ──
        val listHeader = JLabel("Other Tools").apply {
            border = JBUI.Borders.empty(8, 0, 4, 0)
        }

        toolList.cellRenderer = ToolCellRenderer()
        toolList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        toolList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val idx = toolList.locationToIndex(e.point)
                if (idx >= 0) toggleToolAt(idx)
            }
        })

        // ── Combine into a single vertical content panel ──
        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            ideSection.alignmentX = Component.LEFT_ALIGNMENT
            listHeader.alignmentX = Component.LEFT_ALIGNMENT
            toolList.alignmentX = Component.LEFT_ALIGNMENT
            add(ideSection)
            add(listHeader)
            add(toolList)
        }

        val scrollPane = JBScrollPane(contentPanel).apply {
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            border = BorderFactory.createEmptyBorder()
        }

        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        refreshTools()
    }

    fun refreshTools() {
        val settings = CopilotChatSettings.getInstance()

        // IDE Tools table
        val ideTools = mutableListOf<ToolInfo>()
        try {
            for (tool in PsiTools.allTools) {
                ideTools.add(ToolInfo(tool.name, tool.description, "PSI", settings.isToolEnabled(tool.name)))
            }
        } catch (_: Exception) {}
        ideTableModel.setTools(ideTools)

        // Other tools list
        listModel.clear()

        if (IdeIndexBridge.isAvailable()) {
            val schemas = IdeIndexBridge.getToolSchemas()
            for (schemaJson in schemas) {
                try {
                    val obj = json.parseToJsonElement(schemaJson).jsonObject
                    val name = obj["name"]?.jsonPrimitive?.content ?: continue
                    val desc = obj["description"]?.jsonPrimitive?.content ?: ""
                    listModel.addElement(ToolInfo(name, desc, "IDE Index", settings.isToolEnabled(name)))
                } catch (_: Exception) {}
            }
        }

        for (schemaJson in BuiltInTools.schemas) {
            try {
                val obj = json.parseToJsonElement(schemaJson).jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: continue
                val desc = obj["description"]?.jsonPrimitive?.content ?: ""
                listModel.addElement(ToolInfo(name, desc, "Built-in", settings.isToolEnabled(name)))
            } catch (_: Exception) {}
        }

        updateStatusLabel()
    }

    fun addMcpTools(serverName: String, tools: List<Pair<String, String>>) {
        val settings = CopilotChatSettings.getInstance()
        for ((name, desc) in tools) {
            listModel.addElement(ToolInfo(name, desc, "MCP: $serverName", settings.isToolEnabled(name)))
        }
        updateStatusLabel()
    }

    fun getEnabledToolNames(): Set<String> {
        val enabled = mutableSetOf<String>()
        for (info in ideTableModel.tools) {
            if (info.enabled) enabled.add(info.name)
        }
        for (i in 0 until listModel.size()) {
            val info = listModel.getElementAt(i)
            if (info.enabled) enabled.add(info.name)
        }
        return enabled
    }

    private fun toggleToolAt(idx: Int) {
        if (idx < 0) return
        val info = listModel.getElementAt(idx)
        info.enabled = !info.enabled
        CopilotChatSettings.getInstance().setToolEnabled(info.name, info.enabled)
        toolList.repaint()
        updateStatusLabel()
        onToolToggled?.invoke()
    }

    private fun updateStatusLabel() {
        val ideEnabled = ideTableModel.tools.count { it.enabled }
        val ideTotal = ideTableModel.tools.size
        var listEnabled = 0
        for (i in 0 until listModel.size()) {
            if (listModel.getElementAt(i).enabled) listEnabled++
        }
        statusLabel.text = "${ideEnabled + listEnabled}/${ideTotal + listModel.size()} tools enabled"
    }

    // ── IDE Tools table model ──

    private inner class IdeToolsTableModel : AbstractTableModel() {
        var tools = listOf<ToolInfo>()
            private set

        fun setTools(newTools: List<ToolInfo>) {
            tools = newTools
            fireTableDataChanged()
        }

        fun toggleRow(row: Int) {
            if (row < 0 || row >= tools.size) return
            val info = tools[row]
            info.enabled = !info.enabled
            CopilotChatSettings.getInstance().setToolEnabled(info.name, info.enabled)
            fireTableCellUpdated(row, 0)
            updateStatusLabel()
        }

        override fun getRowCount() = tools.size
        override fun getColumnCount() = 3
        override fun getColumnName(column: Int) = when (column) {
            0 -> ""
            1 -> "IDE Tools"
            2 -> "Description"
            else -> ""
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            0 -> java.lang.Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 0

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val info = tools[rowIndex]
            return when (columnIndex) {
                0 -> info.enabled
                1 -> info.name
                2 -> info.description
                else -> ""
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex == 0 && aValue is Boolean) {
                val info = tools[rowIndex]
                info.enabled = aValue
                CopilotChatSettings.getInstance().setToolEnabled(info.name, info.enabled)
                fireTableCellUpdated(rowIndex, 0)
                updateStatusLabel()
                onToolToggled?.invoke()
            }
        }
    }

    // ── Table cell renderers ──

    private class IdeToolNameRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            foreground = JBColor(0x0366D6, 0x58A6FF)
            text = (value as? String)?.removePrefix("ide_") ?: ""
            border = JBUI.Borders.empty(0, 4)
            return this
        }
    }

    private class IdeToolDescRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            foreground = JBColor(0x666666, 0x999999)
            val full = (value as? String) ?: ""
            text = full
            toolTipText = if (full.length > 80) "<html><body style='width:300px'>$full</body></html>" else full
            border = JBUI.Borders.empty(0, 4)
            return this
        }
    }

    // ── Other tools list renderer ──

    private class ToolCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val info = value as? ToolInfo ?: return this
            val sourceColor = when {
                info.source == "IDE Index" -> "#4CAF50"
                info.source == "Built-in" -> "#2196F3"
                info.source.startsWith("MCP") -> "#FF9800"
                else -> "#999999"
            }
            val nameStyle = if (info.enabled) "" else "text-decoration: line-through; color: gray"
            val statusIcon = if (info.enabled) "ON" else "OFF"
            val statusColor = if (info.enabled) "#4CAF50" else "#999999"
            text = "<html><b style='$nameStyle'>${info.name}</b> " +
                    "<span style='color: $sourceColor'>[${info.source}]</span> " +
                    "<span style='color: $statusColor; font-size: 9px'>$statusIcon</span><br/>" +
                    "<span style='color: gray; font-size: 10px'>${info.description}</span></html>"
            toolTipText = if (info.description.length > 100) "<html><body style='width:300px'>${info.description}</body></html>" else info.description
            icon = null
            return this
        }
    }
}
