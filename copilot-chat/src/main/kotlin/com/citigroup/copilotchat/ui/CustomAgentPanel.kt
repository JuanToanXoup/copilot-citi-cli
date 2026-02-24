package com.citigroup.copilotchat.ui

import com.citigroup.copilotchat.agent.*
import com.citigroup.copilotchat.conversation.ConversationManager
import com.citigroup.copilotchat.lsp.LspClient
import com.citigroup.copilotchat.lsp.LspClientFactory
import com.citigroup.copilotchat.lsp.ManagedClient
import com.citigroup.copilotchat.tools.ToolRouter
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.awt.*
import java.util.*
import java.util.Collections
import javax.swing.*

/**
 * "Custom" tab — two-phase panel:
 *
 * **Phase 1 (Config):** Select a lead agent, pick subagents, choose model,
 * and click "Start Session". No LSP processes are running yet.
 *
 * **Phase 2 (Chat):** On start, [LspClientFactory] creates standalone LSP
 * clients for the lead agent (and subagents get their own clients when
 * spawned). The UI transitions to a chat message window identical to
 * [AgentPanel] but backed by the custom-configured clients.
 *
 * This gives full control over which tooling gets configured before any
 * LSP process is started.
 */
class CustomAgentPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(CustomAgentPanel::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    // ── Phase 1: Config ──────────────────────────────────────────────

    private var allAgents: List<AgentDefinition> = emptyList()
    private val leadAgentCombo = JComboBox<String>()
    private val modelCombo = JComboBox(arrayOf("gpt-4.1", "claude-sonnet-4"))
    private val subagentCheckboxes = mutableListOf<SubagentCheckEntry>()
    private val subagentListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val startButton = JButton("Start Session").apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val statusLabel = JLabel(" ").apply {
        foreground = JBColor(0x666666, 0x999999)
        font = font.deriveFont(11f)
    }

    // ── Phase 2: Chat ────────────────────────────────────────────────

    private val messageRenderer = MessageRenderer()
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

    private val chatInput = ChatInputPanel(
        onSend = { text -> sendMessage(text) },
        onStop = { cancelSession() },
    ).apply {
        showAgentDropdown = false
        isInitializing = false
    }

    private val chatTitleLabel = JLabel("Custom Agent").apply {
        foreground = JBColor(0xBBBBBB, 0x999999)
        border = JBUI.Borders.empty(0, 8, 0, 0)
    }

    // Current assistant message for streaming appends
    private var currentAssistantMessage: AssistantMessageComponent? = null
    private val subagentPanels = mutableMapOf<String, SubagentPanelState>()
    private var lastToolCallPanel: ToolCallPanel? = null

    // ── Session state ────────────────────────────────────────────────

    private var leadClient: LspClient? = null
    private var leadManagedClient: ManagedClient? = null
    private var leadSession: LspSession? = null
    private var leadConversationId: String? = null
    @Volatile private var pendingLeadCreate = false
    @Volatile private var isStreaming = false
    private var currentWorkDoneToken: String? = null
    private var currentJob: Job? = null
    private var sessionAgents: List<AgentDefinition> = emptyList()
    private var sessionLeadAgent: AgentDefinition? = null

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 512)
    private val eventBus = object : AgentEventBus {
        override val events = _events
        override suspend fun emit(event: AgentEvent) { _events.emit(event) }
        override fun tryEmit(event: AgentEvent): Boolean = _events.tryEmit(event)
    }

    private var subagentManager: SubagentManager? = null

    init {
        isOpaque = false

        // Build config card
        cardPanel.add(buildConfigCard(), CARD_CONFIG)

        // Build chat card
        cardPanel.add(buildChatCard(), CARD_CHAT)

        add(cardPanel, BorderLayout.CENTER)
        cardLayout.show(cardPanel, CARD_CONFIG)

        // Load agents for the config UI
        refreshAgentList()

        // Observe events for chat phase
        scope.launch {
            _events.collect { event -> handleEvent(event) }
        }
    }

    // ── Config card ──────────────────────────────────────────────────

    private fun buildConfigCard(): JPanel {
        val panel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(12, 20)
        }
        val gbc = GridBagConstraints().apply {
            gridx = 0; gridy = 0
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            anchor = GridBagConstraints.NORTH
            insets = JBUI.insets(0, 0, 8, 0)
        }

        // Title
        panel.add(JLabel("Custom Agent Session").apply {
            font = font.deriveFont(Font.BOLD, 16f)
        }, gbc); gbc.gridy++

        panel.add(JLabel("Configure the lead agent and subagents before starting. LSP clients are only created on Start.").apply {
            font = font.deriveFont(11f)
            foreground = JBColor(0x6E7076, 0x6E7076)
        }, gbc); gbc.gridy++

        panel.add(Box.createVerticalStrut(8), gbc); gbc.gridy++

        // Lead agent
        panel.add(fieldRow("Lead Agent", leadAgentCombo), gbc); gbc.gridy++

        // Model
        panel.add(fieldRow("Model", modelCombo), gbc); gbc.gridy++

        // Subagents section
        val subagentSection = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JLabel("Subagents").apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = JBColor(0x8C8E94, 0x8C8E94)
                border = JBUI.Borders.empty(0, 0, 4, 0)
            }, BorderLayout.NORTH)
            add(JBScrollPane(subagentListPanel).apply {
                border = BorderFactory.createLineBorder(JBColor(0x393B40, 0x393B40))
                preferredSize = Dimension(0, 200)
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
        }
        panel.add(subagentSection, gbc); gbc.gridy++

        panel.add(Box.createVerticalStrut(8), gbc); gbc.gridy++

        // Start button + status
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(startButton)
            add(statusLabel)
        }
        panel.add(buttonRow, gbc); gbc.gridy++

        // Spacer
        gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 1.0
        panel.add(Box.createVerticalGlue(), gbc)

        // Wire actions
        startButton.addActionListener { startSession() }
        leadAgentCombo.addActionListener { onLeadAgentChanged() }

        return panel
    }

    private fun fieldRow(label: String, field: JComponent): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, 0, 4, 0)
        add(JLabel(label).apply {
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = JBColor(0x8C8E94, 0x8C8E94)
            border = JBUI.Borders.empty(0, 0, 2, 0)
        }, BorderLayout.NORTH)
        add(field, BorderLayout.CENTER)
    }

    private fun refreshAgentList() {
        allAgents = AgentRegistry.loadAll(project.basePath)

        leadAgentCombo.removeAllItems()
        val leads = allAgents.filter { it.subagents != null }
        for (lead in leads) {
            leadAgentCombo.addItem(lead.agentType)
        }

        onLeadAgentChanged()
    }

    private fun onLeadAgentChanged() {
        val selectedLead = leadAgentCombo.selectedItem as? String ?: return
        val leadDef = allAgents.find { it.agentType == selectedLead } ?: return

        // Determine visible subagents for this lead
        val workers = allAgents.filter { it.subagents == null }
        val scopedSubagents = if (leadDef.subagents != null && leadDef.subagents.isNotEmpty()) {
            workers.filter { w -> leadDef.subagents.any { it.equals(w.agentType, ignoreCase = true) } }
        } else {
            workers
        }

        subagentListPanel.removeAll()
        subagentCheckboxes.clear()

        for (worker in scopedSubagents) {
            val cb = JCheckBox("${worker.agentType} — ${worker.whenToUse.take(60)}").apply {
                isSelected = true
                isOpaque = false
            }
            subagentCheckboxes.add(SubagentCheckEntry(worker.agentType, cb))
            subagentListPanel.add(cb)
        }

        subagentListPanel.revalidate()
        subagentListPanel.repaint()
    }

    // ── Session startup ──────────────────────────────────────────────

    private fun startSession() {
        val selectedLead = leadAgentCombo.selectedItem as? String
        if (selectedLead == null) {
            statusLabel.text = "Select a lead agent first."
            return
        }
        val leadDef = allAgents.find { it.agentType == selectedLead }
        if (leadDef == null) {
            statusLabel.text = "Lead agent not found."
            return
        }

        // Gather enabled subagent types
        val enabledSubagents = subagentCheckboxes
            .filter { it.checkbox.isSelected }
            .map { it.agentType }

        val selectedModel = modelCombo.selectedItem as? String ?: "gpt-4.1"

        startButton.isEnabled = false
        statusLabel.text = "Starting LSP clients..."

        scope.launch {
            try {
                val factory = LspClientFactory.getInstance(project)
                val convManager = ConversationManager.getInstance(project)
                convManager.ensureInitialized()

                // Create a standalone client for the lead agent with its specific tools
                val leadTools = if (leadDef.hasUnrestrictedTools) emptySet() else leadDef.tools.toSet()
                val clientId = "custom-lead-${System.currentTimeMillis()}"

                statusLabel.text = "Creating lead agent LSP client..."

                val panel = this@CustomAgentPanel
                val client = factory.createStandaloneClient(
                    clientId = clientId,
                    tools = leadTools,
                    mcpServers = leadDef.mcpServers,
                    onServerRequest = { method, id, params ->
                        panel.handleServerRequest(method, id, params)
                    },
                )

                leadClient = client
                leadManagedClient = ManagedClient(client, isStandalone = true, clientId = clientId, tools = leadTools)

                // Build the session agents list scoped to enabled subagents
                sessionAgents = allAgents.filter { agent ->
                    agent.subagents == null && enabledSubagents.any { it.equals(agent.agentType, ignoreCase = true) }
                }
                sessionLeadAgent = leadDef

                // Create SubagentManager for this session
                val pool = com.citigroup.copilotchat.lsp.LspClientPool.getInstance(project)
                subagentManager = SubagentManager(
                    project = project,
                    scope = ioScope,
                    eventBus = eventBus,
                    pool = pool,
                    leadClient = client,
                    conversationManager = convManager,
                    cachedAuth = convManager.cachedAuth,
                )

                // Populate slash items for the chat input
                chatInput.slashItems = sessionAgents.map { agent ->
                    ChatInputPanel.SlashItem(agent.agentType, agent.whenToUse, "Subagent")
                }

                statusLabel.text = "Session ready!"
                log.info("CustomAgentPanel: session started — lead=$selectedLead, model=$selectedModel, subagents=${enabledSubagents.size}")

                // Transition to chat
                cardLayout.show(cardPanel, CARD_CHAT)
                chatTitleLabel.text = "Custom: $selectedLead"

            } catch (e: Exception) {
                log.error("CustomAgentPanel: failed to start session", e)
                statusLabel.text = "Error: ${e.message}"
                startButton.isEnabled = true
            }
        }
    }

    // ── Chat card ────────────────────────────────────────────────────

    private fun buildChatCard(): JPanel {
        val panel = JPanel(BorderLayout()).apply { isOpaque = false }

        val headerBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor(0xD0D0D0, 0x3C3F41)),
                JBUI.Borders.empty(6, 8, 6, 4)
            )

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(chatTitleLabel)
            }
            add(leftPanel, BorderLayout.CENTER)

            val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(createIconButton(AllIcons.Actions.Back, "Back to Config") { resetToConfig() })
                add(createIconButton(AllIcons.General.Add, "New Conversation") { newConversation() })
            }
            add(actionsPanel, BorderLayout.EAST)
        }

        val bottomPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(chatInput, BorderLayout.CENTER)
        }

        val splitter = OnePixelSplitter(true, "copilot.custom.splitter", 0.8f).apply {
            firstComponent = messagesScrollPane
            secondComponent = bottomPanel
            setHonorComponentsMinimumSize(true)
        }

        panel.add(headerBar, BorderLayout.NORTH)
        panel.add(splitter, BorderLayout.CENTER)

        return panel
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

    // ── Send / Cancel ────────────────────────────────────────────────

    private fun sendMessage(text: String) {
        if (isStreaming) return
        val client = leadClient ?: return

        if (text.isEmpty()) return

        val truncated = if (text.length > 40) text.take(40) + "..." else text
        chatTitleLabel.text = "Custom: $truncated"

        addGroupSpacing()
        addMessageComponent(UserMessageComponent(text))
        chatInput.isStreaming = true

        currentAssistantMessage = null
        lastToolCallPanel = null

        currentJob?.cancel()
        currentJob = ioScope.launch {
            try {
                val leadDef = sessionLeadAgent ?: return@launch
                val model = modelCombo.selectedItem as? String ?: "gpt-4.1"

                isStreaming = true
                var workDoneToken = "custom-lead-${UUID.randomUUID().toString().take(8)}"
                currentWorkDoneToken = workDoneToken
                val replyParts = Collections.synchronizedList(mutableListOf<String>())

                client.registerProgressListener(workDoneToken) { value ->
                    handleLeadProgress(value, replyParts)
                }

                try {
                    val rootUri = project.basePath?.let { "file://$it" } ?: "file:///tmp"
                    val isFirstTurn = leadConversationId == null
                    val prompt = if (isFirstTurn) buildLeadPrompt(text, leadDef) else text

                    if (isFirstTurn) {
                        val params = buildJsonObject {
                            put("workDoneToken", workDoneToken)
                            putJsonArray("turns") {
                                addJsonObject { put("request", prompt) }
                            }
                            putJsonObject("capabilities") {
                                put("allSkills", true)
                            }
                            put("source", "panel")
                            put("chatMode", "Agent")
                            put("needToolCallConfirmation", true)
                            put("model", model)
                            put("workspaceFolder", rootUri)
                            putJsonArray("workspaceFolders") {
                                addJsonObject {
                                    put("uri", rootUri)
                                    put("name", project.name)
                                }
                            }
                        }

                        log.info("CustomAgentPanel: creating lead conversation")
                        pendingLeadCreate = true
                        val resp = client.sendRequest("conversation/create", params, timeoutMs = 300_000)
                        pendingLeadCreate = false
                        if (leadConversationId == null) {
                            val result = resp["result"]
                            leadConversationId = when (result) {
                                is JsonArray -> result.firstOrNull()?.jsonObject?.get("conversationId")?.jsonPrimitive?.contentOrNull
                                is JsonObject -> result["conversationId"]?.jsonPrimitive?.contentOrNull
                                else -> null
                            }
                        }
                        log.info("CustomAgentPanel: lead conversationId=$leadConversationId")
                    } else {
                        val params = buildJsonObject {
                            put("workDoneToken", workDoneToken)
                            put("conversationId", leadConversationId!!)
                            put("message", text)
                            put("source", "panel")
                            put("chatMode", "Agent")
                            put("needToolCallConfirmation", true)
                            put("model", model)
                            put("workspaceFolder", rootUri)
                            putJsonArray("workspaceFolders") {
                                addJsonObject {
                                    put("uri", rootUri)
                                    put("name", project.name)
                                }
                            }
                        }

                        log.info("CustomAgentPanel: continuing lead conversation $leadConversationId")
                        client.sendRequest("conversation/turn", params, timeoutMs = 300_000)
                    }

                    // Wait loop with subagent collection
                    val startTime = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startTime < 300_000) {
                        delay(100)
                        if (!isStreaming) {
                            val mgr = subagentManager
                            if (mgr != null && mgr.hasPending()) {
                                log.info("CustomAgentPanel: lead turn ended with pending subagents — collecting results")
                                client.removeProgressListener(workDoneToken)

                                val resultContext = mgr.awaitAll()

                                workDoneToken = "custom-lead-${UUID.randomUUID().toString().take(8)}"
                                currentWorkDoneToken = workDoneToken
                                client.registerProgressListener(workDoneToken) { value ->
                                    handleLeadProgress(value, replyParts)
                                }

                                isStreaming = true
                                sendFollowUpTurn(client, workDoneToken, resultContext, model, rootUri)
                            } else {
                                break
                            }
                        }
                    }
                } finally {
                    client.removeProgressListener(workDoneToken)
                }

                currentWorkDoneToken = null
                isStreaming = false
                val fullReply = synchronized(replyParts) { replyParts.joinToString("") }
                _events.emit(LeadEvent.Done(fullReply))

            } catch (e: CancellationException) {
                isStreaming = false
                throw e
            } catch (e: Exception) {
                log.error("CustomAgentPanel: error in sendMessage", e)
                isStreaming = false
                _events.emit(LeadEvent.Error(e.message ?: "Unknown error"))
            }
        }

        scrollManager.forceSticky()
    }

    private fun buildLeadPrompt(userMessage: String, leadAgent: AgentDefinition): String {
        val visibleAgents = sessionAgents
        val agentList = visibleAgents.joinToString("\n") { "- ${it.agentType}: ${it.whenToUse}" }

        val template = if (leadAgent.systemPromptTemplate.isNotBlank()) {
            leadAgent.systemPromptTemplate
        } else {
            AgentRegistry.DEFAULT_LEAD_TEMPLATE
        }

        val resolvedPrompt = template.replace("{{AGENT_LIST}}", agentList)
        return "<system_instructions>\n$resolvedPrompt\n</system_instructions>\n\n$userMessage"
    }

    private suspend fun sendFollowUpTurn(
        client: LspClient,
        workDoneToken: String,
        resultContext: String,
        model: String,
        rootUri: String,
    ) {
        val message = "Subagent results from the previous round:\n\n" +
            "$resultContext\n\n" +
            "Review these results. If the task requires additional work — follow-up research, " +
            "dependent subtasks, or verification — delegate those now using delegate_task. " +
            "If all work is complete, synthesize the results into a final answer for the user."

        val params = buildJsonObject {
            put("workDoneToken", workDoneToken)
            put("conversationId", leadConversationId!!)
            put("message", message)
            put("source", "panel")
            put("chatMode", "Agent")
            put("needToolCallConfirmation", true)
            put("model", model)
            put("workspaceFolder", rootUri)
            putJsonArray("workspaceFolders") {
                addJsonObject {
                    put("uri", rootUri)
                    put("name", project.name)
                }
            }
        }

        log.info("CustomAgentPanel: sending follow-up turn with results")
        client.sendRequest("conversation/turn", params, timeoutMs = 300_000)
    }

    /**
     * Handle all server requests routed to this panel's standalone LSP client.
     * This is the custom onServerRequest passed to [LspClientFactory.createStandaloneClient].
     */
    internal suspend fun handleServerRequest(method: String, id: Int, params: JsonObject) {
        val client = leadClient ?: return

        when (method) {
            "conversation/invokeClientToolConfirmation" -> {
                val result = buildJsonArray {
                    addJsonObject { put("result", "accept") }
                    add(JsonNull)
                }
                client.sendResponse(id, result)
            }
            "conversation/invokeClientTool" -> {
                val toolName = params["name"]?.jsonPrimitive?.contentOrNull
                    ?: params["toolName"]?.jsonPrimitive?.contentOrNull
                    ?: "unknown"
                val toolInput = params["input"]?.jsonObject
                    ?: params["arguments"]?.jsonObject
                    ?: JsonObject(emptyMap())
                val callConvId = params["conversationId"]?.jsonPrimitive?.contentOrNull

                // Capture conversationId from early tool calls (before create returns)
                if (pendingLeadCreate && leadConversationId == null && callConvId != null) {
                    leadConversationId = callConvId
                    log.info("CustomAgentPanel: captured lead conversationId=$callConvId from early tool call")
                }

                log.info("CustomAgentPanel: tool call '$toolName' for conversation=$callConvId")

                // Intercept delegation via run_in_terminal
                if (toolName == "run_in_terminal") {
                    val command = toolInput["command"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (command.trimStart().startsWith("delegate ")) {
                        log.info("CustomAgentPanel: intercepted delegation via run_in_terminal")
                        val delegateInput = parseDelegateCommand(command)
                        subagentManager?.spawnSubagent(id, delegateInput, sessionAgents)
                        return
                    }
                }

                when (toolName) {
                    "delegate_task" -> subagentManager?.spawnSubagent(id, toolInput, sessionAgents)
                    else -> {
                        val convManager = ConversationManager.getInstance(project)
                        val wsOverride = if (callConvId != null) convManager.getWorkspaceOverride(callConvId) else null
                        val toolRouter = ToolRouter(project)
                        val result = toolRouter.executeTool(toolName, toolInput, wsOverride)
                        client.sendResponse(id, result)
                    }
                }
            }
            else -> {
                client.sendResponse(id, JsonNull)
            }
        }
    }

    private fun parseDelegateCommand(command: String): JsonObject {
        val typeRegex = Regex("""--type\s+(\S+)""")
        val promptRegex = Regex("""--prompt\s+"(.*?)"\s*$""", RegexOption.DOT_MATCHES_ALL)
        val promptFallbackRegex = Regex("""--prompt\s+(.+)$""", RegexOption.DOT_MATCHES_ALL)

        val subagentType = typeRegex.find(command)?.groupValues?.get(1) ?: "general-purpose"
        val prompt = promptRegex.find(command)?.groupValues?.get(1)
            ?: promptFallbackRegex.find(command)?.groupValues?.get(1)?.trim()
            ?: command.substringAfter("delegate ").trim()

        return buildJsonObject {
            put("description", prompt.take(50))
            put("prompt", prompt)
            put("subagent_type", subagentType)
        }
    }

    /** Check if this panel owns the given conversationId. */
    fun ownsConversation(conversationId: String?): Boolean {
        if (conversationId == null) return false
        if (conversationId == leadConversationId) return true
        if (pendingLeadCreate && leadConversationId == null) {
            leadConversationId = conversationId
            log.info("CustomAgentPanel: captured lead conversationId=$conversationId from early tool call")
            return true
        }
        return false
    }

    // ── Progress handling ────────────────────────────────────────────

    private fun handleLeadProgress(value: JsonObject, replyParts: MutableList<String>) {
        val kind = value["kind"]?.jsonPrimitive?.contentOrNull

        if (kind == "end") {
            isStreaming = false
            return
        }

        val reply = value["reply"]?.jsonPrimitive?.contentOrNull
        if (reply != null) {
            replyParts.add(reply)
            _events.tryEmit(LeadEvent.Delta(reply))
        }

        val delta = value["delta"]?.jsonPrimitive?.contentOrNull
        if (delta != null) {
            replyParts.add(delta)
            _events.tryEmit(LeadEvent.Delta(delta))
        }

        val message = value["message"]?.jsonPrimitive?.contentOrNull
        if (message != null && kind != "begin") {
            replyParts.add(message)
            _events.tryEmit(LeadEvent.Delta(message))
        }

        // Agent rounds
        val rounds = value["editAgentRounds"]?.jsonArray
        rounds?.forEach { roundEl ->
            val round = roundEl.jsonObject
            val roundReply = round["reply"]?.jsonPrimitive?.contentOrNull ?: ""
            if (roundReply.isNotEmpty()) {
                replyParts.add(roundReply)
                _events.tryEmit(LeadEvent.Delta(roundReply))
            }

            val toolCalls = round["toolCalls"]?.jsonArray
            toolCalls?.forEach { toolCallEl ->
                val tc = toolCallEl.jsonObject
                val tcName = tc["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val tcInput = tc["input"]?.jsonObject ?: JsonObject(emptyMap())
                val status = tc["status"]?.jsonPrimitive?.contentOrNull ?: ""

                val isDelegation = tcName == "delegate_task" || (tcName == "run_in_terminal" &&
                    tcInput["command"]?.jsonPrimitive?.contentOrNull?.trimStart()?.startsWith("delegate ") == true)

                if (!isDelegation) {
                    _events.tryEmit(LeadEvent.ToolCall(tcName, tcInput))
                    if (status == "completed" || status == "error") {
                        val resultData = tc["result"]?.jsonArray
                        val resultText = resultData?.firstOrNull()?.jsonObject
                            ?.get("content")?.jsonPrimitive?.contentOrNull
                            ?: status
                        _events.tryEmit(LeadEvent.ToolResult(tcName, resultText.take(200)))
                    }
                }
            }
        }
    }

    // ── Event rendering (same pattern as AgentPanel) ─────────────────

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is LeadEvent.Delta -> {
                val msg = getOrCreateLeadMessage()
                msg.appendText(event.text)
                scrollManager.onContentAdded()
            }

            is LeadEvent.ToolCall -> {
                val existingPanel = lastToolCallPanel
                if (existingPanel != null && existingPanel.toolName == event.name) {
                    existingPanel.addAction(event.input)
                } else {
                    currentAssistantMessage = null
                    addItemSpacing()
                    val panel = ToolCallPanel(event.name, event.input)
                    lastToolCallPanel = panel
                    addMessageComponent(panel)
                }
                scrollManager.onContentAdded()
            }

            is LeadEvent.ToolResult -> {
                lastToolCallPanel = null
            }

            is SubagentEvent.Spawned -> {
                currentAssistantMessage = null
                lastToolCallPanel = null
                addItemSpacing()

                val headerLabel = JLabel("[${event.agentType}] ${event.description}").apply {
                    icon = AnimatedIcon.Default()
                    foreground = JBColor(0x0366D6, 0x58A6FF)
                    border = JBUI.Borders.empty(2, 4)
                }
                val collapsible = CollapsiblePanel(headerLabel, initiallyExpanded = true)
                val contentPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                }

                if (event.prompt.isNotBlank()) {
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
                }

                val replyPane = MarkdownHtmlPane()
                contentPanel.add(replyPane)
                collapsible.getContent().add(contentPanel)

                subagentPanels[event.agentId] = SubagentPanelState(collapsible, replyPane, headerLabel)
                addMessageComponent(collapsible)
                scrollManager.onContentAdded()
            }

            is SubagentEvent.Delta -> {
                val state = subagentPanels[event.agentId] ?: return
                state.replyPane.appendText(event.text)
                scrollManager.onContentAdded()
            }

            is SubagentEvent.ToolCall -> { }

            is SubagentEvent.Completed -> {
                val state = subagentPanels[event.agentId]
                if (state != null) {
                    if (event.result.isNotBlank() && !state.replyPane.hasContent()) {
                        state.replyPane.setText(event.result)
                    }
                    val durationStr = formatDuration(event.durationMs)
                    val statusIcon = if (event.status == "success") "\u2713" else "\u2717"
                    val currentText = state.headerLabel.text
                    state.headerLabel.icon = null
                    state.headerLabel.text = "$statusIcon $currentText ($durationStr)"
                    state.headerLabel.foreground = if (event.status == "success")
                        JBColor(0x28A745, 0x3FB950)
                    else
                        JBColor(0xCB2431, 0xF85149)
                    state.collapsible.collapse()
                }
                subagentPanels.remove(event.agentId)
                scrollManager.onContentAdded()
            }

            is SubagentEvent.HandoffsAvailable -> { }

            is SubagentEvent.WorktreeChangesReady -> {
                currentAssistantMessage = null
                lastToolCallPanel = null
                addItemSpacing()
                val reviewPanel = WorktreeReviewPanel(
                    agentId = event.agentId,
                    changes = event.changes,
                    project = project,
                )
                addMessageComponent(reviewPanel)
                scrollManager.onContentAdded()
            }

            is LeadEvent.Done -> {
                if (event.fullText.isNotBlank() && currentAssistantMessage == null) {
                    addItemSpacing()
                    val pane = messageRenderer.createMessagePane()
                    val component = AssistantMessageComponent(pane)
                    addMessageComponent(component)
                    component.appendText(event.fullText)
                }
                currentAssistantMessage = null
                lastToolCallPanel = null
                chatInput.isStreaming = false
                scrollManager.onContentAdded()
            }

            is LeadEvent.Error -> {
                currentAssistantMessage = null
                lastToolCallPanel = null
                chatInput.isStreaming = false
                addItemSpacing()
                addErrorMessage(event.message)
            }

            // Team events — display as status lines
            is TeamEvent.Created -> { addItemSpacing(); addStatusLine("Team '${event.teamName}' created") }
            is TeamEvent.MemberJoined -> { addItemSpacing(); addStatusLine("${event.name} (${event.agentType}) joined") }
            is TeamEvent.MemberIdle -> { addStatusLine("${event.name} is idle") }
            is TeamEvent.MemberResumed -> { addStatusLine("${event.name} resumed") }
            is TeamEvent.MailboxMessage -> { addStatusLine("${event.from} -> ${event.to}: ${event.summary}") }
            is TeamEvent.Disbanded -> { addItemSpacing(); addStatusLine("Team '${event.teamName}' disbanded") }
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

    // ── UI helpers ───────────────────────────────────────────────────

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

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun colorToHex(color: java.awt.Color): String =
        String.format("#%02x%02x%02x", color.red, color.green, color.blue)

    private fun formatDuration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "%.1fs".format(ms / 1000.0)
        else -> {
            val minutes = ms / 60_000
            val seconds = (ms % 60_000) / 1000
            "${minutes}m ${seconds}s"
        }
    }

    // ── Session management ───────────────────────────────────────────

    private fun cancelSession() {
        val client = leadClient ?: return
        val token = currentWorkDoneToken
        if (token != null && client.isRunning) {
            client.sendNotification(
                "window/workDoneProgress/cancel",
                buildJsonObject { put("token", token) }
            )
            client.removeProgressListener(token)
            currentWorkDoneToken = null
        }

        currentJob?.cancel()
        currentJob = null
        subagentManager?.cancelAll()
        isStreaming = false
        scope.launch { _events.emit(LeadEvent.Done()) }
    }

    private fun newConversation() {
        cancelSession()
        leadConversationId = null
        pendingLeadCreate = false
        messagesPanel.removeAll()
        messagesPanel.revalidate()
        messagesPanel.repaint()
        currentAssistantMessage = null
        lastToolCallPanel = null
        subagentPanels.clear()
        chatInput.isStreaming = false
        chatTitleLabel.text = "Custom: ${sessionLeadAgent?.agentType ?: "Agent"}"
    }

    private fun resetToConfig() {
        // Tear down session completely
        cancelSession()
        disposeSessionClients()

        leadConversationId = null
        pendingLeadCreate = false
        sessionAgents = emptyList()
        sessionLeadAgent = null
        subagentManager = null

        // Clear chat UI
        messagesPanel.removeAll()
        messagesPanel.revalidate()
        messagesPanel.repaint()
        currentAssistantMessage = null
        lastToolCallPanel = null
        subagentPanels.clear()
        chatInput.isStreaming = false
        chatTitleLabel.text = "Custom Agent"

        // Back to config
        startButton.isEnabled = true
        statusLabel.text = " "
        refreshAgentList()
        cardLayout.show(cardPanel, CARD_CONFIG)
    }

    private fun disposeSessionClients() {
        val managed = leadManagedClient
        if (managed != null && managed.isStandalone && managed.clientId != null) {
            try {
                LspClientFactory.getInstance(project).disposeStandaloneClient(managed.clientId)
            } catch (e: Exception) {
                log.warn("CustomAgentPanel: error disposing lead client: ${e.message}")
            }
        }
        leadClient = null
        leadManagedClient = null
    }

    // ── Dispose ──────────────────────────────────────────────────────

    override fun dispose() {
        cancelSession()
        disposeSessionClients()
        scope.cancel()
        ioScope.cancel()
    }

    // ── Inner types ──────────────────────────────────────────────────

    private data class SubagentCheckEntry(val agentType: String, val checkbox: JCheckBox)

    private data class SubagentPanelState(
        val collapsible: CollapsiblePanel,
        val replyPane: MarkdownHtmlPane,
        val headerLabel: JLabel,
    )

    companion object {
        private const val CARD_CONFIG = "config"
        private const val CARD_CHAT = "chat"
    }
}
