package com.citigroup.copilotchat.ui

import com.citigroup.copilotchat.config.CopilotChatSettings
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
 * Each tool can be individually enabled/disabled via checkbox.
 */
class ToolsPanel : JPanel(BorderLayout()) {

    data class ToolInfo(
        val name: String,
        val description: String,
        val source: String,     // "IDE Index", "Built-in", or MCP server name
        var enabled: Boolean = true,
    )

    private val listModel = DefaultListModel<ToolInfo>()
    private val toolList = JList(listModel)
    private val statusLabel = JLabel("Loading tools...")
    private val json = Json { ignoreUnknownKeys = true }

    init {
        border = JBUI.Borders.empty(4)

        val header = JPanel(BorderLayout()).apply {
            add(JLabel("Registered Tools"), BorderLayout.WEST)
            val buttons = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(JButton(AllIcons.Actions.Refresh).apply {
                    toolTipText = "Refresh"
                    addActionListener { refreshTools() }
                })
                add(JButton(AllIcons.Actions.ToggleVisibility).apply {
                    toolTipText = "Enable/Disable Selected"
                    addActionListener { toggleToolAt(toolList.selectedIndex) }
                })
            }
            add(buttons, BorderLayout.EAST)
        }

        toolList.cellRenderer = ToolCellRenderer()
        toolList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        // Click to toggle enabled/disabled
        toolList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val idx = toolList.locationToIndex(e.point)
                if (idx >= 0) toggleToolAt(idx)
            }
        })

        val scrollPane = JBScrollPane(toolList)
        scrollPane.preferredSize = Dimension(0, 200)

        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        refreshTools()
    }

    fun refreshTools() {
        listModel.clear()
        val settings = CopilotChatSettings.getInstance()

        // IDE Index tools
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

        // Built-in tools
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

    /** Add MCP tools that were discovered from the language server. */
    fun addMcpTools(serverName: String, tools: List<Pair<String, String>>) {
        val settings = CopilotChatSettings.getInstance()
        for ((name, desc) in tools) {
            listModel.addElement(ToolInfo(name, desc, "MCP: $serverName", settings.isToolEnabled(name)))
        }
        updateStatusLabel()
    }

    /** Get list of tool names that are currently enabled. */
    fun getEnabledToolNames(): Set<String> {
        val enabled = mutableSetOf<String>()
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
    }

    private fun updateStatusLabel() {
        val total = listModel.size()
        var enabled = 0
        for (i in 0 until total) {
            if (listModel.getElementAt(i).enabled) enabled++
        }
        statusLabel.text = "$enabled/$total tools enabled"
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
            val nameStyle = if (info.enabled) "" else "text-decoration: line-through; color: gray"
            val statusIcon = if (info.enabled) "ON" else "OFF"
            val statusColor = if (info.enabled) "#4CAF50" else "#999999"
            text = "<html><b style='$nameStyle'>${info.name}</b> " +
                    "<span style='color: $sourceColor'>[${info.source}]</span> " +
                    "<span style='color: $statusColor; font-size: 9px'>$statusIcon</span><br/>" +
                    "<span style='color: gray; font-size: 10px'>${info.description.take(100)}</span></html>"
            icon = when {
                !info.enabled -> AllIcons.Actions.Cancel
                info.source == "IDE Index" -> AllIcons.Nodes.Plugin
                info.source.startsWith("MCP") -> AllIcons.Nodes.Related
                else -> AllIcons.Nodes.Function
            }
            return this
        }
    }
}
