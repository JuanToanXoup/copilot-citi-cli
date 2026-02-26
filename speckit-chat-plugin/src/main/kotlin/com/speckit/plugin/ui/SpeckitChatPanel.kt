package com.speckit.plugin.ui

import com.github.copilot.agent.session.CopilotAgentSessionManager
import com.github.copilot.api.CopilotChatService
import com.github.copilot.chat.window.ShowChatToolWindowsListener
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.speckit.plugin.tools.ResourceLoader
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SpeckitChatPanel(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val console: ConsoleView
    private val agentCombo: JComboBox<AgentEntry>
    private val argField: JBTextArea
    private val sendButton: JButton
    private val newChatButton: JButton
    private val refreshButton: JButton
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        Disposer.register(parentDisposable, this)

        console = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .console
        Disposer.register(this, console)

        agentCombo = JComboBox<AgentEntry>().apply {
            preferredSize = Dimension(250, preferredSize.height)
        }
        argField = JBTextArea(3, 0).apply {
            lineWrap = true
            wrapStyleWord = true
        }
        sendButton = JButton("Send")
        newChatButton = JButton("New Chat")
        refreshButton = JButton("Refresh")

        val argScrollPane = JBScrollPane(argField).apply {
            preferredSize = Dimension(0, 60)
        }
        val topBar = JPanel(BorderLayout(4, 0))
        topBar.add(argScrollPane, BorderLayout.CENTER)
        topBar.add(sendButton, BorderLayout.EAST)

        val bottomBar = JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)
            add(JLabel("Agent:"))
            add(agentCombo)
            add(newChatButton)
            add(refreshButton)
        }

        val topPanel = JPanel(BorderLayout(0, 4))
        topPanel.add(topBar, BorderLayout.NORTH)
        topPanel.add(bottomBar, BorderLayout.SOUTH)

        add(topPanel, BorderLayout.NORTH)
        add(console.component, BorderLayout.CENTER)

        sendButton.addActionListener { sendMessage() }
        val ctrlEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK)
        argField.getInputMap().put(ctrlEnter, "send")
        argField.getActionMap().put("send", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { sendMessage() }
        })
        newChatButton.addActionListener { newChat() }
        refreshButton.addActionListener { loadAgents() }

        loadAgents()
    }

    private fun loadAgents() {
        val basePath = project.basePath ?: return
        val agentFiles = ResourceLoader.listAgents(basePath)
        val entries = agentFiles.map { fileName ->
            val slug = fileName.removePrefix("speckit.").removeSuffix(".agent.md")
            val content = ResourceLoader.readAgent(basePath, fileName)
            val description = content?.let { parseDescription(it) } ?: ""
            AgentEntry(fileName, slug, description)
        }
        agentCombo.model = DefaultComboBoxModel(entries.toTypedArray())
    }

    private fun sendMessage() {
        val agent = agentCombo.selectedItem as? AgentEntry ?: return
        val argument = argField.text.trim()
        val basePath = project.basePath ?: return

        val agentContent = ResourceLoader.readAgent(basePath, agent.fileName)
        if (agentContent == null) {
            console.print("Agent not found: ${agent.fileName}\n", ConsoleViewContentType.ERROR_OUTPUT)
            return
        }

        val prompt = agentContent.replace("\$ARGUMENTS", argument)

        argField.text = ""

        val chatService = project.getService(CopilotChatService::class.java)
        if (chatService == null) {
            console.print("CopilotChatService not available\n", ConsoleViewContentType.ERROR_OUTPUT)
            return
        }

        sendButton.isEnabled = false
        val dataContext = SimpleDataContext.getProjectContext(project)

        chatService.query(dataContext) {
            withInput(prompt)
            withAgentMode()
            withNewSession()

            onComplete {
                invokeLater {
                    console.print("Response delivered to Copilot Chat\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                    sendButton.isEnabled = true
                }
            }

            onError { message, _, _, _, _ ->
                invokeLater {
                    console.print("ERROR: $message\n", ConsoleViewContentType.ERROR_OUTPUT)
                    sendButton.isEnabled = true
                }
            }

            onCancel {
                invokeLater {
                    console.print("Cancelled\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                    sendButton.isEnabled = true
                }
            }
        }

        console.print("Sending to Copilot Chat...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    private fun newChat() {
        val sessionManager = project.service<CopilotAgentSessionManager>()

        scope.launch {
            try {
                val session = sessionManager.createSession { }
                sessionManager.activateSession(session.id)
                invokeLater {
                    if (project.isDisposed) return@invokeLater
                    project.messageBus
                        .syncPublisher(ShowChatToolWindowsListener.TOPIC)
                        .showChatToolWindow()
                    console.print("\n--- New chat session created ---\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                }
            } catch (e: Exception) {
                invokeLater {
                    console.print("Failed to create new session: ${e.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                }
            }
        }
    }

    override fun dispose() {}
}

private fun parseDescription(content: String): String {
    val match = Regex("^description:\\s*(.+)$", RegexOption.MULTILINE).find(content)
    return match?.groupValues?.get(1)?.trim() ?: ""
}

private data class AgentEntry(
    val fileName: String,
    val slug: String,
    val description: String
) {
    override fun toString(): String {
        return if (description.isNotEmpty()) "$slug - $description" else slug
    }
}
