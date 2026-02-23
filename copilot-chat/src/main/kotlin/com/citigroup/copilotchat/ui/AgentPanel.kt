package com.citigroup.copilotchat.ui

import com.citigroup.copilotchat.agent.AgentEvent
import com.citigroup.copilotchat.agent.AgentService
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*

/**
 * Agent tab panel implementing Claude Code's architecture patterns:
 * lead agent with delegate_task for subagent spawning, team communication.
 *
 * Same layout pattern as OrchestratorPanel: header + splitter (messages/input).
 */
class AgentPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val agentService = AgentService.getInstance(project)
    private val messageRenderer = MessageRenderer()

    // Messages area
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

    private val inputPanel: ChatInputPanel = ChatInputPanel(
        onSend = { text -> sendMessage(text) },
        onStop = { cancel() },
    ).apply {
        showAgentDropdown = false
        isInitializing = false
    }

    // Current lead assistant message (for streaming appends)
    private var currentAssistantMessage: AssistantMessageComponent? = null

    // Active subagent panels, keyed by agentId
    private val subagentPanels = mutableMapOf<String, SubagentPanelState>()

    // Last tool call panel for grouping
    private var lastToolCallPanel: ToolCallPanel? = null

    private val titleLabel = JLabel("Agent").apply {
        foreground = JBColor(0xBBBBBB, 0x999999)
        border = JBUI.Borders.empty(0, 8, 0, 0)
    }

    init {
        isOpaque = false

        val bottomPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(inputPanel, BorderLayout.CENTER)
        }

        val splitter = OnePixelSplitter(true, "copilot.agent.splitter", 0.8f).apply {
            firstComponent = messagesScrollPane
            secondComponent = bottomPanel
            setHonorComponentsMinimumSize(true)
        }

        // Header bar
        val headerBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor(0xD0D0D0, 0x3C3F41)),
                JBUI.Borders.empty(6, 8, 6, 4)
            )
            add(titleLabel, BorderLayout.CENTER)

            val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(createIconButton(AllIcons.General.Add, "New Conversation") { newConversation() })
            }
            add(actionsPanel, BorderLayout.EAST)
        }

        add(headerBar, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        // Observe agent events
        scope.launch {
            agentService.events.collect { event -> handleEvent(event) }
        }
    }

    private fun createIconButton(icon: Icon, tooltip: String, action: () -> Unit): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            margin = JBUI.insets(2)
            preferredSize = Dimension(28, 28)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { action() }
        }
    }

    private fun sendMessage(text: String) {
        if (agentService.isStreaming) return

        val truncated = if (text.length > 40) text.take(40) + "..." else text
        titleLabel.text = truncated

        // User message bubble
        addGroupSpacing()
        addMessageComponent(UserMessageComponent(text))
        inputPanel.isStreaming = true

        // Reset streaming state
        currentAssistantMessage = null
        lastToolCallPanel = null

        agentService.sendMessage(text)
        scrollManager.forceSticky()
    }

    private fun cancel() {
        agentService.cancel()
        inputPanel.isStreaming = false
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.LeadDelta -> {
                val msg = getOrCreateLeadMessage()
                msg.appendText(event.text)
                scrollManager.onContentAdded()
            }

            is AgentEvent.LeadToolCall -> {
                // Group consecutive calls to the same tool
                val existingPanel = lastToolCallPanel
                if (existingPanel != null && existingPanel.toolName == event.name) {
                    existingPanel.addAction(event.input)
                } else {
                    // New tool call — break the current assistant message so it appears before the tool
                    currentAssistantMessage = null
                    addItemSpacing()
                    val panel = ToolCallPanel(event.name, event.input)
                    lastToolCallPanel = panel
                    addMessageComponent(panel)
                }
                scrollManager.onContentAdded()
            }

            is AgentEvent.LeadToolResult -> {
                // Tool result ends a tool call group
                lastToolCallPanel = null
            }

            is AgentEvent.SubagentSpawned -> {
                // Break current assistant message
                currentAssistantMessage = null
                lastToolCallPanel = null

                addItemSpacing()

                // Create collapsible panel for the subagent
                val headerLabel = JLabel("[${event.agentType}] ${event.description}").apply {
                    foreground = JBColor(0x0366D6, 0x58A6FF)
                    border = JBUI.Borders.empty(2, 4)
                }
                val collapsible = CollapsiblePanel(headerLabel, initiallyExpanded = true)
                val contentPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                }

                // Show prompt if available
                if (event.prompt.isNotBlank()) {
                    val promptLabel = JLabel("PROMPT").apply {
                        foreground = JBColor(0x999999, 0x666666)
                        font = font.deriveFont(font.size2D - 2f)
                        border = JBUI.Borders.empty(2, 4, 2, 4)
                    }
                    contentPanel.add(promptLabel)
                    val promptPane = messageRenderer.createMessagePane()
                    promptPane.text = event.prompt
                    promptPane.foreground = JBColor(0x333333, 0xCCCCCC)
                    val promptWrapper = JPanel(BorderLayout()).apply {
                        isOpaque = true
                        background = JBColor(0xF6F8FA, 0x1C1C1E)
                        border = JBUI.Borders.empty(4, 6)
                        add(promptPane, BorderLayout.CENTER)
                    }
                    contentPanel.add(promptWrapper)
                    contentPanel.add(Box.createVerticalStrut(6))
                    val replyLabel = JLabel("REPLY").apply {
                        foreground = JBColor(0x999999, 0x666666)
                        font = font.deriveFont(font.size2D - 2f)
                        border = JBUI.Borders.empty(2, 4, 2, 4)
                    }
                    contentPanel.add(replyLabel)
                }

                val replyPane = messageRenderer.createMessagePane()
                val assistantMsg = AssistantMessageComponent(replyPane)
                contentPanel.add(assistantMsg)
                collapsible.getContent().add(contentPanel)

                subagentPanels[event.agentId] = SubagentPanelState(collapsible, assistantMsg, headerLabel)
                addMessageComponent(collapsible)
                scrollManager.onContentAdded()
            }

            is AgentEvent.SubagentDelta -> {
                val state = subagentPanels[event.agentId] ?: return
                state.assistantMessage.appendText(event.text)
                scrollManager.onContentAdded()
            }

            is AgentEvent.SubagentToolCall -> {
                // Could show tool calls within subagent panel if desired
            }

            is AgentEvent.SubagentCompleted -> {
                val state = subagentPanels[event.agentId]
                if (state != null) {
                    // If deltas didn't stream any content, set the full result
                    // so it's available when the user expands the panel
                    if (event.result.isNotBlank() && !state.assistantMessage.hasContent()) {
                        state.assistantMessage.appendText(event.result)
                    }
                    // Update header to show completion status
                    val statusIcon = if (event.status == "success") "\u2713" else "\u2717"
                    val currentText = state.headerLabel.text
                    state.headerLabel.text = "$statusIcon $currentText"
                    state.headerLabel.foreground = if (event.status == "success")
                        JBColor(0x28A745, 0x3FB950)
                    else
                        JBColor(0xCB2431, 0xF85149)
                    // Collapse — user can expand to see full output
                    state.collapsible.collapse()
                }
                subagentPanels.remove(event.agentId)
                scrollManager.onContentAdded()
            }

            is AgentEvent.LeadDone -> {
                // Safety net: if we have full text but no assistant message was displayed,
                // show it now. This handles cases where LeadDelta events were not emitted
                // (e.g., reply text only arrived in editAgentRounds or final progress event).
                if (event.fullText.isNotBlank() && currentAssistantMessage == null) {
                    addItemSpacing()
                    val pane = messageRenderer.createMessagePane()
                    val component = AssistantMessageComponent(pane)
                    addMessageComponent(component)
                    component.appendText(event.fullText)
                }
                currentAssistantMessage = null
                lastToolCallPanel = null
                inputPanel.isStreaming = false
                scrollManager.onContentAdded()
            }

            is AgentEvent.LeadError -> {
                currentAssistantMessage = null
                lastToolCallPanel = null
                inputPanel.isStreaming = false
                addItemSpacing()
                addErrorMessage(event.message)
            }

            // Team events
            is AgentEvent.TeamCreated -> {
                addItemSpacing()
                addStatusLine("Team '${event.teamName}' created")
            }

            is AgentEvent.TeammateJoined -> {
                addItemSpacing()
                addStatusLine("${event.name} (${event.agentType}) joined the team")
            }

            is AgentEvent.TeammateIdle -> {
                addStatusLine("${event.name} is idle")
            }

            is AgentEvent.TeammateResumed -> {
                addStatusLine("${event.name} resumed")
            }

            is AgentEvent.MailboxMessageEvent -> {
                addStatusLine("${event.from} -> ${event.to}: ${event.summary}")
            }

            is AgentEvent.TeamDisbanded -> {
                addItemSpacing()
                addStatusLine("Team '${event.teamName}' disbanded")
            }
        }
    }

    private fun getOrCreateLeadMessage(): AssistantMessageComponent {
        val existing = currentAssistantMessage
        if (existing != null) return existing

        addItemSpacing()
        val pane = messageRenderer.createMessagePane()
        val component = AssistantMessageComponent(pane)
        addMessageComponent(component)
        currentAssistantMessage = component
        return component
    }

    private fun addStatusLine(text: String) {
        val color = colorToHex(JBColor(0x666666, 0x999999))
        val label = JLabel(
            "<html><span style='color: $color'>${escapeHtml(text)}</span></html>"
        )
        label.border = JBUI.Borders.empty(2, 4)
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

    private fun addGroupSpacing() {
        messagesPanel.add(Box.createVerticalStrut(20))
    }

    private fun addItemSpacing() {
        messagesPanel.add(Box.createVerticalStrut(8))
    }

    private fun addMessageComponent(component: JComponent) {
        messagesPanel.add(component)
        messagesPanel.revalidate()
        messagesPanel.repaint()
        scrollManager.onContentAdded()
    }

    private fun newConversation() {
        agentService.newConversation()
        messagesPanel.removeAll()
        messagesPanel.revalidate()
        messagesPanel.repaint()
        currentAssistantMessage = null
        lastToolCallPanel = null
        subagentPanels.clear()
        inputPanel.isStreaming = false
        titleLabel.text = "Agent"
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun colorToHex(color: java.awt.Color): String =
        String.format("#%02x%02x%02x", color.red, color.green, color.blue)

    override fun dispose() {
        scope.cancel()
    }

    /** Internal state for a subagent's UI panel. */
    private data class SubagentPanelState(
        val collapsible: CollapsiblePanel,
        val assistantMessage: AssistantMessageComponent,
        val headerLabel: JLabel,
    )
}
