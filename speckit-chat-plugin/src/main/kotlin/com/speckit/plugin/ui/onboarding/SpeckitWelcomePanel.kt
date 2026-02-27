package com.speckit.plugin.ui.onboarding

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGapsY
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.speckit.plugin.ui.SpeckitInstaller
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * Welcome landing page — the initial screen shown in the Onboarding tab.
 *
 * Layout:
 *   - Product title h1 ("Welcome to Speckit", UnscaledGapsY(0, 8))
 *   - Subtitle/greeting text
 *   - Setup callout: download button + GitHub link
 *   - Top 5 feature DisclosureButtons with icons (flat list, no category headers)
 *   - "Discover all features" link
 *
 * All wrapped in a borderless scroll pane. Panel is opaque=false.
 */
class SpeckitWelcomePanel(
    private val project: Project,
    private val onFeatureSelected: (SpeckitFeatureDescriptor, Int) -> Unit,
    private val onDiscoverAll: () -> Unit,
    private val allDescriptors: List<SpeckitFeatureDescriptor>
) : JPanel(BorderLayout()) {

    /**
     * Top 5 features shown on the welcome page — flat list, no categories.
     */
    private fun welcomeFeatureDescriptors(): List<SpeckitFeatureDescriptor> = listOf(
        DiscoveryFeatureDescriptor,
        ConstitutionFeatureDescriptor,
        SpecifyFeatureDescriptor,
        PlanFeatureDescriptor,
        TasksFeatureDescriptor,
        ImplementFeatureDescriptor
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
            // ── Title row ───────────────────────────────────────────────────
            row {
                text("Welcome to Speckit")
                    .applyToComponent {
                        font = JBFont.h1()
                    }
            }.customize(UnscaledGapsY(0, 8))

            // ── Greeting / subtitle row ─────────────────────────────────────
            row {
                text("Here is how Speckit can help you:")
            }

            // ── Setup callout ────────────────────────────────────────────────
            row {
                text("Download the latest Speckit agents into your project to get started.")
            }.customize(UnscaledGapsY(16, 0))

            row {
                button("Init Speckit") {
                    SpeckitInstaller.install(project)
                }.applyToComponent {
                    icon = AllIcons.Actions.Download
                }
                link("View on GitHub") {
                    BrowserUtil.browse("https://github.com/github/spec-kit")
                }
            }.customize(UnscaledGapsY(8, 0))

            // ── Feature buttons (flat list with icons) ──────────────────────
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
            row {
                link("Discover all features") { onDiscoverAll() }
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 20, 20, 20)
        }
    }
}
