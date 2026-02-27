package com.speckit.plugin.ui.onboarding

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.ui.dsl.gridLayout.UnscaledGapsY
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * Welcome landing page — the initial screen shown in the Onboarding tab.
 * Mirrors WelcomeUIUtilsKt.createShortDiscoverAI from JetBrains AI Chat plugin.
 *
 * Layout:
 *   - Logo icon row (centered, UnscaledGapsY(30, 30))
 *   - Product title h1 ("Welcome to Speckit", UnscaledGapsY(0, 8))
 *   - Subtitle/greeting text
 *   - Top 5 feature DisclosureButtons with icons (flat list, no category headers)
 *     topGap=20, bottomGap=22
 *   - "Discover all features" link
 *
 * All wrapped in a borderless scroll pane. Panel is opaque=false.
 */
class SpeckitWelcomePanel(
    private val onFeatureSelected: (SpeckitFeatureDescriptor, Int) -> Unit,
    private val onDiscoverAll: () -> Unit,
    private val allDescriptors: List<SpeckitFeatureDescriptor>
) : JPanel(BorderLayout()) {

    /**
     * Top 5 features shown on the welcome page — flat list, no categories.
     * Mirrors WelcomeUIUtilsKt.welcomeFeatureDescriptors().
     */
    private fun welcomeFeatureDescriptors(): List<SpeckitFeatureDescriptor> = listOf(
        SpecifyFeatureDescriptor,
        PlanFeatureDescriptor,
        TasksFeatureDescriptor,
        ImplementFeatureDescriptor,
        ConstitutionFeatureDescriptor
    )

    init {
        val content = createContent()
        val scrollPane = JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        add(scrollPane, BorderLayout.CENTER)
    }

    private fun createContent(): JComponent {
        val descriptors = welcomeFeatureDescriptors()

        return panel {
            // ── Logo icon row ───────────────────────────────────────────────
            // Matches createShortDiscoverAI: icon row with UnscaledGapsY(30, 30)
            row {
                icon(AllIcons.Actions.Lightning)
            }.customize(UnscaledGapsY(30, 30))

            // ── Title row ───────────────────────────────────────────────────
            // Matches: h1 "Welcome to {ProductName}", UnscaledGapsY(0, 8)
            row {
                text("Welcome to Speckit")
                    .applyToComponent {
                        font = JBFont.h1()
                    }
            }.customize(UnscaledGapsY(0, 8))

            // ── Greeting / subtitle row ─────────────────────────────────────
            // Matches: description text with word wrap
            row {
                text("Here is how Speckit can help you:")
            }

            // ── Feature buttons (flat list with icons) ──────────────────────
            // Matches createFeaturesButtons: showIcons=true, topGap=20, bottomGap=22
            descriptors.forEachIndexed { i, descriptor ->
                val featureIndex = allDescriptors.indexOf(descriptor)
                row {
                    cell(SpeckitDisclosureButton().apply {
                        isOpaque = false
                        text = descriptor.title
                        this.icon = descriptor.icon
                        addActionListener {
                            onFeatureSelected(descriptor, featureIndex)
                        }
                    }).align(AlignX.FILL)
                }.customize(
                    UnscaledGapsY(
                        top = if (i == 0) 20 else 0,
                        bottom = if (i == descriptors.size - 1) 22 else 6
                    )
                )
            }

            // ── "Discover all features" link ────────────────────────────────
            // Matches createShortDiscoverAI: link at bottom
            row {
                link("Discover all features") { onDiscoverAll() }
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 20, 20, 20)
        }
    }
}
