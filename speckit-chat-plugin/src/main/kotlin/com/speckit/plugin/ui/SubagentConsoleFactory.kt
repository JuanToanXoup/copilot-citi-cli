package com.speckit.plugin.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.speckit.plugin.persistence.SessionPersistenceManager
import com.speckit.plugin.ui.onboarding.SpeckitOnboardingPanel

class SubagentConsoleFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val cm = toolWindow.contentManager

        val persistenceManager = project.service<SessionPersistenceManager>()
        persistenceManager.initialize()

        val sessionPanel = SessionPanel(project, toolWindow.disposable, persistenceManager)

        val onboardingPanel = SpeckitOnboardingPanel(project, toolWindow.disposable, sessionPanel, persistenceManager)
        cm.addContent(cm.factory.createContent(onboardingPanel, "Onboarding", false).apply { isCloseable = false })

        val discoveryPanel = DiscoveryPanel(project, toolWindow.disposable, sessionPanel, persistenceManager)
        cm.addContent(cm.factory.createContent(discoveryPanel, "Discovery", false).apply { isCloseable = false })

        val pipelinePanel = PipelinePanel(project, toolWindow.disposable, sessionPanel, persistenceManager)
        cm.addContent(cm.factory.createContent(pipelinePanel, "Pipeline", false).apply { isCloseable = false })

        cm.addContent(cm.factory.createContent(sessionPanel, "Sessions", false).apply { isCloseable = false })
    }
}
