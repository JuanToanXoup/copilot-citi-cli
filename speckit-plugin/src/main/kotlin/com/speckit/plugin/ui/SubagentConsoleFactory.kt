package com.speckit.plugin.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class SubagentConsoleFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()

        // Agents tab
        val agentPanel = AgentRunPanel(project, toolWindow.disposable)
        project.service<SubagentConsole>().panel = agentPanel
        val agentContent = contentFactory.createContent(agentPanel, "Agents", false)
        agentContent.isCloseable = false
        toolWindow.contentManager.addContent(agentContent)

        // Chat tab
        val chatPanel = SpeckitChatPanel(project, toolWindow.disposable)
        val chatContent = contentFactory.createContent(chatPanel, "Chat", false)
        chatContent.isCloseable = false
        toolWindow.contentManager.addContent(chatContent)
    }
}
