package com.citigroup.copilotchat.ui

import com.citigroup.copilotchat.agent.*
import com.citigroup.copilotchat.config.CopilotChatSettings
import com.citigroup.copilotchat.tools.BuiltInTools
import com.citigroup.copilotchat.tools.psi.PsiTools
import kotlinx.serialization.json.*
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * "Agents" config tab — single scrollable page.
 * Top: tabbed agent list (Lead Agents / Subagents), tables grow to fit rows.
 * Below: inline config editor for the selected agent.
 */
class AgentConfigPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private var allAgents: List<AgentDefinition> = emptyList()
    private var currentAgent: AgentDefinition? = null

    // ── Pending changes (nothing touches disk until Apply) ──────────

    /** Agents scheduled for deletion on Apply. Keyed by agentType. */
    private val pendingDeletes = mutableSetOf<String>()

    // ── Dirty state (centralized) ───────────────────────────────────

    private var dirty = false
    private var trackingEnabled = true

    /** Run [block] with dirty tracking suppressed — guaranteed to restore tracking afterwards. */
    private inline fun withoutTracking(block: () -> Unit) {
        val was = trackingEnabled
        trackingEnabled = false
        try { block() } finally { trackingEnabled = was }
    }

    private fun markDirty() {
        if (trackingEnabled) {
            dirty = true
            applyButton.isEnabled = true
        }
    }

    private fun resetDirty() {
        dirty = false
        pendingDeletes.clear()
        applyButton.isEnabled = false
    }

    // ── Agent list tables ──────────────────────────────────────────

    private val leadTableModel = NameDescTableModel()
    private val subagentTableModel = NameDescTableModel()
    private val leadTable = agentTable(leadTableModel)
    private val subagentTable = agentTable(subagentTableModel)
    private val agentTabs = JTabbedPane()

    // ── Config fields ──────────────────────────────────────────────

    private val titleLabel = JLabel().apply { font = font.deriveFont(Font.BOLD, 15f) }
    private val sourceBadge = JLabel().apply {
        font = font.deriveFont(10f)
        foreground = JBColor(0x6E7076, 0x6E7076)
        isOpaque = true
        background = JBColor(0x393B40, 0x393B40)
        border = JBUI.Borders.empty(2, 7)
    }
    private val nameField = JBTextField()
    private val descriptionField = JBTextField()
    private val modelCombo = JComboBox(arrayOf("inherit", "gpt-4.1", "claude-sonnet-4"))
    private val maxTurnsSpinner = JSpinner(SpinnerNumberModel(30, 1, 200, 1))
    private val systemPromptArea = JTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        font = Font("JetBrains Mono", Font.PLAIN, 12)
        foreground = JBColor(0x1E1E1E, 0xBCBEC4)
        background = JBColor(0xFFFFFF, 0x2B2D30)
        caretColor = JBColor(0x1E1E1E, 0xBCBEC4)
        border = JBUI.Borders.empty(6, 8)
    }

    // Subagent pool
    private val poolModel = CheckTableModel(arrayOf("", "Agent", "Description")) { markDirty() }
    private val poolTable = checkTable(poolModel)

    // Tool tables
    private val builtInToolsModel = CheckTableModel(arrayOf("", "Tool", "Description")) { markDirty() }
    private val ideToolsModel = CheckTableModel(arrayOf("", "Action", "Description")) { markDirty() }
    private val mcpToolsModel = CheckTableModel(arrayOf("", "Server", "Description")) { markDirty() }
    private val builtInToolsTable = checkTable(builtInToolsModel)
    private val ideToolsTable = checkTable(ideToolsModel)
    private val mcpToolsTable = checkTable(mcpToolsModel)

    // Handoffs
    private val handoffsModel = NameDescTableModel()
    private val handoffsTable = agentTable(handoffsModel)

    // Advanced
    private val forkContextCb = JCheckBox("Fork Context")
    private val backgroundCb = JCheckBox("Background")
    private val disableModelCb = JCheckBox("Disable Model Invocation")
    private val targetCombo = JComboBox(arrayOf("(none)", "vscode", "github-copilot"))

    private val applyButton = JButton("Apply").apply {
        isEnabled = false
        addActionListener {
            flushAllChanges()
            resetDirty()
            text = "Saved"
            Timer(1500) { e ->
                text = "Apply"
                (e.source as Timer).stop()
            }.start()
        }
    }

    // Section references for show/hide
    private lateinit var subagentPoolSection: CollapsiblePanel

    // Outer content panel (for revalidate after reload)
    private val contentPanel: JPanel

    // Tracks previous selection so we can revert on Cancel
    private var previousLeadRow = -1
    private var previousSubagentRow = -1
    private var previousTabIndex = 0
    private var suppressSelectionPrompt = false

    init {
        isOpaque = false
        border = JBUI.Borders.empty()

        // Selection listeners — prompt for unsaved changes, revert on Cancel.
        // When suppressSelectionPrompt is true, the tab change listener already handled the prompt.
        leadTable.selectionModel.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val row = leadTable.selectedRow
            if (row < 0 || row == previousLeadRow) return@addListSelectionListener
            if (!suppressSelectionPrompt && !promptUnsavedChanges()) {
                SwingUtilities.invokeLater {
                    if (previousLeadRow in 0 until leadTableModel.rowCount) {
                        leadTable.setRowSelectionInterval(previousLeadRow, previousLeadRow)
                    }
                }
                return@addListSelectionListener
            }
            previousLeadRow = row
            showAgentConfig(leadTableModel.agentAt(row))
        }
        subagentTable.selectionModel.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val row = subagentTable.selectedRow
            if (row < 0 || row == previousSubagentRow) return@addListSelectionListener
            if (!suppressSelectionPrompt && !promptUnsavedChanges()) {
                SwingUtilities.invokeLater {
                    if (previousSubagentRow in 0 until subagentTableModel.rowCount) {
                        subagentTable.setRowSelectionInterval(previousSubagentRow, previousSubagentRow)
                    }
                }
                return@addListSelectionListener
            }
            previousSubagentRow = row
            showAgentConfig(subagentTableModel.agentAt(row))
        }

        // Tab change: prompt for unsaved, revert tab on Cancel.
        // Suppresses the selection listener prompt since we handle it here.
        agentTabs.addChangeListener {
            val newIndex = agentTabs.selectedIndex
            if (newIndex == previousTabIndex) return@addChangeListener
            if (!promptUnsavedChanges()) {
                // Revert the tab
                SwingUtilities.invokeLater { agentTabs.selectedIndex = previousTabIndex }
                return@addChangeListener
            }
            previousTabIndex = newIndex
            suppressSelectionPrompt = true
            try {
                val table = if (newIndex == 0) leadTable else subagentTable
                val model = if (newIndex == 0) leadTableModel else subagentTableModel
                val row = table.selectedRow
                if (row >= 0) showAgentConfig(model.agentAt(row))
            } finally {
                suppressSelectionPrompt = false
            }
        }

        // Dirty tracking on editable fields
        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { markDirty() }
            override fun removeUpdate(e: DocumentEvent?) { markDirty() }
            override fun changedUpdate(e: DocumentEvent?) { markDirty() }
        }
        nameField.document.addDocumentListener(docListener)
        descriptionField.document.addDocumentListener(docListener)
        systemPromptArea.document.addDocumentListener(docListener)
        modelCombo.addActionListener { markDirty() }
        maxTurnsSpinner.addChangeListener { markDirty() }
        forkContextCb.addActionListener { markDirty() }
        backgroundCb.addActionListener { markDirty() }
        disableModelCb.addActionListener { markDirty() }
        targetCombo.addActionListener { markDirty() }

        // ── Build single scrollable page ──
        contentPanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 14, 20, 14)
        }
        val gbc = GridBagConstraints().apply {
            gridx = 0; gridy = 0
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            anchor = GridBagConstraints.NORTH
            insets = JBUI.insets(0, 0, 2, 0)
        }

        // Agent tabs (Lead Agents / Subagents)
        agentTabs.addTab("Lead Agents", buildAgentTabContent(leadTable, leadTableModel, isSupervisor = true))
        agentTabs.addTab("Subagents", buildAgentTabContent(subagentTable, subagentTableModel, isSupervisor = false))
        contentPanel.add(agentTabs, gbc); gbc.gridy++

        // Title row
        contentPanel.add(buildTitleRow(), gbc); gbc.gridy++

        // General
        contentPanel.add(buildGeneralSection(), gbc); gbc.gridy++

        // System Prompt
        contentPanel.add(buildSystemPromptSection(), gbc); gbc.gridy++

        // Subagent Pool (leads only)
        subagentPoolSection = buildSubagentPoolSection()
        contentPanel.add(subagentPoolSection, gbc); gbc.gridy++

        // Tools
        contentPanel.add(buildToolsSection(), gbc); gbc.gridy++

        // Handoffs
        contentPanel.add(buildHandoffsSection(), gbc); gbc.gridy++

        // Advanced
        contentPanel.add(buildAdvancedSection(), gbc); gbc.gridy++

        // Spacer
        gbc.fill = GridBagConstraints.BOTH; gbc.weighty = 1.0
        contentPanel.add(Box.createVerticalGlue(), gbc)

        add(JBScrollPane(contentPanel).apply {
            border = BorderFactory.createEmptyBorder()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)

        // Fixed bottom bar with Apply button
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor(0x393B40, 0x393B40))
            add(applyButton)
        }, BorderLayout.SOUTH)

        reload()
    }

    // ── Data ───────────────────────────────────────────────────────

    fun reload() {
        allAgents = AgentRegistry.loadAll(project.basePath)
        leadTableModel.update(allAgents.filter { it.subagents != null })
        subagentTableModel.update(allAgents.filter { it.subagents == null })

        val cur = currentAgent
        if (cur != null) {
            val refreshed = allAgents.find { it.agentType == cur.agentType }
            if (refreshed != null) {
                showAgentConfig(refreshed)
                selectInTable(refreshed)
            } else {
                currentAgent = null
                withoutTracking { clearConfigPanel() }
            }
        } else if (allAgents.isNotEmpty()) {
            val first = allAgents.firstOrNull { it.subagents != null } ?: allAgents.first()
            selectInTable(first)
        }

        contentPanel.revalidate()
        contentPanel.repaint()
    }

    // ── Agent tabs ─────────────────────────────────────────────────

    private fun buildAgentTabContent(
        table: JBTable,
        model: NameDescTableModel,
        isSupervisor: Boolean,
    ): JPanel {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 1, 0, 1, JBColor(0x393B40, 0x393B40)),
                JBUI.Borders.empty(2, 4),
            )
            add(iconButton(AllIcons.General.Add) { addAgent(isSupervisor) })
            add(iconButton(AllIcons.General.Remove) { deleteSelectedAgent(table, model) })
            add(iconButton(AllIcons.Actions.Copy) { cloneSelectedAgent(table, model) })
        }

        // Table without scroll — grows to fit all rows
        val tableContainer = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createLineBorder(JBColor(0x393B40, 0x393B40))
            add(table.tableHeader, BorderLayout.NORTH)
            add(table, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(toolbar, BorderLayout.NORTH)
            add(tableContainer, BorderLayout.CENTER)
        }
    }

    // ── Config sections ────────────────────────────────────────────

    private fun buildTitleRow(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor(0x393B40, 0x393B40)),
            JBUI.Borders.empty(4, 0, 4, 0),
        )
        add(titleLabel)
        add(sourceBadge)
    }

    private fun buildGeneralSection(): CollapsiblePanel {
        val content = JPanel(GridBagLayout()).apply {
            isOpaque = false
            val c = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                gridx = 0; gridy = 0; weightx = 1.0
                gridwidth = 2
                insets = JBUI.insets(0, 0, 4, 0)
            }
            add(fieldRow("Name", nameField), c); c.gridy++
            add(fieldRow("Description", descriptionField), c); c.gridy++

            // Model + Max Turns side by side
            c.gridwidth = 1; c.insets = JBUI.insets(0, 0, 0, 12)
            add(fieldRow("Model", modelCombo), c)
            c.gridx = 1; c.weightx = 0.0; c.insets = JBUI.emptyInsets()
            maxTurnsSpinner.preferredSize = Dimension(80, maxTurnsSpinner.preferredSize.height)
            add(fieldRow("Max Turns", maxTurnsSpinner), c)
        }
        return collapsible("General", expanded = true, content)
    }

    private fun buildSystemPromptSection(): CollapsiblePanel {
        val promptBg = JBColor(0xF5F5F5, 0x1E1F22)
        systemPromptArea.background = promptBg
        systemPromptArea.isOpaque = true

        val content = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JLabel(
                "<html>Use <code style='font-size:11px'>{{AGENT_LIST}}</code> to inject available agents at runtime.</html>"
            ).apply {
                font = font.deriveFont(11f)
                foreground = JBColor(0x6E7076, 0x6E7076)
                border = JBUI.Borders.empty(0, 0, 4, 0)
            }, BorderLayout.NORTH)
            add(JBScrollPane(systemPromptArea).apply {
                border = BorderFactory.createLineBorder(JBColor(0x43454A, 0x43454A))
                viewport.background = promptBg
                viewport.isOpaque = true
            }, BorderLayout.CENTER)
        }
        return collapsible("System Prompt", expanded = true, content)
    }

    private fun buildSubagentPoolSection(): CollapsiblePanel {
        val content = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JLabel("Controls which workers this lead can delegate to via delegate_task.").apply {
                font = font.deriveFont(11f)
                foreground = JBColor(0x6E7076, 0x6E7076)
                border = JBUI.Borders.empty(0, 0, 4, 0)
            }, BorderLayout.NORTH)
            add(innerTableWithToolbar(
                poolTable,
                AllIcons.General.Add to {},
                AllIcons.General.Remove to {},
                AllIcons.Actions.Refresh to { reload() },
            ), BorderLayout.CENTER)
        }
        return collapsible("Subagent Pool", expanded = true, content)
    }

    private fun buildToolsSection(): CollapsiblePanel {
        val tabbedPane = JTabbedPane().apply {
            addTab("Built-in", toolTabPanel(builtInToolsTable, hasAddRemove = false))
            addTab("IDE", toolTabPanel(ideToolsTable, hasAddRemove = false))
            addTab("MCP", toolTabPanel(mcpToolsTable, hasAddRemove = true))
        }
        val content = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JLabel("Controls which tools are available to this agent.").apply {
                font = font.deriveFont(11f)
                foreground = JBColor(0x6E7076, 0x6E7076)
                border = JBUI.Borders.empty(0, 0, 4, 0)
            }, BorderLayout.NORTH)
            add(tabbedPane, BorderLayout.CENTER)
        }
        return collapsible("Tools", expanded = true, content)
    }

    private fun toolTabPanel(table: JBTable, hasAddRemove: Boolean): JPanel {
        val buttons = mutableListOf<Pair<Icon, () -> Unit>>()
        if (hasAddRemove) {
            buttons.add(AllIcons.General.Add to {})
            buttons.add(AllIcons.General.Remove to {})
        }
        buttons.add(AllIcons.Actions.Refresh to {})
        return innerTableWithToolbar(table, *buttons.toTypedArray())
    }

    private fun buildHandoffsSection(): CollapsiblePanel {
        val content = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JLabel("Agent-to-agent transitions. This agent can hand off control to the listed agents.").apply {
                font = font.deriveFont(11f)
                foreground = JBColor(0x6E7076, 0x6E7076)
                border = JBUI.Borders.empty(0, 0, 4, 0)
            }, BorderLayout.NORTH)
            add(innerTableWithToolbar(
                handoffsTable,
                AllIcons.General.Add to {},
                AllIcons.General.Remove to {},
                AllIcons.Actions.Refresh to {},
            ), BorderLayout.CENTER)
        }
        return collapsible("Handoffs", expanded = false, content)
    }

    private fun buildAdvancedSection(): CollapsiblePanel {
        val content = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, 16, 0)).apply {
                isOpaque = false
                add(forkContextCb)
                add(backgroundCb)
                add(disableModelCb)
            }, BorderLayout.NORTH)
            add(fieldRow("Target", targetCombo).apply {
                border = JBUI.Borders.empty(8, 0, 0, 0)
            }, BorderLayout.CENTER)
        }
        return collapsible("Advanced", expanded = false, content)
    }

    // ── Unsaved changes ───────────────────────────────────────────

    /**
     * Prompts the user if there are unsaved changes on an editable agent.
     * Returns true if it's OK to proceed (saved, discarded, or no changes).
     * Returns false if the user chose Cancel.
     *
     * Public so [ChatToolWindowFactory] can call it when the user navigates away from the Agents tab.
     */
    fun promptUnsavedChanges(): Boolean {
        if (!dirty || currentAgent == null || currentAgent?.source == AgentSource.BUILT_IN) return true
        val choice = JOptionPane.showOptionDialog(
            this,
            "You have unsaved changes. Save before switching?",
            "Unsaved Changes",
            JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            arrayOf("Save", "Discard", "Cancel"),
            "Save",
        )
        return when (choice) {
            0 -> { flushAllChanges(); resetDirty(); true }
            1 -> {
                // Discard everything — reload from disk to revert all in-memory changes
                resetDirty()
                reload()
                true
            }
            else -> false
        }
    }

    // ── Show / clear config ────────────────────────────────────────

    private fun showAgentConfig(agent: AgentDefinition?) {
        if (agent == null) { withoutTracking { clearConfigPanel() }; return }
        currentAgent = agent

        withoutTracking {
            val isReadOnly = agent.source == AgentSource.BUILT_IN

            titleLabel.text = agent.agentType
            sourceBadge.text = sourceLabel(agent.source)
            sourceBadge.isVisible = true
            nameField.text = agent.agentType
            descriptionField.text = agent.whenToUse
            modelCombo.selectedItem = AgentRegistry.modelToString(agent.model)
            maxTurnsSpinner.value = agent.maxTurns
            systemPromptArea.text = agent.systemPromptTemplate
            systemPromptArea.caretPosition = 0

            val isLead = agent.subagents != null
            subagentPoolSection.isVisible = isLead
            if (isLead) populateSubagentPool(agent)

            populateBuiltInTools(agent)
            populateIdeTools(agent)
            populateMcpTools(agent)
            populateHandoffs(agent)

            forkContextCb.isSelected = agent.forkContext
            backgroundCb.isSelected = agent.background
            disableModelCb.isSelected = agent.disableModelInvocation
            targetCombo.selectedItem = agent.target ?: "(none)"

            setFieldsEditable(!isReadOnly)
        }
        resetDirty()
    }

    private fun clearConfigPanel() {
        titleLabel.text = "(select an agent)"
        sourceBadge.isVisible = false
        nameField.text = ""
        descriptionField.text = ""
        modelCombo.selectedItem = "inherit"
        maxTurnsSpinner.value = 30
        systemPromptArea.text = ""
        poolModel.clear()
        builtInToolsModel.clear()
        ideToolsModel.clear()
        mcpToolsModel.clear()
        handoffsModel.update(emptyList())
        setFieldsEditable(false)
        resetDirty()
    }

    private fun setFieldsEditable(editable: Boolean) {
        nameField.isEditable = editable
        descriptionField.isEditable = editable
        modelCombo.isEnabled = editable
        maxTurnsSpinner.isEnabled = editable
        systemPromptArea.isEditable = editable
        forkContextCb.isEnabled = editable
        backgroundCb.isEnabled = editable
        disableModelCb.isEnabled = editable
        targetCombo.isEnabled = editable
        poolModel.editable = editable
        builtInToolsModel.editable = editable
        ideToolsModel.editable = editable
        mcpToolsModel.editable = editable
    }

    // ── Populate data ──────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    private fun populateSubagentPool(agent: AgentDefinition) {
        val workers = allAgents.filter { it.subagents == null }
        val included = agent.subagents ?: emptyList()
        val allIncluded = included.isEmpty()
        poolModel.update(workers.map { w ->
            CheckRow(
                w.agentType,
                w.whenToUse.take(80),
                allIncluded || included.any { it.equals(w.agentType, ignoreCase = true) },
            )
        })
        autoFitNameColumn(poolTable)
    }

    private fun populateBuiltInTools(agent: AgentDefinition) {
        val skipNames = setOf("delegate_task", "create_team", "send_message", "delete_team")
        val rows = BuiltInTools.schemas.mapNotNull { schema ->
            try {
                val obj = json.parseToJsonElement(schema).jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (name in skipNames) return@mapNotNull null
                val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                val enabled = agent.tools == null || agent.tools.contains(name)
                CheckRow(name, desc, enabled)
            } catch (_: Throwable) { null }
        }
        builtInToolsModel.update(rows)
        autoFitNameColumn(builtInToolsTable)
    }

    private fun populateIdeTools(agent: AgentDefinition) {
        try {
            val rows = PsiTools.allTools.map { tool ->
                val display = tool.name.removePrefix("ide_")
                val enabled = agent.tools == null || "ide" in agent.tools || tool.name in (agent.tools ?: emptyList())
                CheckRow(display, tool.description, enabled)
            }
            ideToolsModel.update(rows)
            autoFitNameColumn(ideToolsTable)
        } catch (_: Throwable) {
            ideToolsModel.clear()
        }
    }

    private fun populateMcpTools(agent: AgentDefinition) {
        val fromAgent = agent.mcpServers.entries.map { (name, cfg) ->
            CheckRow(name, "${cfg.command} ${cfg.args.joinToString(" ")}".trim(), true)
        }
        val fromGlobal = CopilotChatSettings.getInstance().mcpServers.map { entry ->
            CheckRow(entry.name, "${entry.command} ${entry.args}".trim(), entry.enabled)
        }
        mcpToolsModel.update((fromAgent + fromGlobal).distinctBy { it.name })
        autoFitNameColumn(mcpToolsTable)
    }

    private fun populateHandoffs(agent: AgentDefinition) {
        val handoffAgents = agent.handoffs.mapNotNull { name ->
            allAgents.find { it.agentType.equals(name, ignoreCase = true) }
        }
        handoffsModel.update(handoffAgents)
    }

    // ── Actions ────────────────────────────────────────────────────

    private fun addAgent(isSupervisor: Boolean) {
        val defaults = if (isSupervisor) {
            AgentDefinition(
                agentType = "", whenToUse = "",
                model = AgentModel.CLAUDE_SONNET_4,
                systemPromptTemplate = AgentRegistry.DEFAULT_LEAD_TEMPLATE,
                maxTurns = 30, subagents = emptyList(),
            )
        } else {
            AgentDefinition(
                agentType = "", whenToUse = "",
                model = AgentModel.GPT_4_1,
                tools = listOf("ide", "read_file", "list_dir", "grep_search", "file_search"),
                systemPromptTemplate = "", maxTurns = 15,
            )
        }

        val dialog = AgentDefinitionDialog(project, defaults, isSupervisor)
        if (dialog.showAndGet()) {
            val name = dialog.getAgentName()
            val desc = dialog.getAgentDescription()
            val agent = defaults.copy(
                agentType = name, whenToUse = desc,
                source = AgentSource.CUSTOM_PROJECT,
            )
            // Add to in-memory list and table — do NOT persist until Apply
            addAgentInMemory(agent)
        }
    }

    private fun deleteSelectedAgent(table: JBTable, model: NameDescTableModel) {
        val row = table.selectedRow
        if (row < 0) return
        val agent = model.agentAt(row) ?: return
        if (agent.source == AgentSource.BUILT_IN) return
        val confirm = JOptionPane.showConfirmDialog(
            this, "Delete agent '${agent.agentType}'?", "Confirm Delete", JOptionPane.YES_NO_OPTION,
        )
        if (confirm == JOptionPane.YES_OPTION) {
            // Track for deletion on Apply (only if it exists on disk)
            if (agent.filePath != null) pendingDeletes.add(agent.agentType)
            // Remove from in-memory list and table
            allAgents = allAgents.filter { it.agentType != agent.agentType }
            val isLead = agent.subagents != null
            val tableModel = if (isLead) leadTableModel else subagentTableModel
            tableModel.update(allAgents.filter { if (isLead) it.subagents != null else it.subagents == null })
            currentAgent = null
            withoutTracking { clearConfigPanel() }
            markDirty()
        }
    }

    private fun cloneSelectedAgent(table: JBTable, model: NameDescTableModel) {
        val row = table.selectedRow
        if (row < 0) return
        val agent = model.agentAt(row) ?: return
        val newName = "${agent.agentType}-copy"
        val cloned = agent.copy(
            agentType = newName, source = AgentSource.CUSTOM_PROJECT, filePath = null,
        )
        // Add to in-memory list and table — do NOT persist until Apply
        addAgentInMemory(cloned)
    }

    /**
     * Adds an agent to the in-memory list and the appropriate table model
     * without writing to disk. Selects it and marks dirty so Apply persists it.
     */
    private fun addAgentInMemory(agent: AgentDefinition) {
        allAgents = allAgents + agent
        val isLead = agent.subagents != null
        val model = if (isLead) leadTableModel else subagentTableModel
        model.update(allAgents.filter { if (isLead) it.subagents != null else it.subagents == null })
        selectInTable(agent)
        showAgentConfig(agent)
        markDirty()
    }

    /**
     * Flush ALL pending changes to disk in one shot:
     * 1. Delete files for agents in [pendingDeletes]
     * 2. Save the currently-selected agent's form fields (covers both new and edited agents)
     * 3. Reload from disk to sync state
     */
    private fun flushAllChanges() {
        // 1. Process pending deletions
        for (name in pendingDeletes) {
            val agent = AgentRegistry.loadAll(project.basePath).find { it.agentType == name }
            agent?.filePath?.let { File(it).delete() }
        }

        // 2. Save current agent form fields (new agents get written, existing get updated)
        saveCurrentAgentFields()

        // 3. Reload from disk so allAgents is fully in sync
        reload()
    }

    /** Writes the currently-selected agent's form fields to disk. */
    private fun saveCurrentAgentFields() {
        val agent = currentAgent ?: return
        if (agent.source == AgentSource.BUILT_IN) return

        val name = nameField.text.trim()
        if (name.isBlank() || !AgentRegistry.isValidAgentName(name)) {
            JOptionPane.showMessageDialog(
                this, "Invalid agent name. Use only [a-zA-Z0-9._-].",
                "Validation Error", JOptionPane.ERROR_MESSAGE,
            )
            return
        }

        val model = AgentRegistry.parseModelString(modelCombo.selectedItem as? String)
        val maxTurns = maxTurnsSpinner.value as Int
        val prompt = systemPromptArea.text

        val enabledBuiltIn = builtInToolsModel.getCheckedNames()
        val allBuiltInChecked = enabledBuiltIn.size == builtInToolsModel.rowCount

        val enabledIde = ideToolsModel.getCheckedNames() // display names without ide_ prefix
        val allIdeChecked = enabledIde.size == ideToolsModel.rowCount

        val tools: List<String>? = if (allBuiltInChecked && allIdeChecked) {
            null // all tools enabled — omit from file
        } else {
            val list = mutableListOf<String>()
            list.addAll(enabledBuiltIn)
            // Use "ide" shorthand if all IDE tools checked, else add individual ide_<name> entries
            if (allIdeChecked) {
                list.add("ide")
            } else {
                list.addAll(enabledIde.map { "ide_$it" })
            }
            list
        }

        // Build mcpServers from MCP tool table
        val enabledMcp = mcpToolsModel.getCheckedNames()
        val mcpServers = mutableMapOf<String, McpServerConfig>()
        for (name in enabledMcp) {
            // Preserve existing config if available, otherwise create a stub
            val existing = agent.mcpServers[name]
                ?: CopilotChatSettings.getInstance().mcpServers
                    .find { it.name == name }
                    ?.let { McpServerConfig(command = it.command, args = it.args.split(" ").filter(String::isNotBlank)) }
                ?: McpServerConfig()
            mcpServers[name] = existing
        }

        val subagents = if (agent.subagents != null) {
            val checked = poolModel.getCheckedNames()
            val allWorkers = allAgents.filter { it.subagents == null }
            if (checked.size == allWorkers.size) emptyList() else checked
        } else null

        val handoffs = (0 until handoffsModel.rowCount).mapNotNull {
            handoffsModel.agentAt(it)?.agentType
        }

        val targetVal = targetCombo.selectedItem as? String
        val target = if (targetVal == "(none)") null else targetVal

        val updated = AgentDefinition(
            agentType = name,
            whenToUse = descriptionField.text.trim(),
            tools = tools,
            disallowedTools = agent.disallowedTools,
            source = agent.source,
            model = model,
            systemPromptTemplate = prompt,
            forkContext = forkContextCb.isSelected,
            background = backgroundCb.isSelected,
            maxTurns = maxTurns,
            disableModelInvocation = disableModelCb.isSelected,
            handoffs = handoffs,
            mcpServers = mcpServers,
            metadata = agent.metadata,
            filePath = agent.filePath,
            subagents = subagents,
            target = target,
        )

        val file = if (agent.filePath != null) File(agent.filePath) else {
            val agentDir = File(project.basePath ?: return, ".copilot-chat/agents")
            File(agentDir, "${updated.agentType}.agent.md")
        }
        AgentRegistry.writeAgentFile(updated.copy(filePath = file.absolutePath), file)

        // If agent was renamed, delete the old file
        if (agent.agentType != name && agent.filePath != null) {
            File(agent.filePath).delete()
        }

        currentAgent = updated.copy(filePath = file.absolutePath)
    }

    // ── Selection helpers ──────────────────────────────────────────

    private fun selectByName(name: String) {
        val agent = allAgents.find { it.agentType == name } ?: return
        selectInTable(agent)
    }

    private fun selectInTable(agent: AgentDefinition) {
        val isLead = agent.subagents != null
        agentTabs.selectedIndex = if (isLead) 0 else 1
        val table = if (isLead) leadTable else subagentTable
        val model = if (isLead) leadTableModel else subagentTableModel
        for (i in 0 until model.rowCount) {
            if (model.agentAt(i)?.agentType == agent.agentType) {
                table.setRowSelectionInterval(i, i)
                if (isLead) previousLeadRow = i else previousSubagentRow = i
                return
            }
        }
    }

    // ── UI helpers ─────────────────────────────────────────────────

    private fun agentTable(model: NameDescTableModel): JBTable = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        rowHeight = 26
        tableHeader.reorderingAllowed = false
    }

    private fun checkTable(model: CheckTableModel): JBTable = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        rowHeight = 24
        tableHeader.reorderingAllowed = false
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        // Col 0: checkbox — fixed narrow
        columnModel.getColumn(0).apply {
            preferredWidth = 30; maxWidth = 30; minWidth = 30
        }
    }

    /** Resize col 1 (name) to fit the widest cell content, leaving col 2 to fill the rest. */
    private fun autoFitNameColumn(table: JBTable) {
        if (table.rowCount == 0) return
        val col = 1
        val fm = table.getFontMetrics(table.font)
        var max = fm.stringWidth(table.columnModel.getColumn(col).headerValue?.toString() ?: "")
        for (row in 0 until table.rowCount) {
            val value = table.getValueAt(row, col)?.toString() ?: ""
            max = maxOf(max, fm.stringWidth(value))
        }
        val padded = max + 16
        table.columnModel.getColumn(col).apply {
            preferredWidth = padded; minWidth = padded; maxWidth = padded
        }
    }

    private fun innerTableWithToolbar(
        table: JBTable,
        vararg buttons: Pair<Icon, () -> Unit>,
    ): JPanel {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 1, 0, 1, JBColor(0x393B40, 0x393B40)),
                JBUI.Borders.empty(2, 4),
            )
            for ((icon, action) in buttons) add(iconButton(icon) { action() })
        }
        val tableContainer = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createLineBorder(JBColor(0x393B40, 0x393B40))
            add(table.tableHeader, BorderLayout.NORTH)
            add(table, BorderLayout.CENTER)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(toolbar, BorderLayout.NORTH)
            add(tableContainer, BorderLayout.CENTER)
        }
    }

    private fun collapsible(title: String, expanded: Boolean, content: JComponent): CollapsiblePanel {
        val label = JLabel(title).apply {
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = JBColor(0x8C8E94, 0x8C8E94)
        }
        // Use a BorderLayout content panel to avoid BoxLayout alignment issues
        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(content, BorderLayout.CENTER)
        }
        return CollapsiblePanel(label, wrapper, initiallyExpanded = expanded)
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

    private fun iconButton(icon: Icon, action: () -> Unit): JButton = JButton(icon).apply {
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        margin = JBUI.insets(2)
        preferredSize = Dimension(24, 24)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { action() }
    }

    private fun sourceLabel(s: AgentSource): String = when (s) {
        AgentSource.BUILT_IN -> "built-in"
        AgentSource.CUSTOM_PROJECT -> "project"
        AgentSource.CUSTOM_USER -> "user"
    }

    override fun dispose() {}

    // ── Table models ───────────────────────────────────────────────

    class NameDescTableModel : AbstractTableModel() {
        private val agents = mutableListOf<AgentDefinition>()
        private val cols = arrayOf("Name", "Description")

        fun update(list: List<AgentDefinition>) {
            agents.clear(); agents.addAll(list); fireTableDataChanged()
        }

        fun agentAt(row: Int): AgentDefinition? = agents.getOrNull(row)
        override fun getRowCount() = agents.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(col: Int) = cols[col]
        override fun isCellEditable(row: Int, col: Int) = false

        override fun getValueAt(row: Int, col: Int): Any {
            val a = agents[row]
            return when (col) {
                0 -> a.agentType
                1 -> a.whenToUse.take(80)
                else -> ""
            }
        }
    }

    data class CheckRow(val name: String, val description: String, var checked: Boolean)

    /** Column order: 0=checkbox, 1=name, 2=description. Notifies [onChanged] when a checkbox toggles. */
    class CheckTableModel(
        private val cols: Array<String>,
        private val onChanged: (() -> Unit)? = null,
    ) : AbstractTableModel() {
        private val rows = mutableListOf<CheckRow>()
        var editable = true

        fun update(list: List<CheckRow>) {
            rows.clear(); rows.addAll(list); fireTableDataChanged()
        }

        fun clear() { rows.clear(); fireTableDataChanged() }
        fun getCheckedNames(): List<String> = rows.filter { it.checked }.map { it.name }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(col: Int) = cols[col]
        override fun getColumnClass(col: Int): Class<*> =
            if (col == 0) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(row: Int, col: Int) = col == 0 && editable

        override fun getValueAt(row: Int, col: Int): Any = when (col) {
            0 -> rows[row].checked
            1 -> rows[row].name
            2 -> rows[row].description
            else -> ""
        }

        override fun setValueAt(value: Any?, row: Int, col: Int) {
            if (col == 0 && value is Boolean) {
                rows[row].checked = value
                fireTableCellUpdated(row, col)
                onChanged?.invoke()
            }
        }
    }
}
