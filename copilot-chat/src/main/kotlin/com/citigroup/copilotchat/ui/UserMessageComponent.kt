package com.citigroup.copilotchat.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

/**
 * User message displayed as a right-aligned compact pill/bubble,
 * matching the official GitHub Copilot Chat UI.
 */
class UserMessageComponent(private val text: String) : JPanel(BorderLayout()) {

    companion object {
        private val PILL_BG = JBColor(0x4B6A9B, 0x4B6A9B)
        private val PILL_FG = JBColor(0xFFFFFF, 0xFFFFFF)
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(0)

        val pill = object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(6, 12, 6, 12)
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g as? Graphics2D ?: return
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = PILL_BG
                g2.fillRoundRect(0, 0, width, height, 14, 14)
                super.paintComponent(g)
            }
        }

        val label = JLabel("<html>${escapeHtml(text)}</html>").apply {
            foreground = PILL_FG
            font = font.deriveFont(13f)
        }
        pill.add(label, BorderLayout.CENTER)

        val wrapper = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(pill)
        }

        add(wrapper, BorderLayout.CENTER)
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
}
