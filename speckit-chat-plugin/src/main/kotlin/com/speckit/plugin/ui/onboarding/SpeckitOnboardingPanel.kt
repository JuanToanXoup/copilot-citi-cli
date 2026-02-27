package com.speckit.plugin.ui.onboarding

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Mirrors AIAssistantContainerPanelVmImpl screen navigation.
 *
 * Container panel that switches between three screens:
 *   1. SpeckitWelcomePanel  — initial landing page (like createShortDiscoverAI)
 *   2. SpeckitDiscoverPanel — full categorized feature list (like DiscoverAIWelcomePanel)
 *   3. SpeckitFeaturePanel  — feature detail with Previous/Next (like FeatureWelcomePanel)
 *
 * Uses a simple replace: remove current, add new, revalidate.
 */
class SpeckitOnboardingPanel(
    private val project: Project,
    parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    /** Ordered list of all feature descriptors for navigation. */
    private lateinit var allDescriptors: List<SpeckitFeatureDescriptor>

    init {
        Disposer.register(parentDisposable, this)
        // Build the ordered descriptor list from the discover panel
        initDescriptors()
        // Start with the welcome landing page
        showWelcomePanel()
    }

    private fun initDescriptors() {
        val discoverPanel = SpeckitDiscoverPanel(
            onFeatureSelected = { _, _ -> },
            onClose = {}
        )
        allDescriptors = discoverPanel.allDescriptors
    }

    /**
     * Show the welcome landing page — initial entry point.
     * Mirrors the createShortDiscoverAI flow.
     */
    private fun showWelcomePanel() {
        val welcomePanel = SpeckitWelcomePanel(
            onFeatureSelected = { descriptor, index -> showFeaturePanel(descriptor, index) },
            onDiscoverAll = { showDiscoverPanel() },
            allDescriptors = allDescriptors
        )
        replaceContent(welcomePanel)
    }

    /**
     * Show the categorized feature list.
     * Mirrors goToPanel(AiaScreenDetailed.DiscoverAll) flow.
     */
    private fun showDiscoverPanel() {
        val discoverPanel = SpeckitDiscoverPanel(
            onFeatureSelected = { descriptor, index -> showFeaturePanel(descriptor, index) },
            onClose = { showWelcomePanel() }
        )
        replaceContent(discoverPanel)
    }

    /**
     * Show the detail view for a specific feature.
     * Mirrors goToPanel(AiaScreenDetailed.WelcomeScreen(descriptor, index, ...)) flow.
     */
    private fun showFeaturePanel(descriptor: SpeckitFeatureDescriptor, index: Int) {
        val featurePanel = SpeckitFeaturePanel(
            descriptor = descriptor,
            index = index,
            allDescriptors = allDescriptors,
            onFeatureSelected = { desc, idx -> showFeaturePanel(desc, idx) },
            onDiscoverAll = { showDiscoverPanel() },
            onClose = { showWelcomePanel() }
        )
        replaceContent(featurePanel)
    }

    /**
     * Replace current content with a new panel.
     * Mirrors the screen swap in AIAssistantContainerPanelVmImpl.goToPanel.
     */
    private fun replaceContent(content: JPanel) {
        removeAll()
        add(content, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    override fun dispose() {}
}
