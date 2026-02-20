package com.citigroup.copilotchat.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

/**
 * User message displayed as a right-aligned bubble, matching the official
 * GitHub Copilot plugin's MessageContentBubble.buildRightAlignedBubble().
 *
 * Official structure (from decompiled bytecode):
 *   wrapper (JPanel, GridBagLayout, opaque=false, focusable=false)
 *   ├── filler (gridx=0, fill=BOTH, weightx=1.0) — pushes bubble right
 *   └── bubble (gridx=1, fill=NONE, anchor=NORTHEAST)
 *       └── content (MarkdownPane or text)
 *
 * Default bubble background: JBColor(0xD4E2FF, 0x264F78)
 * Arc: rounded corners via paintComponent
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

        val textArea = JTextArea(text).apply {
            isEditable = false
            isOpaque = false
            isFocusable = true
            lineWrap = true
            wrapStyleWord = true
            font = UIManager.getFont("Label.font")
            border = BorderFactory.createEmptyBorder()
            selectionColor = JBColor(0xB3D7FF, 0x214283)
            selectedTextColor = JBColor(0x000000, 0xFFFFFF)
        }

        // Bubble panel with rounded background.
        // Override getPreferredSize to dynamically compute width from the container,
        // preventing the JTextArea preferred-size caching issue on resize.
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
                val availableTextWidth = maxBubbleWidth - bubbleInsets.left - bubbleInsets.right

                // Compute natural (unwrapped) text width from font metrics
                val fm = textArea.getFontMetrics(textArea.font)
                val maxLineWidth = text.lines().maxOfOrNull { fm.stringWidth(it) } ?: 0
                val naturalTextWidth = maxLineWidth + textArea.insets.let { it.left + it.right }

                val effectiveTextWidth = naturalTextWidth.coerceAtMost(availableTextWidth).coerceAtLeast(1)

                // Standard Swing pattern: set size then query preferred to get wrapped height
                textArea.setSize(effectiveTextWidth, Short.MAX_VALUE.toInt())
                val textPref = textArea.preferredSize

                return Dimension(
                    effectiveTextWidth + bubbleInsets.left + bubbleInsets.right,
                    textPref.height + bubbleInsets.top + bubbleInsets.bottom
                )
            }
        }
        bubble.add(textArea, BorderLayout.CENTER)

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
