package com.citigroup.copilotchat.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.JTextArea
import javax.swing.UIManager
import javax.swing.text.DefaultCaret

class MarkdownPane(markdown: String) : JTextArea(markdown) {

    init {
        isOpaque = false
        isFocusable = true
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = UIManager.getFont("Label.font")
        border = BorderFactory.createEmptyBorder(0, 12, 0, 12)

        // Visible selection colors for both light and dark themes
        selectionColor = JBColor(0xB3D7FF, 0x214283)
        selectedTextColor = JBColor(0x000000, 0xFFFFFF)

        caret = object : DefaultCaret() {
            override fun adjustVisibility(nloc: Rectangle?) {
                // no-op: prevent auto-scrolling
            }
        }
    }

    fun update(markdown: String) {
        ApplicationManager.getApplication().invokeLater {
            text = markdown
            revalidate()
            repaint()
        }
    }
}

class MessageRenderer {
    fun createMessagePane(): MarkdownPane = MarkdownPane("")
}
