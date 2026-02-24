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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

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

    private val readyPlaceholder = "Add context (#), extensions (@), commands (/)"
    private val initPlaceholder = "Starting up..."
    private val placeholderText: String get() = if (isInitializing) initPlaceholder else readyPlaceholder

    private val controlHeight = 32

    // Agent mode dropdown (matches official UI)
    val agentDropdown = JComboBox(arrayOf("Agent", "Ask")).apply {
        selectedIndex = 0
        maximumSize = Dimension(100, controlHeight)
        preferredSize = Dimension(90, controlHeight)
    }

    val isAgentMode: Boolean
        get() = agentDropdown.selectedIndex == 0

    var showAgentDropdown: Boolean = true
        set(value) {
            field = value
            agentDropdown.isVisible = value
        }

    // Model selector — placed on the left next to Agent dropdown
    private var leftPanel: JPanel? = null
    var modelSelector: JComboBox<String>? = null
        set(value) {
            field = value
            if (value != null) {
                value.maximumSize = Dimension(120, controlHeight)
                value.preferredSize = Dimension(100, controlHeight)
                leftPanel?.add(value)
                leftPanel?.revalidate()
            }
        }

    private val rightButtonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
        isOpaque = false
    }

    private val buttonBorderColor = JBColor(0xC4C4C4, 0x4E5157)

    private val sendButton = JButton(AllIcons.Actions.Execute).apply {
        toolTipText = "Send (Enter)"
        isBorderPainted = true
        isContentAreaFilled = false
        isFocusPainted = false
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(buttonBorderColor, 1, true),
            JBUI.Borders.empty(4)
        )
        preferredSize = Dimension(controlHeight, controlHeight)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val stopButton = JButton(AllIcons.Actions.Suspend).apply {
        toolTipText = "Stop generating"
        isVisible = false
        isBorderPainted = true
        isContentAreaFilled = false
        isFocusPainted = false
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(buttonBorderColor, 1, true),
            JBUI.Borders.empty(4)
        )
        preferredSize = Dimension(controlHeight, controlHeight)
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

    // ── Slash command popup ────────────────────────────────────────

    /** Items shown in the "/" popup. Each has a name and description. */
    data class SlashItem(val name: String, val description: String, val tag: String = "Agent")

    /** Current slash command items. Set by the parent panel (e.g. AgentPanel). */
    var slashItems: List<SlashItem> = emptyList()

    private val slashPopup = JPopupMenu()
    private var slashPopupVisible = false

    /** True while the LSP/MCP backend is starting up. Blocks all input. */
    var isInitializing: Boolean = true
        set(value) {
            field = value
            applyInputState()
        }

    var isStreaming: Boolean = false
        set(value) {
            field = value
            applyInputState()
        }

    private fun applyInputState() {
        val blocked = isInitializing || isStreaming
        textArea.isEnabled = !blocked
        sendButton.isVisible = !isStreaming && !isInitializing
        sendButton.isEnabled = !isInitializing
        stopButton.isVisible = isStreaming
        agentDropdown.isEnabled = !blocked
    }

    private fun showSlashPopup(filter: String) {
        val query = filter.lowercase()
        val filtered = if (query.isEmpty()) slashItems
            else slashItems.filter { it.name.lowercase().contains(query) }
        if (filtered.isEmpty()) {
            hideSlashPopup()
            return
        }

        slashPopup.removeAll()
        val nameHex = colorToHex(JBColor(0x000000, 0xBCBEC4))
        val descHex = colorToHex(JBColor(0x999999, 0x666666))
        val agentTagHex = colorToHex(JBColor(0x3574F0, 0x548AF7))
        val subagentTagHex = colorToHex(JBColor(0x7C3AED, 0xA78BFA))
        for (item in filtered) {
            val desc = item.description.take(60)
            val tagHex = if (item.tag == "Subagent") subagentTagHex else agentTagHex
            val menuItem = JMenuItem(
                "<html><font color='$tagHex'>${item.tag}</font>" +
                "&nbsp;&nbsp;<b><font color='$nameHex'>/${item.name}</font></b>" +
                "&nbsp;&nbsp;<font color='$descHex'>$desc</font></html>"
            )
            menuItem.addActionListener {
                applySlashSelection(item.name)
            }
            slashPopup.add(menuItem)
        }

        // Size popup to fit content, at least as wide as the text area
        val popupWidth = maxOf(textArea.width, 300)
        slashPopup.preferredSize = Dimension(popupWidth, slashPopup.preferredSize.height)

        // Position popup above the text area, left-aligned
        slashPopup.show(textArea, 0, -slashPopup.preferredSize.height)
        slashPopupVisible = true

        // Return focus to text area so the user can keep typing
        SwingUtilities.invokeLater { textArea.requestFocusInWindow() }
    }

    private fun hideSlashPopup() {
        if (slashPopupVisible) {
            slashPopup.isVisible = false
            slashPopupVisible = false
        }
    }

    private fun applySlashSelection(name: String) {
        hideSlashPopup()
        // Replace everything from "/" to current caret with "/<name> "
        val text = textArea.text
        val slashStart = text.indexOf('/')
        if (slashStart >= 0) {
            textArea.text = "/${name} " + text.substring(textArea.caretPosition).trimStart()
            textArea.caretPosition = name.length + 2 // after "/<name> "
        }
    }

    private fun handleTextChanged() {
        val text = textArea.text
        if (text.startsWith("/") && !text.contains('\n')) {
            val typed = text.substringAfter("/").substringBefore(" ")
            if (!text.contains(" ")) {
                // Still typing the command — show/filter popup
                showSlashPopup(typed)
            } else {
                hideSlashPopup()
            }
        } else {
            hideSlashPopup()
        }
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
            border = JBUI.Borders.empty(0, 9, 6, 9)
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

        // Assemble: attach row + text area grow in center, toolbar pinned at bottom
        val topSection = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(attachRow, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        val innerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(topSection, BorderLayout.CENTER)
            add(bottomToolbar, BorderLayout.SOUTH)
        }

        add(innerPanel, BorderLayout.CENTER)

        // Initial border
        updateBorderColor(false)

        // Key bindings
        sendButton.addActionListener { sendMessage() }
        stopButton.addActionListener { onStop() }

        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (slashPopupVisible) {
                    when (e.keyCode) {
                        KeyEvent.VK_ESCAPE -> {
                            e.consume()
                            hideSlashPopup()
                            return
                        }
                        KeyEvent.VK_TAB, KeyEvent.VK_ENTER -> {
                            // Select first item in popup
                            if (slashPopup.componentCount > 0) {
                                e.consume()
                                (slashPopup.getComponent(0) as? JMenuItem)?.doClick()
                                return
                            }
                        }
                    }
                }
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    sendMessage()
                }
            }
        })

        // Document listener for slash command detection
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { SwingUtilities.invokeLater { handleTextChanged() } }
            override fun removeUpdate(e: DocumentEvent?) { SwingUtilities.invokeLater { handleTextChanged() } }
            override fun changedUpdate(e: DocumentEvent?) {}
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
        hideSlashPopup()
        val text = textArea.text.trim()
        if (text.isEmpty() || isStreaming || isInitializing) return
        textArea.text = ""
        onSend(text)
    }

    private fun colorToHex(color: Color): String =
        String.format("#%02x%02x%02x", color.red, color.green, color.blue)
}
