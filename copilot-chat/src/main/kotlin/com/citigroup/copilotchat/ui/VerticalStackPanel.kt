package com.citigroup.copilotchat.ui

import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.Scrollable

/**
 * Port of the official Copilot plugin's VerticalStackWrappingPanel.
 *
 * Uses GridBagLayout to stack messages vertically, each filling the full width.
 * Implements Scrollable so that:
 *  - getScrollableTracksViewportWidth() = true → messages wrap to scroll pane width
 *  - getScrollableTracksViewportHeight() = false → vertical scrolling works
 */
class VerticalStackPanel : JPanel(GridBagLayout()), Scrollable {

    private var currentGridY = 0

    override fun add(comp: Component): Component {
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridwidth = 1
            gridx = 0
            gridy = currentGridY++
        }
        super.add(comp, gbc)
        return comp
    }

    override fun removeAll() {
        super.removeAll()
        currentGridY = 0
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 10

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 100
}
