package com.citigroup.copilotchat.ui

import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Assistant message component matching the official GitHub Copilot plugin's
 * AbstractMessageComponent → MessageContentPanel → MarkdownPane hierarchy.
 *
 * Official structure (from decompiled bytecode):
 *   AbstractMessageComponent (JPanel, BorderLayout, focusable=false)
 *   ├── CENTER: MessageContentPanel (JPanel(VerticalLayout), opaque=false, focusable=false)
 *   │   └── MarkdownPane (border = DefaultHorizontalBorder = empty(0, 12, 0, 12))
 *   └── SOUTH: BottomLinePanel (toolbar actions — omitted here)
 */
class AssistantMessageComponent(
    val contentPane: MarkdownPane,
) : JPanel(BorderLayout()) {

    private val contentText = StringBuilder()

    // Matches official MessageContentPanel: JPanel(VerticalLayout()), opaque=false, focusable=false
    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isFocusable = false
    }

    init {
        isFocusable = false
        isOpaque = false
        contentPanel.add(contentPane)
        add(contentPanel, BorderLayout.CENTER)
    }

    fun appendText(text: String) {
        contentText.append(text)
        contentPane.update(contentText.toString())
    }

    fun getText(): String = contentText.toString()
}
