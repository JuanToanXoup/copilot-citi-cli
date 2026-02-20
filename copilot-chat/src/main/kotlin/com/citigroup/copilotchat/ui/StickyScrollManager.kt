package com.citigroup.copilotchat.ui

import java.awt.event.AdjustmentEvent
import java.awt.event.AdjustmentListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * Port of the official Copilot plugin's StickyScrollingManager.
 *
 * Manages auto-scroll behavior for the chat messages panel:
 *  - Auto-scrolls to bottom when new content arrives (sticky mode)
 *  - Stops auto-scrolling when user manually scrolls up (unsticky)
 *  - Re-enables sticky mode when user scrolls back to bottom
 *  - Debounces to prevent scroll jitter during streaming
 *  - Suppresses auto-scroll briefly after external/programmatic scroll events
 */
class StickyScrollManager(
    private val scrollPane: JScrollPane,
    private val target: JComponent,
) {
    private var isSticky = true
    private var lastVerticalValue = 0
    private var debounceCount = 2
    private var currentDebounceCount = 0
    private var lastExternalScrollTime = 0L
    private val externalScrollSuppressionWindow = 100L // ms

    private var userDragging = false

    init {
        val scrollBar = scrollPane.verticalScrollBar

        // Detect manual user scrolling via mouse drag on scrollbar
        scrollBar.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                userDragging = true
            }

            override fun mouseReleased(e: MouseEvent) {
                userDragging = false
                checkStickyState()
            }
        })

        // Track scroll position changes
        scrollBar.addAdjustmentListener(object : AdjustmentListener {
            override fun adjustmentValueChanged(e: AdjustmentEvent) {
                if (userDragging) {
                    // User is manually scrolling
                    checkStickyState()
                }
                lastVerticalValue = e.value
            }
        })

        // Mouse wheel on the scroll pane itself
        scrollPane.addMouseWheelListener { e ->
            if (e.wheelRotation < 0) {
                // Scrolling up — user wants to read history
                isSticky = false
            } else {
                // Scrolling down — check if at bottom
                checkStickyState()
            }
        }
    }

    /**
     * Check if the user has scrolled to the bottom and re-enable sticky mode.
     */
    private fun checkStickyState() {
        val sb = scrollPane.verticalScrollBar
        val atBottom = sb.value + sb.visibleAmount >= sb.maximum - 10
        isSticky = atBottom
    }

    /**
     * Call this when new content is added to the messages panel.
     * Will auto-scroll to bottom if in sticky mode.
     */
    fun onContentAdded() {
        if (!isSticky) return

        // Debounce rapid updates during streaming
        currentDebounceCount++
        if (currentDebounceCount < debounceCount) return
        currentDebounceCount = 0

        scrollToBottom()
    }

    /**
     * Force scroll to bottom and enable sticky mode.
     */
    fun scrollToBottom() {
        if (shouldSuppressAutoScroll()) return

        lastExternalScrollTime = System.currentTimeMillis()
        SwingUtilities.invokeLater {
            val sb = scrollPane.verticalScrollBar
            sb.value = sb.maximum
            isSticky = true
        }
    }

    /**
     * Force sticky mode on (e.g., when user sends a new message).
     */
    fun forceSticky() {
        isSticky = true
        currentDebounceCount = 0
        scrollToBottom()
    }

    private fun shouldSuppressAutoScroll(): Boolean {
        return System.currentTimeMillis() - lastExternalScrollTime < externalScrollSuppressionWindow
    }
}
