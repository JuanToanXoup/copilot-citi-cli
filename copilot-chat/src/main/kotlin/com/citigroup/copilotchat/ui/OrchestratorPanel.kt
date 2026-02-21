package com.citigroup.copilotchat.ui

import com.citigroup.copilotchat.orchestrator.OrchestratorEvent
import com.citigroup.copilotchat.orchestrator.OrchestratorService
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
 * Chat-style orchestrator panel. Mirrors CopilotChatPanel layout but renders
 * OrchestratorEvents instead of ChatEvents. The orchestrator's plan, worker
 * activity, and summary all appear as chat messages in the conversation flow.
 */
class OrchestratorPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val orchestratorService = OrchestratorService.getInstance(project)
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
        onSend = { text -> startOrchestration(text) },
        onStop = { stopOrchestration() },
    ).apply {
        showAgentDropdown = false
        isInitializing = false
    }

    // Per-worker streaming message components, keyed by worker role
    private val workerMessages = mutableMapOf<String, AssistantMessageComponent>()

    // True when supervisor is waiting for user input
    private var waitingForUserResponse = false

    // Header title
    private val titleLabel = JLabel("Orchestrator").apply {
        foreground = JBColor(0xBBBBBB, 0x999999)
        border = JBUI.Borders.empty(0, 8, 0, 0)
    }

    init {
        isOpaque = false

        val bottomPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(inputPanel, BorderLayout.CENTER)
        }

        val splitter = OnePixelSplitter(true, "copilot.orchestrator.splitter", 0.8f).apply {
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

        // Observe orchestrator events
        scope.launch {
            orchestratorService.events.collect { event -> handleEvent(event) }
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

    private fun stopOrchestration() {
        orchestratorService.cancel()
        inputPanel.isStreaming = false
        waitingForUserResponse = false
    }

    private fun startOrchestration(goal: String) {
        // When waiting for user response, route input to supervisor instead of starting new run
        if (waitingForUserResponse) {
            orchestratorService.respond(goal)
            return
        }

        if (orchestratorService.isRunning) return

        val truncated = if (goal.length > 40) goal.take(40) + "..." else goal
        titleLabel.text = truncated

        // User message bubble
        addGroupSpacing()
        addMessageComponent(UserMessageComponent(goal))
        inputPanel.isStreaming = true
        workerMessages.clear()

        orchestratorService.run(goal)
        scrollManager.forceSticky()
    }

    private fun handleEvent(event: OrchestratorEvent) {
        when (event) {
            is OrchestratorEvent.PlanStarted -> {
                addItemSpacing()
                addAssistantMessage("Planning...")
            }

            is OrchestratorEvent.WorkersGenerated -> {
                val names = event.workers.joinToString(", ") { it.role }
                addItemSpacing()
                addAssistantMessage("Deploying workers: $names")
            }

            is OrchestratorEvent.PlanCompleted -> {
                val taskList = event.tasks.joinToString("\n") { task ->
                    val deps = if (task.dependsOn.isEmpty()) "" else " (after: ${task.dependsOn.joinToString(", ")})"
                    "${task.index + 1}. [${task.workerRole}] ${task.task}$deps"
                }
                addItemSpacing()
                addAssistantMessage("Task plan:\n$taskList")
            }

            is OrchestratorEvent.TaskAssigned -> {
                addItemSpacing()
                addStatusLine("[${event.task.workerRole}] Starting: ${event.task.task}")
            }

            is OrchestratorEvent.TaskProgress -> {
                val msg = workerMessages.getOrPut(event.workerRole) {
                    addItemSpacing()
                    val pane = messageRenderer.createMessagePane()
                    val component = AssistantMessageComponent(pane)
                    addMessageComponent(component)
                    // Add a label prefix so user knows which worker is streaming
                    component.appendText("[${event.workerRole}] ")
                    component
                }
                msg.appendText(event.text)
                scrollManager.onContentAdded()
            }

            is OrchestratorEvent.TaskCompleted -> {
                // Remove from active streaming map so next task for same role gets a new bubble
                workerMessages.remove(event.result.workerRole)
                val statusIcon = if (event.result.status == "success") "Done" else "Failed"
                addItemSpacing()
                addStatusLine("[${event.result.workerRole}] $statusIcon")
            }

            is OrchestratorEvent.TaskSkipped -> {
                addItemSpacing()
                addStatusLine("[${event.task.workerRole}] Skipped (dependency failure)")
            }

            is OrchestratorEvent.Deadlock -> {
                addItemSpacing()
                addErrorMessage("DAG deadlock: pending tasks ${event.pending}, completed ${event.completed}")
            }

            is OrchestratorEvent.SupervisorEvaluating -> {
                addItemSpacing()
                addStatusLine("Supervisor evaluating (iteration ${event.iteration})...")
            }

            is OrchestratorEvent.SupervisorVerdictEvent -> {
                addItemSpacing()
                addAssistantMessage("**${event.decision}**: ${event.reasoning}")
            }

            is OrchestratorEvent.FollowUpPlanned -> {
                val taskList = event.tasks.joinToString("\n") { task ->
                    val deps = if (task.dependsOn.isEmpty()) "" else " (after: ${task.dependsOn.joinToString(", ")})"
                    "${task.index + 1}. [${task.workerRole}] ${task.task}$deps"
                }
                addItemSpacing()
                addAssistantMessage("Follow-up tasks:\n$taskList")
            }

            is OrchestratorEvent.WaitingForUser -> {
                addItemSpacing()
                addAssistantMessage(event.question)
                inputPanel.isStreaming = false
                waitingForUserResponse = true
            }

            is OrchestratorEvent.UserResponded -> {
                addGroupSpacing()
                addMessageComponent(UserMessageComponent(event.response))
                inputPanel.isStreaming = true
                waitingForUserResponse = false
                scrollManager.forceSticky()
            }

            is OrchestratorEvent.SummarizeStarted -> {
                workerMessages.clear()
                addItemSpacing()
                addAssistantMessage("Generating summary...")
            }

            is OrchestratorEvent.SummarizeCompleted -> {
                addItemSpacing()
                addAssistantMessage(event.summary)
            }

            is OrchestratorEvent.Finished -> {
                workerMessages.clear()
                inputPanel.isStreaming = false
                scrollManager.onContentAdded()
            }

            is OrchestratorEvent.Error -> {
                workerMessages.clear()
                inputPanel.isStreaming = false
                addItemSpacing()
                addErrorMessage(event.message)
            }
        }
    }

    private fun addAssistantMessage(text: String) {
        val pane = messageRenderer.createMessagePane()
        val component = AssistantMessageComponent(pane)
        addMessageComponent(component)
        component.appendText(text)
        scrollManager.onContentAdded()
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
        orchestratorService.cancel()
        messagesPanel.removeAll()
        messagesPanel.revalidate()
        messagesPanel.repaint()
        workerMessages.clear()
        inputPanel.isStreaming = false
        waitingForUserResponse = false
        titleLabel.text = "Orchestrator"
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun colorToHex(color: java.awt.Color): String =
        String.format("#%02x%02x%02x", color.red, color.green, color.blue)

    override fun dispose() {
        scope.cancel()
    }
}
