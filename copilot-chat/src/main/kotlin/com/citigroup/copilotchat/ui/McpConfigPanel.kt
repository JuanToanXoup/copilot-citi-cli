package com.citigroup.copilotchat.ui

import com.citigroup.copilotchat.config.CopilotChatSettings
import com.citigroup.copilotchat.config.CopilotChatSettings.McpServerEntry
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * Panel for configuring MCP servers (e.g., Playwright, custom tools).
 * Shows a list of configured servers with add/edit/remove/toggle.
 */
class McpConfigPanel(
    private val onConfigChanged: () -> Unit,
) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<McpServerEntry>()
    private val serverList = JList(listModel)

    init {
        border = JBUI.Borders.empty(4)

        // Header
        val header = JPanel(BorderLayout()).apply {
            add(JLabel("MCP Servers"), BorderLayout.WEST)
            val buttons = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(JButton(AllIcons.General.Add).apply {
                    toolTipText = "Add MCP Server"
                    addActionListener { addServer() }
                })
                add(JButton(AllIcons.General.Remove).apply {
                    toolTipText = "Remove Selected"
                    addActionListener { removeSelected() }
                })
                add(JButton(AllIcons.Actions.ToggleVisibility).apply {
                    toolTipText = "Enable/Disable Selected"
                    addActionListener { toggleSelected() }
                })
            }
            add(buttons, BorderLayout.EAST)
        }

        serverList.cellRenderer = McpServerCellRenderer()
        serverList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        // Double-click to edit
        serverList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) editSelected()
            }
        })

        val scrollPane = JBScrollPane(serverList)
        scrollPane.preferredSize = Dimension(0, 150)

        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        loadFromSettings()
    }

    private fun loadFromSettings() {
        CopilotChatSettings.getInstance().ensureDefaults()
        listModel.clear()
        CopilotChatSettings.getInstance().mcpServers.forEach { listModel.addElement(it) }
    }

    private fun saveToSettings() {
        val entries = mutableListOf<McpServerEntry>()
        for (i in 0 until listModel.size()) {
            entries.add(listModel.getElementAt(i))
        }
        CopilotChatSettings.getInstance().mcpServers = entries
        onConfigChanged()
    }

    private fun addServer() {
        val dialog = McpServerDialog(null)
        if (dialog.showAndGet()) {
            listModel.addElement(dialog.result!!)
            saveToSettings()
        }
    }

    private fun editSelected() {
        val idx = serverList.selectedIndex
        if (idx < 0) return
        val entry = listModel.getElementAt(idx)
        val dialog = McpServerDialog(entry)
        if (dialog.showAndGet()) {
            listModel.set(idx, dialog.result!!)
            saveToSettings()
        }
    }

    private fun toggleSelected() {
        val idx = serverList.selectedIndex
        if (idx < 0) return
        val entry = listModel.getElementAt(idx)
        entry.enabled = !entry.enabled
        serverList.repaint()
        saveToSettings()
    }

    private fun removeSelected() {
        val idx = serverList.selectedIndex
        if (idx < 0) return
        val entry = listModel.getElementAt(idx)
        if (Messages.showYesNoDialog(
                "Remove MCP server '${entry.name}'?",
                "Remove Server",
                Messages.getQuestionIcon()
            ) == Messages.YES
        ) {
            listModel.remove(idx)
            saveToSettings()
        }
    }

    /** Build MCP config map for sending to language server. */
    fun buildMcpConfig(): Map<String, Map<String, Any>> {
        val config = mutableMapOf<String, Map<String, Any>>()
        for (i in 0 until listModel.size()) {
            val entry = listModel.getElementAt(i)
            if (!entry.enabled) continue
            val serverConfig = mutableMapOf<String, Any>()
            if (entry.url.isNotBlank()) {
                serverConfig["url"] = entry.url
            } else {
                serverConfig["command"] = entry.command
                if (entry.args.isNotBlank()) {
                    serverConfig["args"] = entry.args.split(" ").filter { it.isNotBlank() }
                }
            }
            if (entry.env.isNotBlank()) {
                val envMap = mutableMapOf<String, String>()
                entry.env.lines().filter { "=" in it }.forEach { line ->
                    val (k, v) = line.split("=", limit = 2)
                    envMap[k.trim()] = v.trim()
                }
                if (envMap.isNotEmpty()) serverConfig["env"] = envMap
            }
            config[entry.name] = serverConfig
        }
        return config
    }

    private class McpServerCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val entry = value as? McpServerEntry ?: return this
            val transport = if (entry.url.isNotBlank()) "SSE" else "stdio"
            val status = if (entry.enabled) "on" else "off"
            text = "<html><b>${entry.name}</b> <span style='color: gray'>[$transport] ($status)</span></html>"
            icon = AllIcons.Nodes.Plugin
            return this
        }
    }
}

/**
 * Dialog for adding/editing an MCP server configuration.
 */
class McpServerDialog(private val existing: McpServerEntry?) : DialogWrapper(true) {
    var result: McpServerEntry? = null
        private set

    private val nameField = JBTextField(existing?.name ?: "")
    private val commandField = JBTextField(existing?.command ?: "")
    private val argsField = JBTextField(existing?.args ?: "")
    private val urlField = JBTextField(existing?.url ?: "")
    private val envArea = JTextArea(existing?.env ?: "", 3, 30)
    private val enabledCheckbox = JCheckBox("Enabled", existing?.enabled ?: true)

    init {
        title = if (existing != null) "Edit MCP Server" else "Add MCP Server"
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

        addRow("Name:", nameField)
        addRow("Command:", commandField)
        addRow("Args:", argsField)

        // Separator
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2
        panel.add(JSeparator(), gbc)
        gbc.gridwidth = 1
        row++

        addRow("SSE URL:", urlField)

        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JLabel("Env:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        val envScroll = JBScrollPane(envArea)
        envScroll.preferredSize = Dimension(300, 60)
        panel.add(envScroll, gbc)
        row++

        gbc.gridx = 1; gbc.gridy = row
        panel.add(enabledCheckbox, gbc)

        // Hint
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2
        val hint = JLabel("<html><i style='color: gray'>For stdio: set Command + Args (e.g. npx @playwright/mcp@latest)<br/>For SSE: set URL only</i></html>")
        panel.add(hint, gbc)

        return panel
    }

    override fun doOKAction() {
        if (nameField.text.isBlank()) {
            Messages.showErrorDialog("Server name is required.", "Validation Error")
            return
        }
        if (commandField.text.isBlank() && urlField.text.isBlank()) {
            Messages.showErrorDialog("Either Command or SSE URL is required.", "Validation Error")
            return
        }
        result = McpServerEntry(
            name = nameField.text.trim(),
            command = commandField.text.trim(),
            args = argsField.text.trim(),
            env = envArea.text.trim(),
            url = urlField.text.trim(),
            enabled = enabledCheckbox.isSelected,
        )
        super.doOKAction()
    }
}
