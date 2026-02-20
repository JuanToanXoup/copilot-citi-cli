package com.citigroup.copilotchat.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

/**
 * User message displayed as a right-aligned bubble, matching the official
 * GitHub Copilot plugin's MessageContentBubble.buildRightAlignedBubble().
 *
 * Uses JEditorPane with HTML (like the official plugin's MarkdownPane) instead
 * of JTextArea, because JEditorPane's HTML view has correct preferred-size
 * behavior for text wrapping — unlike JTextArea whose WrappedPlainView has a
 * margin/inset mismatch that causes incorrect sizing.
 */
class UserMessageComponent(private val text: String) : JPanel(GridBagLayout()) {

    companion object {
        private val BUBBLE_BG = JBColor(0xD4E2FF, 0x264F78)
        private const val ARC = 16
        private const val MAX_WIDTH_FRACTION = 0.75
    }

    init {
        isOpaque = false
        isFocusable = false

        val escapedText = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")

        val kit = HTMLEditorKit()
        val ss = StyleSheet()
        ss.addRule("body { margin: 0; padding: 0; }")
        kit.styleSheet = ss

        val textPane = JEditorPane().apply {
            editorKit = kit
            this.text = "<html><body>$escapedText</body></html>"
            isEditable = false
            isOpaque = false
            isFocusable = true
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = UIManager.getFont("Label.font")
            border = BorderFactory.createEmptyBorder()
            selectionColor = JBColor(0xB3D7FF, 0x214283)
            selectedTextColor = JBColor(0x000000, 0xFFFFFF)
        }

        val bubble = object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(8, 12)
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g as? Graphics2D ?: return
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = BUBBLE_BG
                g2.fillRoundRect(0, 0, width, height, ARC, ARC)
                super.paintComponent(g)
            }

            override fun getPreferredSize(): Dimension {
                val containerWidth = this@UserMessageComponent.parent?.width?.takeIf { it > 0 }
                val maxBubbleWidth = if (containerWidth != null) {
                    (containerWidth * MAX_WIDTH_FRACTION).toInt().coerceAtLeast(100)
                } else {
                    400
                }
                val bubbleInsets = insets
                val availableContentWidth = maxBubbleWidth - bubbleInsets.left - bubbleInsets.right

                // JEditorPane with HTML: preferredSize.width = natural unwrapped text width
                val textPref = textPane.preferredSize
                val naturalBubbleWidth = textPref.width + bubbleInsets.left + bubbleInsets.right

                if (naturalBubbleWidth <= maxBubbleWidth) {
                    return Dimension(naturalBubbleWidth, textPref.height + bubbleInsets.top + bubbleInsets.bottom)
                }

                // Text exceeds max width — constrain and let HTML view wrap
                textPane.setSize(availableContentWidth, Short.MAX_VALUE.toInt())
                val wrappedPref = textPane.preferredSize
                return Dimension(
                    maxBubbleWidth,
                    wrappedPref.height + bubbleInsets.top + bubbleInsets.bottom
                )
            }
        }
        bubble.add(textPane, BorderLayout.CENTER)

        // Filler to push bubble to the right (official: weightx=1.0, fill=BOTH)
        val fillerConstraints = GridBagConstraints().apply {
            gridx = 0; gridy = 0
            fill = GridBagConstraints.BOTH
            weightx = 1.0; weighty = 1.0
        }
        add(JPanel().apply { isOpaque = false; isFocusable = false }, fillerConstraints)

        // Bubble constraint (official: fill=NONE, anchor=NORTHEAST)
        val bubbleConstraints = GridBagConstraints().apply {
            gridx = 1; gridy = 0
            fill = GridBagConstraints.NONE
            anchor = GridBagConstraints.NORTHEAST
        }
        add(bubble, bubbleConstraints)
    }
}
