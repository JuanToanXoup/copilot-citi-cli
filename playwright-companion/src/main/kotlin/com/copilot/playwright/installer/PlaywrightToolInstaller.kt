package com.copilot.playwright.installer

import com.copilot.playwright.mcp.McpClient
import com.copilot.playwright.mcp.McpToolBridge
import com.github.copilot.chat.conversation.agent.tool.ConversationToolService
import com.github.copilot.chat.conversation.agent.tool.ToolRegistryImpl
import com.github.copilot.chat.conversation.agent.tool.ToolRegistryProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * Spawns the Playwright MCP server at IDE startup, discovers its tools,
 * bridges each one into Copilot's ToolRegistry, and pushes the full
 * tool list to the LSP server via ConversationToolService.
 */
class PlaywrightToolInstaller : StartupActivity.DumbAware {

    private val log = Logger.getInstance(PlaywrightToolInstaller::class.java)

    override fun runActivity(project: Project) {
        try {
            registerPlaywrightTools()
        } catch (e: Exception) {
            log.warn("Playwright tool registration failed — Copilot Chat may not be ready yet", e)
        }
    }

    private fun registerPlaywrightTools() {
        // 1. Get Copilot's mutable tool registry
        val registry = ToolRegistryProvider.getInstance()
        if (registry !is ToolRegistryImpl) {
            log.warn("ToolRegistry is not ToolRegistryImpl — cannot register Playwright tools")
            return
        }

        // 2. Spawn the Playwright MCP server (stdio)
        val client = McpClient.start("npx", listOf("@playwright/mcp@latest"))
        log.info("Playwright MCP server started")

        // 3. Discover tools and bridge each one into the registry
        val tools = client.listTools()
        if (tools.isEmpty()) {
            log.warn("Playwright MCP server returned no tools")
            client.stop()
            return
        }

        for (tool in tools) {
            registry.registerTool(McpToolBridge(client, tool))
            log.info("Registered Playwright tool: ${tool.name}")
        }
        log.info("Registered ${tools.size} Playwright tools")

        // 4. Push full tool list to Copilot's LSP server
        val allTools = registry.getRegisteredTools()
        ConversationToolService.Companion.getInstance().registerTools(allTools)
        log.info("Pushed ${allTools.size} total tools to Copilot LSP client")
    }
}
