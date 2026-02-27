package com.speckit.plugin.ui.onboarding

import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.dsl.gridLayout.UnscaledGapsY
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Container
import java.awt.Dimension
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * Exact replica of FeatureWelcomePanel from JetBrains AI Chat plugin.
 *
 * Shows a single feature detail view with:
 * - Header: "Discover all features" link on left, "Close" link on right
 * - Title (h1 font)
 * - HTML description
 * - Previous / Next navigation buttons
 *
 * Layout structure (from BaseWelcomePanel):
 *   - NORTH: header panel (back link + close)
 *   - CENTER: scrollable content (title + description + nav buttons)
 */
class SpeckitFeaturePanel(
    private val descriptor: SpeckitFeatureDescriptor,
    private val index: Int,
    private val allDescriptors: List<SpeckitFeatureDescriptor>,
    private val onFeatureSelected: (SpeckitFeatureDescriptor, Int) -> Unit,
    private val onDiscoverAll: () -> Unit,
    private val onClose: () -> Unit
) : JPanel(BorderLayout()) {

    init {
        val header = createHeader()
        add(header, BorderLayout.NORTH)

        val content = createContent()
        val scrollPane = JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        add(scrollPane, BorderLayout.CENTER)
    }

    /**
     * Header with "Discover all features" link and "Close" link.
     * Mirrors BaseWelcomePanel.createHeader when onDiscoverAll != null.
     * UnscaledGaps(22, 20, 14, 20) then separator.
     */
    private fun createHeader(): JComponent {
        return panel {
            row {
                link("Discover all features") { onDiscoverAll() }
                    .resizableColumn()
                link("Close") { onClose() }
                    .align(AlignX.RIGHT)
            }
            separator()
        }.apply {
            border = JBUI.Borders.empty(22, 20, 14, 20)
        }
    }

    /**
     * Content: title + description + previous/next buttons.
     * JBUI.Borders.empty(20, 20, 22, 20) matches FeatureWelcomePanel.createContent.
     */
    private fun createContent(): JComponent {
        return panel {
            // Title — wrapped in sub-panel for UnscaledGaps(24, 0, 12, 0)
            // Matches BaseWelcomePanel.title
            panel {
                row {
                    text(descriptor.title)
                        .resizableColumn()
                        .applyToComponent {
                            font = JBFont.h1()
                        }
                }
            }.customize(UnscaledGaps(24, 0, 12, 0))

            // HTML description
            // Matches FeatureWelcomePanel.createContent — EditorPaneWrapper with JBHtmlPane
            val html = descriptor.description.asHtml
            if (html != null) {
                row {
                    cell(EditorPaneWrapper(createDescriptionPane(), html))
                        .align(AlignX.FILL)
                }
            }

            // Previous / Next navigation buttons
            // Matches FeatureWelcomePanel: UnscaledGapsY(24, 0)
            row {
                val prevIndex = index - 1
                if (prevIndex >= 0) {
                    button("Previous") {
                        onFeatureSelected(allDescriptors[prevIndex], prevIndex)
                    }.customize(UnscaledGaps(0, 0, 0, 6))
                }

                val nextIndex = index + 1
                if (nextIndex < allDescriptors.size) {
                    val nextDescriptor = allDescriptors[nextIndex]
                    button("Next: ${nextDescriptor.title}") {
                        onFeatureSelected(nextDescriptor, nextIndex)
                    }
                }
            }.customize(UnscaledGapsY(24, 0))
        }.apply {
            border = JBUI.Borders.empty(20, 20, 22, 20)
        }
    }

    /**
     * Mirrors FeatureWelcomePanel.createDescription.
     * JBHtmlPane with custom style sheet for pre/code wrapping.
     */
    private fun createDescriptionPane(): JBHtmlPane {
        val styleConfig = JBHtmlPaneStyleConfiguration()
        val paneConfig = JBHtmlPaneConfiguration.Builder()
            .customStyleSheet("pre {white-space: pre-wrap;} code, pre, a {overflow-wrap: anywhere;}")
            .build()
        return JBHtmlPane(styleConfig, paneConfig).apply {
            isOpaque = false
        }
    }

    /**
     * Exact replica of FeatureWelcomePanel.EditorPaneWrapper.
     * Wraps a JEditorPane to properly size HTML content to parent width.
     * Min size: JBDimension(50, 10).
     */
    private class EditorPaneWrapper(
        private val component: JEditorPane,
        private val htmlText: String
    ) : JPanel(BorderLayout()) {

        init {
            component.text = htmlText
            add(component, BorderLayout.CENTER)
        }

        override fun updateUI() {
            super.updateUI()
            @Suppress("SENSELESS_COMPARISON")
            if (component != null) {
                component.text = htmlText
            }
        }

        override fun getPreferredSize(): Dimension {
            val parent: Container = parent ?: return minimumSize
            val insets: Insets = parent.insets
            val availableWidth = parent.width - insets.left - insets.right
            component.setSize(Dimension(availableWidth, Short.MAX_VALUE.toInt()))
            val preferredSize = component.preferredSize
            return Dimension(minimumSize.width, preferredSize.height)
        }

        override fun getMinimumSize(): Dimension {
            return JBDimension(50, 10)
        }
    }
}
