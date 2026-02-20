package com.citigroup.copilotchat.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

/**
 * Input panel matching the official GitHub Copilot Chat layout:
 *
 * ┌─────────────────────────────────────────┐  ← round border, focus highlight (blue)
 * │  [📎]                                   │  ← attachment icon
 * │                                         │
 * │  Add context (#), extensions (@), ...   │  ← placeholder text
 * │                                         │
 * ├─────────────────────────────────────────┤
 * │  [Agent ▾] [Auto ▾]        [◇] [▶]    │  ← bottom toolbar
 * └─────────────────────────────────────────┘
 */
class ChatInputPanel(
    private val onSend: (String) -> Unit,
    private val onStop: () -> Unit,
) : JPanel(BorderLayout()) {

    private val placeholderText = "Add context (#), extensions (@), commands (/)"

    // Agent mode dropdown (matches official UI)
    val agentDropdown = JComboBox(arrayOf("Agent", "Ask")).apply {
        selectedIndex = 0
        maximumSize = Dimension(100, 28)
        preferredSize = Dimension(90, 28)
    }

    val isAgentMode: Boolean
        get() = agentDropdown.selectedIndex == 0

    // Model selector — placed on the left next to Agent dropdown
    private var leftPanel: JPanel? = null
    var modelSelector: JComboBox<String>? = null
        set(value) {
            field = value
            if (value != null) {
                value.maximumSize = Dimension(120, 28)
                value.preferredSize = Dimension(100, 28)
                leftPanel?.add(value)
                leftPanel?.revalidate()
            }
        }

    private val rightButtonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
        isOpaque = false
    }

    private val sendButton = JButton(AllIcons.Actions.Execute).apply {
        toolTipText = "Send (Enter)"
        isBorderPainted = false
        isContentAreaFilled = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val stopButton = JButton(AllIcons.Actions.Suspend).apply {
        toolTipText = "Stop generating"
        isVisible = false
        isBorderPainted = false
        isContentAreaFilled = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private val focusBorderColor = JBColor(0x3574F0, 0x3574F0)
    private val unfocusBorderColor = JBColor(0xC4C4C4, 0x4E5157)
    private val inputBgColor = JBColor(0xFFFFFF, 0x2B2D30)
    private val placeholderColor = JBColor(0x999999, 0x666666)

    // Text area with placeholder rendering
    private val textArea = object : JBTextArea(3, 0) {
        init {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(11, 11, 12, 11)
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (text.isEmpty() && !isFocusOwner) {
                val g2 = g as? Graphics2D ?: return
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.color = placeholderColor
                g2.font = font
                val fm = g2.fontMetrics
                val ins = insets
                g2.drawString(placeholderText, ins.left, ins.top + fm.ascent)
            }
        }
    }

    var isStreaming: Boolean = false
        set(value) {
            field = value
            sendButton.isVisible = !value
            stopButton.isVisible = value
            textArea.isEnabled = !value
        }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(12)

        // Attachment icon row
        val attachRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 0, 0)
            add(JButton(AllIcons.General.Pin).apply {
                toolTipText = "Attach context"
                isBorderPainted = false
                isContentAreaFilled = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            })
        }

        // Scroll pane wrapping text area
        val scrollPane = JBScrollPane(textArea).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            isOpaque = false
            viewport.isOpaque = false
            minimumSize = Dimension(0, 60)
        }

        // Focus listeners for border color + placeholder repaint
        textArea.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                textArea.repaint()
                updateBorderColor(true)
            }
            override fun focusLost(e: FocusEvent) {
                textArea.repaint()
                updateBorderColor(false)
            }
        })

        // Bottom toolbar (GridBagLayout)
        val bottomToolbar = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 9, 0, 9)
        }

        // Left: Agent dropdown + Model selector
        leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(agentDropdown)
        }
        bottomToolbar.add(leftPanel, GridBagConstraints().apply {
            fill = GridBagConstraints.NONE
            anchor = GridBagConstraints.SOUTHWEST
            weightx = 1.0
            gridx = 0; gridy = 0
        })

        // Right: Model selector + Send/Stop
        rightButtonPanel.add(sendButton)
        rightButtonPanel.add(stopButton)
        bottomToolbar.add(rightButtonPanel, GridBagConstraints().apply {
            fill = GridBagConstraints.NONE
            anchor = GridBagConstraints.SOUTHEAST
            weightx = 1.0
            gridx = 1; gridy = 0
        })

        // Assemble: attach + text + toolbar
        val innerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(attachRow)
            add(scrollPane)
            add(bottomToolbar)
        }

        add(innerPanel, BorderLayout.CENTER)

        // Initial border
        updateBorderColor(false)

        // Key bindings
        sendButton.addActionListener { sendMessage() }
        stopButton.addActionListener { onStop() }

        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    sendMessage()
                }
            }
        })
    }

    private fun updateBorderColor(focused: Boolean) {
        val color = if (focused) focusBorderColor else unfocusBorderColor
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.empty(12),
            BorderFactory.createLineBorder(color, 1, true)
        )
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as? Graphics2D ?: return
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val insets = JBUI.insets(12)
        g2.color = inputBgColor
        g2.fillRoundRect(insets.left, insets.top,
            width - insets.left - insets.right,
            height - insets.top - insets.bottom,
            8, 8)
    }

    private fun sendMessage() {
        val text = textArea.text.trim()
        if (text.isEmpty() || isStreaming) return
        textArea.text = ""
        onSend(text)
    }
}
