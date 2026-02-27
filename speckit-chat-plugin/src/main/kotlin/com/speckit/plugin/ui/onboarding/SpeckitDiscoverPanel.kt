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
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * Exact replica of DiscoverAIWelcomePanel from JetBrains AI Chat plugin.
 *
 * Shows categorized feature list with DisclosureButtons. Header has
 * "Discover Features" title on left and "Close" link on right.
 * Content is scrollable with JBUI.Borders.empty(12, 20, 0, 20).
 *
 * Layout structure (from BaseWelcomePanel):
 *   - NORTH: header panel (title + close link, with separator)
 *   - CENTER: scrollable content (category blocks with feature buttons)
 */
class SpeckitDiscoverPanel(
    private val onFeatureSelected: (SpeckitFeatureDescriptor, Int) -> Unit,
    private val onClose: () -> Unit
) : JPanel(BorderLayout()) {

    // ── Feature categories (ordered to match pipeline flow) ─────────────────

    private fun gettingStartedDescriptors(): List<SpeckitFeatureDescriptor> = listOf(
        DiscoveryFeatureDescriptor,
        ConstitutionFeatureDescriptor
    )

    private fun specifyDesignDescriptors(): List<SpeckitFeatureDescriptor> = listOf(
        SpecifyFeatureDescriptor,
        ClarifyFeatureDescriptor,
        PlanFeatureDescriptor
    )

    private fun tasksValidationDescriptors(): List<SpeckitFeatureDescriptor> = listOf(
        TasksFeatureDescriptor,
        ChecklistFeatureDescriptor,
        AnalyzeFeatureDescriptor
    )

    private fun implementationDescriptors(): List<SpeckitFeatureDescriptor> = listOf(
        ImplementFeatureDescriptor,
        CoverageFeatureDescriptor,
        IssuesFeatureDescriptor
    )

    // ── Ordered list for navigation service ─────────────────────────────────

    val allDescriptors: List<SpeckitFeatureDescriptor> by lazy {
        gettingStartedDescriptors() + specifyDesignDescriptors() + tasksValidationDescriptors() + implementationDescriptors()
    }

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
     * Header: "Discover Features" (h3 bold) on left, "Close" link on right.
     * Matches BaseWelcomePanel.createHeader: UnscaledGaps(22, 20, 14, 20).
     */
    private fun createHeader(): JComponent {
        return panel {
            row {
                text("Discover Features")
                    .applyToComponent {
                        font = JBFont.h3().asBold()
                    }
                    .resizableColumn()
                link("Close") { onClose() }
                    .align(AlignX.RIGHT)
            }
        }.apply {
            border = JBUI.Borders.empty(22, 20, 14, 20)
        }
    }

    /**
     * Content: category blocks with feature buttons.
     * JBUI.Borders.empty(12, 20, 0, 20) matches DiscoverAIWelcomePanel.createContent.
     */
    private fun createContent(): JComponent {
        return panel {
            featuresBlock("Getting started", AllIcons.Nodes.HomeFolder, gettingStartedDescriptors())
            featuresBlock("Specify & design", AllIcons.Actions.Edit, specifyDesignDescriptors())
            featuresBlock("Tasks & validation", AllIcons.Vcs.Changelist, tasksValidationDescriptors())
            featuresBlock("Implementation", AllIcons.Actions.Execute, implementationDescriptors())
        }.apply {
            border = JBUI.Borders.empty(12, 20, 0, 20)
        }
    }

    /**
     * Mirrors BaseWelcomePanel.featuresBlock:
     * - Caption sub-panel with icon + bold text, UnscaledGaps(12, 0, 12, 0)
     * - Then one DisclosureButton per feature
     */
    private fun com.intellij.ui.dsl.builder.Panel.featuresBlock(
        caption: String,
        icon: Icon,
        descriptors: List<SpeckitFeatureDescriptor>
    ) {
        if (descriptors.isEmpty()) return

        // Category header — wrap in sub-panel so we can use UnscaledGaps (4-side)
        // Matches featuresBlock$lambda$12: panel { row { icon, text } }.customize(UnscaledGaps(12, 0, 12, 0))
        panel {
            row {
                icon(icon)
                text(caption).bold()
            }
        }.customize(UnscaledGaps(12, 0, 12, 0))

        // Feature buttons — each in its own row
        // Matches WelcomeUIUtilsKt.createFeaturesButtons
        createFeaturesButtons(descriptors)
    }

    /**
     * Mirrors WelcomeUIUtilsKt.createFeaturesButtons exactly.
     * Row.customize takes UnscaledGapsY (top, bottom only).
     * Gap logic: first item topGap=0, last item bottomGap=12, others gap=6.
     */
    private fun com.intellij.ui.dsl.builder.Panel.createFeaturesButtons(
        descriptors: List<SpeckitFeatureDescriptor>
    ) {
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
                    top = 0,
                    bottom = if (i == descriptors.size - 1) 12 else 6
                )
            )
        }
    }
}
