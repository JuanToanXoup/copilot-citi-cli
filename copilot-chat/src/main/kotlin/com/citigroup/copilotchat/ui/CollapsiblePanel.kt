package com.citigroup.copilotchat.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Port of the official Copilot plugin's CollapsiblePanel.
 *
 * Displays a header with a chevron icon that toggles between expanded/collapsed states.
 * Supports keyboard navigation (Left=collapse, Right=expand, Space=toggle).
 * Uses CardLayout for the chevron icon swap, matching the official implementation.
 */
class CollapsiblePanel(
    trigger: JComponent,
    private val contentPanel: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    },
    initiallyExpanded: Boolean = false,
) : JPanel() {

    companion object {
        private const val COLLAPSE = "collapse"
        private const val EXPAND = "expand"
    }

    private val cardLayout = CardLayout()
    private val chevronPanel = JPanel(cardLayout).apply {
        isOpaque = false
        add(JLabel(AllIcons.General.ArrowRight), EXPAND)
        add(JLabel(AllIcons.General.ArrowDown), COLLAPSE)
    }

    var isExpanded: Boolean = initiallyExpanded
        private set

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false

        // Header row: chevron + trigger component
        val headerWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val headerContent = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(chevronPanel)
            add(trigger)
        }
        headerWrapper.add(headerContent, BorderLayout.CENTER)

        // Click to toggle
        headerWrapper.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = toggle()
        })

        // Keyboard bindings
        val inputMap = headerWrapper.getInputMap(WHEN_FOCUSED)
        val actionMap = headerWrapper.actionMap
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "hide")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "show")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle")
        actionMap.put("hide", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = collapse()
        })
        actionMap.put("show", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = expand()
        })
        actionMap.put("toggle", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = toggle()
        })

        // Focus styling
        headerWrapper.isFocusable = true
        headerWrapper.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) {
                headerWrapper.border = BorderFactory.createLineBorder(
                    JBColor(0x3574F0, 0x3574F0), 1, true
                )
            }
            override fun focusLost(e: java.awt.event.FocusEvent) {
                headerWrapper.border = null
            }
        })

        add(headerWrapper)
        add(contentPanel)

        // Set initial state
        contentPanel.isVisible = initiallyExpanded
        cardLayout.show(chevronPanel, if (initiallyExpanded) COLLAPSE else EXPAND)
    }

    fun toggle() {
        if (isExpanded) collapse() else expand()
    }

    fun expand() {
        isExpanded = true
        contentPanel.isVisible = true
        cardLayout.show(chevronPanel, COLLAPSE)
        revalidate()
        repaint()
    }

    fun collapse() {
        isExpanded = false
        contentPanel.isVisible = false
        cardLayout.show(chevronPanel, EXPAND)
        revalidate()
        repaint()
    }

    fun getContent(): JPanel = contentPanel
}
