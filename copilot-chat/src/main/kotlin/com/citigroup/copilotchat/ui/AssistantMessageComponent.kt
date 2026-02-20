package com.citigroup.copilotchat.ui

import com.intellij.util.ui.JBUI
import javax.swing.*

/**
 * Assistant message matching the official GitHub Copilot Chat UI.
 * Just the markdown content directly, with an optional step panel for tool calls.
 *
 * Layout:
 *  ┌─────────────────────────────────────────────────┐
 *  │ [markdown content here...]                      │
 *  ├─────────────────────────────────────────────────┤
 *  │ ▸ 3 steps completed                            │  ← stepPanel (optional)
 *  └─────────────────────────────────────────────────┘
 */
class AssistantMessageComponent(
    val contentPane: JEditorPane,
    private val messageRenderer: MessageRenderer,
) : JPanel() {

    val stepPanel = StepPanelComponent()
    private val contentText = StringBuilder()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(0, 0, 4, 0)

        contentPane.alignmentX = LEFT_ALIGNMENT
        add(contentPane)

        stepPanel.alignmentX = LEFT_ALIGNMENT
        add(stepPanel)
    }

    fun appendText(text: String) {
        contentText.append(text)
        contentPane.text = messageRenderer.renderMarkdown(contentText.toString())
    }

    fun addStep(toolName: String, status: String = "running") {
        stepPanel.addStep(toolName, status)
    }

    fun updateStep(toolName: String, status: String) {
        stepPanel.updateStep(toolName, status)
    }

    fun getText(): String = contentText.toString()
}
