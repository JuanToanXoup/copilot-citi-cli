package com.citigroup.copilotchat.ui

import com.citigroup.copilotchat.tools.BuiltInTools
import com.citigroup.copilotchat.tools.IdeIndexBridge
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.serialization.json.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.*

/**
 * Panel showing all registered tools grouped by source (IDE Index, Built-in, MCP).
 */
class ToolsPanel : JPanel(BorderLayout()) {

    data class ToolInfo(
        val name: String,
        val description: String,
        val source: String,     // "IDE Index", "Built-in", or MCP server name
    )

    private val listModel = DefaultListModel<ToolInfo>()
    private val toolList = JList(listModel)
    private val statusLabel = JLabel("Loading tools...")
    private val json = Json { ignoreUnknownKeys = true }

    init {
        border = JBUI.Borders.empty(4)

        val header = JPanel(BorderLayout()).apply {
            add(JLabel("Registered Tools"), BorderLayout.WEST)
            add(JButton(AllIcons.Actions.Refresh).apply {
                toolTipText = "Refresh"
                addActionListener { refreshTools() }
            }, BorderLayout.EAST)
        }

        toolList.cellRenderer = ToolCellRenderer()
        toolList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        val scrollPane = JBScrollPane(toolList)
        scrollPane.preferredSize = Dimension(0, 200)

        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        refreshTools()
    }

    fun refreshTools() {
        listModel.clear()

        // IDE Index tools
        if (IdeIndexBridge.isAvailable()) {
            val schemas = IdeIndexBridge.getToolSchemas()
            for (schemaJson in schemas) {
                try {
                    val obj = json.parseToJsonElement(schemaJson).jsonObject
                    val name = obj["name"]?.jsonPrimitive?.content ?: continue
                    val desc = obj["description"]?.jsonPrimitive?.content ?: ""
                    listModel.addElement(ToolInfo(name, desc, "IDE Index"))
                } catch (_: Exception) {}
            }
        }

        // Built-in tools
        for (schemaJson in BuiltInTools.schemas) {
            try {
                val obj = json.parseToJsonElement(schemaJson).jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: continue
                val desc = obj["description"]?.jsonPrimitive?.content ?: ""
                listModel.addElement(ToolInfo(name, desc, "Built-in"))
            } catch (_: Exception) {}
        }

        statusLabel.text = "${listModel.size()} tools available"
    }

    /** Add MCP tools that were discovered from the language server. */
    fun addMcpTools(serverName: String, tools: List<Pair<String, String>>) {
        for ((name, desc) in tools) {
            listModel.addElement(ToolInfo(name, desc, "MCP: $serverName"))
        }
        statusLabel.text = "${listModel.size()} tools available"
    }

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
            text = "<html><b>${info.name}</b> <span style='color: $sourceColor'>[${info.source}]</span><br/>" +
                    "<span style='color: gray; font-size: 10px'>${info.description.take(100)}</span></html>"
            icon = when {
                info.source == "IDE Index" -> AllIcons.Nodes.Plugin
                info.source.startsWith("MCP") -> AllIcons.Nodes.Related
                else -> AllIcons.Nodes.Function
            }
            return this
        }
    }
}
