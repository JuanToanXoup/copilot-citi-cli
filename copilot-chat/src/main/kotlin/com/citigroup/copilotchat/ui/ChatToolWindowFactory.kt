package com.citigroup.copilotchat.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Creates the Copilot Chat tool window with multiple content tabs:
 *  - Chat: main conversation panel
 *  - Tools: registered tools viewer
 *  - MCP: MCP server configuration
 *  - Workers: worker agents + orchestration
 *
 * Follows the official Copilot plugin pattern of using IntelliJ content tabs
 * rather than embedding JTabbedPane inside the tool window.
 */
class ChatToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()
        val contentManager = toolWindow.contentManager

        // Chat tab — main conversation
        val chatPanel = CopilotChatPanel(project)
        val chatContent = contentFactory.createContent(chatPanel, "Chat", false)
        chatContent.isCloseable = false
        contentManager.addContent(chatContent)
        Disposer.register(toolWindow.disposable, chatPanel)

        // Tools tab — registered tools viewer
        val toolsPanel = ToolsPanel()
        val toolsContent = contentFactory.createContent(toolsPanel, "Tools", false)
        toolsContent.isCloseable = false
        contentManager.addContent(toolsContent)

        // MCP tab — server configuration
        val mcpPanel = McpConfigPanel(onConfigChanged = {
            chatPanel.onMcpConfigChanged()
            toolsPanel.refreshTools()
        })
        chatPanel.setMcpConfigPanel(mcpPanel)
        val mcpContent = contentFactory.createContent(mcpPanel, "MCP", false)
        mcpContent.isCloseable = false
        contentManager.addContent(mcpContent)

        // Workers tab — worker agents
        val workerPanel = WorkerPanel(project)
        val workersContent = contentFactory.createContent(workerPanel, "Workers", false)
        workersContent.isCloseable = false
        contentManager.addContent(workersContent)
        Disposer.register(toolWindow.disposable, workerPanel)

        // Select Chat tab by default
        contentManager.setSelectedContent(chatContent)
    }
}
