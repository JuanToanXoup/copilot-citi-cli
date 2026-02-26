package com.speckit.plugin.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class SubagentConsoleFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val console = project.service<SubagentConsole>().getOrCreateConsole()
        val content = ContentFactory.getInstance()
            .createContent(console.component, "Agents", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }
}
