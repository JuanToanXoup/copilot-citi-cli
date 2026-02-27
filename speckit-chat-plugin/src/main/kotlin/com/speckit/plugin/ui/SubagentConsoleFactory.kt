package com.speckit.plugin.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.speckit.plugin.ui.onboarding.SpeckitOnboardingPanel

class SubagentConsoleFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val cm = toolWindow.contentManager

        val chatPanel = SpeckitChatPanel(project, toolWindow.disposable)
        cm.addContent(cm.factory.createContent(chatPanel, "Chat", false).apply { isCloseable = false })

        val constitutionPanel = ConstitutionPanel(project, toolWindow.disposable, chatPanel)
        cm.addContent(cm.factory.createContent(constitutionPanel, "Discovery", false).apply { isCloseable = false })

        val specifyPanel = SpecifyPanel(project, toolWindow.disposable, chatPanel)
        cm.addContent(cm.factory.createContent(specifyPanel, "Specify", false).apply { isCloseable = false })

        val onboardingPanel = SpeckitOnboardingPanel(project, toolWindow.disposable)
        cm.addContent(cm.factory.createContent(onboardingPanel, "Onboarding", false).apply { isCloseable = false })
    }
}
