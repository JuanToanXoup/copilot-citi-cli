package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.tool.ConversationToolService
import com.github.copilot.chat.conversation.agent.tool.ToolRegistryImpl
import com.github.copilot.chat.conversation.agent.tool.ToolRegistryProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class IssueToolInstaller : StartupActivity.DumbAware {

    private val log = Logger.getInstance(IssueToolInstaller::class.java)

    override fun runActivity(project: Project) {
        try {
            registerTools()
        } catch (e: Exception) {
            log.warn("speckit_create_issue registration failed — Copilot Chat may not be ready yet", e)
        }
    }

    private fun registerTools() {
        val registry = ToolRegistryProvider.getInstance()
        if (registry !is ToolRegistryImpl) {
            log.warn("ToolRegistry is not ToolRegistryImpl — cannot register speckit_create_issue")
            return
        }

        val tool = CreateIssueTool()
        registry.registerTool(tool)
        log.info("Registered tool: ${tool.toolDefinition.name}")

        val allTools = registry.getRegisteredTools()
        ConversationToolService.Companion.getInstance().registerTools(allTools)
        log.info("Pushed ${allTools.size} tools to Copilot LSP client")
    }
}
