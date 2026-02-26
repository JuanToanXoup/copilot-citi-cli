package com.speckit.plugin.ui

import com.github.copilot.api.CopilotChatService
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel

class SpeckitChatPanel(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val console: ConsoleView
    private val inputField: JBTextField
    private val modeCombo: JComboBox<String>
    private val sendButton: JButton
    private val newChatButton: JButton
    private var sessionId: String? = null

    init {
        Disposer.register(parentDisposable, this)

        console = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .console
        Disposer.register(this, console)

        inputField = JBTextField()
        modeCombo = JComboBox(arrayOf("Agent", "Ask"))
        sendButton = JButton("Send")
        newChatButton = JButton("New Chat")

        val inputBar = JPanel(BorderLayout(4, 0))
        val leftControls = JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)
            add(modeCombo)
            add(newChatButton)
        }
        inputBar.add(leftControls, BorderLayout.WEST)
        inputBar.add(inputField, BorderLayout.CENTER)
        inputBar.add(sendButton, BorderLayout.EAST)

        add(console.component, BorderLayout.CENTER)
        add(inputBar, BorderLayout.SOUTH)

        sendButton.addActionListener { sendMessage() }
        inputField.addActionListener { sendMessage() }
        newChatButton.addActionListener { resetSession() }
    }

    private fun sendMessage() {
        val input = inputField.text.trim()
        if (input.isEmpty()) return

        inputField.text = ""
        val mode = modeCombo.selectedItem as String
        console.print("\n> [$mode] $input\n", ConsoleViewContentType.USER_INPUT)

        val chatService = project.getService(CopilotChatService::class.java)
        if (chatService == null) {
            console.print("CopilotChatService not available\n", ConsoleViewContentType.ERROR_OUTPUT)
            return
        }

        sendButton.isEnabled = false
        val dataContext = SimpleDataContext.getProjectContext(project)

        chatService.query(dataContext) {
            withInput(input)
            if (mode == "Agent") withAgentMode() else withAskMode()

            val currentSessionId = sessionId
            if (currentSessionId != null) {
                withExistingSession(currentSessionId)
            } else {
                withCurrentSession()
            }

            withSessionIdReceiver { id ->
                sessionId = id
            }

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

    private fun resetSession() {
        sessionId = null
        console.print("\n--- New chat session ---\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    override fun dispose() {}
}
