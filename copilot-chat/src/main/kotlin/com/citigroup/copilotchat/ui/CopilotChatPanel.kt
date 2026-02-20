package com.citigroup.copilotchat.ui

import com.citigroup.copilotchat.config.CopilotChatSettings
import com.citigroup.copilotchat.conversation.ChatEvent
import com.citigroup.copilotchat.conversation.ConversationManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.JsonObject
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * Main chat panel matching the official GitHub Copilot Chat UI.
 */
class CopilotChatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val conversationManager = ConversationManager.getInstance(project)
    private val messageRenderer = MessageRenderer()

    // Messages area — 20px left/right padding matching official
    private val messagesPanel = VerticalStackPanel().apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 20)
    }
    private val messagesScrollPane = JBScrollPane(messagesPanel).apply {
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        border = BorderFactory.createEmptyBorder()
        isOpaque = false
        viewport.isOpaque = false
    }

    private val scrollManager = StickyScrollManager(messagesScrollPane, messagesPanel)

    private val modelSelector = ModelSelector(project)
    private val inputPanel = ChatInputPanel(
        onSend = { text -> sendMessage(text) },
        onStop = { conversationManager.cancel() },
    )

    private var currentAssistantMessage: AssistantMessageComponent? = null

    // Header title
    private val titleLabel = JLabel("New Conversation").apply {
        foreground = JBColor(0xBBBBBB, 0x999999)
        border = JBUI.Borders.empty(0, 8, 0, 0)
    }

    private var mcpConfigPanel: McpConfigPanel? = null

    init {
        isOpaque = false
        inputPanel.modelSelector = modelSelector

        // Bottom panel
        val bottomPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(inputPanel, BorderLayout.CENTER)
        }

        // OnePixelSplitter: messages top (0.8), input bottom (0.2)
        val splitter = OnePixelSplitter(true, "copilot.chat.splitter", 0.8f).apply {
            firstComponent = messagesScrollPane
            secondComponent = bottomPanel
            setHonorComponentsMinimumSize(true)
        }

        // Header bar with separator line below (matching official)
        val headerBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor(0xD0D0D0, 0x3C3F41)),
                JBUI.Borders.empty(6, 8, 6, 4)
            )
            add(titleLabel, BorderLayout.CENTER)

            val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(createIconButton(AllIcons.General.GearPlain, "Proxy Settings") { showProxyDialog() })
                add(createIconButton(AllIcons.Vcs.History, "History"))
                add(createIconButton(AllIcons.General.Add, "New Conversation") { newConversation() })
            }
            add(actionsPanel, BorderLayout.EAST)
        }

        add(headerBar, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        // Observe conversation events
        scope.launch {
            conversationManager.events.collectLatest { event -> handleEvent(event) }
        }

        // Block input until LSP + MCP startup completes
        inputPanel.isInitializing = true
        scope.launch(Dispatchers.IO) {
            try {
                conversationManager.ensureInitialized()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addErrorMessage("Startup failed: ${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    inputPanel.isInitializing = false
                }
            }
        }
    }

    private fun createIconButton(icon: Icon, tooltip: String, action: (() -> Unit)? = null): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            margin = JBUI.insets(2)
            preferredSize = java.awt.Dimension(28, 28)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            if (action != null) addActionListener { action() }
        }
    }

    private fun sendMessage(text: String) {
        val truncated = if (text.length > 40) text.take(40) + "..." else text
        titleLabel.text = truncated

        // User message (right-aligned pill) — 20px top gap between groups
        addGroupSpacing()
        addMessageComponent(UserMessageComponent(text))
        inputPanel.isStreaming = true

        conversationManager.updateAgentMode(inputPanel.isAgentMode)
        conversationManager.sendMessage(text)

        // Assistant message — 8px gap from user pill
        addItemSpacing()
        val pane = messageRenderer.createMessagePane()
        val assistantMsg = AssistantMessageComponent(pane, messageRenderer)
        addMessageComponent(assistantMsg)
        currentAssistantMessage = assistantMsg

        scrollManager.forceSticky()
    }

    private fun handleEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.Delta -> {
                currentAssistantMessage?.appendText(event.text)
                scrollManager.onContentAdded()
            }
            is ChatEvent.ToolCall -> {
                // "Close" the current assistant message so tool call appears in order.
                // A new assistant message will be created when more text arrives.
                currentAssistantMessage = null
                addToolCallMessage(event.name, event.input)
            }
            is ChatEvent.ToolResult -> {
                addToolResultMessage(event.name, event.output)
            }
            is ChatEvent.AgentRound -> {
                if (event.reply.isNotEmpty()) {
                    // Create a fresh assistant message for each round's text,
                    // so it appears after any tool calls that preceded it
                    val msg = currentAssistantMessage
                    if (msg != null && msg.getText().isEmpty()) {
                        // Reuse empty assistant message (just created, no text yet)
                        msg.appendText(event.reply)
                    } else {
                        // Create new assistant message after tool calls
                        addItemSpacing()
                        val pane = messageRenderer.createMessagePane()
                        val newMsg = AssistantMessageComponent(pane, messageRenderer)
                        addMessageComponent(newMsg)
                        currentAssistantMessage = newMsg
                        newMsg.appendText(event.reply)
                    }
                    scrollManager.onContentAdded()
                }
            }
            is ChatEvent.Done -> {
                inputPanel.isStreaming = false
                currentAssistantMessage = null
                scrollManager.onContentAdded()
            }
            is ChatEvent.Error -> {
                inputPanel.isStreaming = false
                currentAssistantMessage = null
                addErrorMessage(event.message)
            }
        }
    }

    fun onToolToggled() {
        conversationManager.reregisterTools()
    }

    fun onMcpConfigChanged() {
        scope.launch {
            try {
                val panel = mcpConfigPanel ?: return@launch
                conversationManager.configureMcp(panel.buildMcpConfig())
            } catch (e: Exception) {
                addErrorMessage("MCP config error: ${e.message}")
            }
        }
    }

    fun setMcpConfigPanel(panel: McpConfigPanel) {
        mcpConfigPanel = panel
    }

    private fun addToolCallMessage(toolName: String, input: JsonObject) {
        addMessageComponent(ToolCallPanel(toolName, input))
    }

    private fun addToolResultMessage(toolName: String, output: String) {
        if (output.isBlank()) return
        val color = colorToHex(JBColor(0x666666, 0x999999))
        val label = JLabel(
            "<html><span style='color: $color'>  \u2514 ${escapeHtml(output.take(120))}${if (output.length > 120) "..." else ""}</span></html>"
        )
        label.border = JBUI.Borders.empty(0, 28, 2, 8)
        addMessageComponent(label)
    }

    private fun addErrorMessage(message: String) {
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
        }
        val icon = JLabel(AllIcons.General.Error).apply {
            border = JBUI.Borders.empty(0, 0, 0, 6)
        }
        val label = JLabel("<html><span style='color: ${colorToHex(JBColor.RED)}'>${escapeHtml(message)}</span></html>")
        panel.add(icon, BorderLayout.WEST)
        panel.add(label, BorderLayout.CENTER)
        addMessageComponent(panel)
    }

    /** 20px gap between message groups (user+response pairs) */
    private fun addGroupSpacing() {
        messagesPanel.add(Box.createVerticalStrut(20))
    }

    /** 8px gap between items within a group (user pill → response) */
    private fun addItemSpacing() {
        messagesPanel.add(Box.createVerticalStrut(8))
    }

    private fun addMessageComponent(component: JComponent) {
        messagesPanel.add(component)
        messagesPanel.revalidate()
        messagesPanel.repaint()
        scrollManager.onContentAdded()
    }

    private fun showProxyDialog() {
        val settings = CopilotChatSettings.getInstance()
        val dialog = ProxyDialog(settings.proxyUrl)
        if (dialog.showAndGet()) {
            settings.proxyUrl = dialog.proxyUrl
        }
    }

    private fun newConversation() {
        conversationManager.newConversation()
        messagesPanel.removeAll()
        messagesPanel.revalidate()
        messagesPanel.repaint()
        currentAssistantMessage = null
        titleLabel.text = "New Conversation"
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun colorToHex(color: java.awt.Color): String =
        String.format("#%02x%02x%02x", color.red, color.green, color.blue)

    override fun dispose() {
        scope.cancel()
    }
}

private class ProxyDialog(
    currentUrl: String,
) : DialogWrapper(true) {

    private val urlField = JBTextField(currentUrl).apply {
        columns = 35
    }

    val proxyUrl: String get() = urlField.text.trim()

    init {
        title = "Proxy Settings"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel.add(JLabel("Proxy URL:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(urlField, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2
        panel.add(JLabel("<html><i style='color: gray'>e.g. http://proxy.corp:8080 — leave empty for direct connection.</i></html>"), gbc)

        return panel
    }
}
