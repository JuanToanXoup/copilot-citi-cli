package com.citigroup.copilotchat.ui

import com.citigroup.copilotchat.workingset.WorkingSetPanel
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener

/**
 * Creates the Copilot Chat tool window with multiple content tabs:
 *  - Agent: Claude Code architecture (primary interface)
 *  - Custom: configurable agent session (config -> chat)
 *  - Agents: agent config (leads + subagents)
 *  - Changes: working set file changes
 *  - Tools: registered tools viewer
 *  - MCP: MCP server configuration
 *  - Memory: RAG settings and project indexing
 *  - Recorder: Playwright codegen
 *
 * Follows the official Copilot plugin pattern of using IntelliJ content tabs
 * rather than embedding JTabbedPane inside the tool window.
 */
class ChatToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()
        val contentManager = toolWindow.contentManager

        // Agent tab — Claude Code architecture (delegate_task, subagents, teams)
        val agentPanel = AgentPanel(project)
        val agentContent = contentFactory.createContent(agentPanel, "Agent", false)
        agentContent.isCloseable = false
        contentManager.addContent(agentContent)
        Disposer.register(toolWindow.disposable, agentPanel)

        // Custom tab — configurable agent session (config phase -> chat phase)
        val customAgentPanel = CustomAgentPanel(project)
        val customContent = contentFactory.createContent(customAgentPanel, "Custom", false)
        customContent.isCloseable = false
        contentManager.addContent(customContent)
        Disposer.register(toolWindow.disposable, customAgentPanel)

        // Agents tab — agent config (leads + subagents)
        val agentConfigPanel = AgentConfigPanel(project)
        val agentConfigContent = contentFactory.createContent(agentConfigPanel, "Agents", false)
        agentConfigContent.isCloseable = false
        contentManager.addContent(agentConfigContent)
        Disposer.register(toolWindow.disposable, agentConfigPanel)

        // Changes tab — working set file changes
        val workingSetPanel = WorkingSetPanel(project)
        val changesContent = contentFactory.createContent(workingSetPanel, "Changes", false)
        changesContent.isCloseable = false
        contentManager.addContent(changesContent)
        Disposer.register(toolWindow.disposable, workingSetPanel)

        // Tools tab — registered tools viewer
        val toolsPanel = ToolsPanel(onToolToggled = {})
        val toolsContent = contentFactory.createContent(toolsPanel, "Tools", false)
        toolsContent.isCloseable = false
        contentManager.addContent(toolsContent)

        // MCP tab — server configuration
        val mcpPanel = McpConfigPanel(onConfigChanged = {
            toolsPanel.refreshTools()
        })
        val mcpContent = contentFactory.createContent(mcpPanel, "MCP", false)
        mcpContent.isCloseable = false
        contentManager.addContent(mcpContent)

        // Memory tab — RAG settings and project indexing
        val memoryPanel = MemoryPanel(project)
        val memoryContent = contentFactory.createContent(memoryPanel, "Memory", false)
        memoryContent.isCloseable = false
        contentManager.addContent(memoryContent)
        Disposer.register(toolWindow.disposable, memoryPanel)

        // Recorder tab — Playwright codegen
        val recorderPanel = RecorderPanel(project)
        val recorderContent = contentFactory.createContent(recorderPanel, "Recorder", false)
        recorderContent.isCloseable = false
        contentManager.addContent(recorderContent)
        Disposer.register(toolWindow.disposable, recorderPanel)

        // Warn on unsaved Agents changes when navigating away
        contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.content === agentConfigContent && !event.content.isSelected) {
                    if (!agentConfigPanel.promptUnsavedChanges()) {
                        // User cancelled — revert to Agents tab
                        javax.swing.SwingUtilities.invokeLater {
                            contentManager.setSelectedContent(agentConfigContent)
                        }
                    }
                }
            }
        })

        // Select Agent tab by default
        contentManager.setSelectedContent(agentContent)
    }
}
