package com.speckit.companion.installer

import com.github.copilot.chat.conversation.agent.tool.ConversationToolService
import com.github.copilot.chat.conversation.agent.tool.ToolRegistryImpl
import com.github.copilot.chat.conversation.agent.tool.ToolRegistryProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.speckit.companion.mcp.McpClient
import com.speckit.companion.mcp.McpToolBridge
import com.speckit.companion.tools.SpeckitAnalyzeProject
import com.speckit.companion.tools.SpeckitListUncovered
import com.speckit.companion.tools.SpeckitParseCoverage
import com.speckit.companion.tools.SpeckitReadArtifact
import com.speckit.companion.tools.SpeckitRunTests
import com.speckit.companion.tools.SpeckitSetupFeature
import com.speckit.companion.tools.SpeckitWriteArtifact

/**
 * Registers Spec-Kit tools on the official GitHub Copilot Chat plugin's
 * ToolRegistry and pushes schemas to its LSP client.
 *
 * Runs as a postStartupActivity so the Copilot plugin is already initialized.
 */
class SpeckitToolInstaller : StartupActivity.DumbAware {

    private val log = Logger.getInstance(SpeckitToolInstaller::class.java)

    override fun runActivity(project: Project) {
        val basePath = project.basePath ?: return

        try {
            registerTools(basePath)
        } catch (e: Exception) {
            log.warn("Spec-Kit tool registration failed — Copilot Chat may not be ready yet", e)
        }
    }

    private fun registerTools(basePath: String) {
        val registry = ToolRegistryProvider.getInstance()
        if (registry !is ToolRegistryImpl) {
            log.warn("ToolRegistry is not ToolRegistryImpl — cannot register tools")
            return
        }

        // 1. Register in-process Spec-Kit tools
        val specKitTools = listOf(
            SpeckitRunTests(basePath),
            SpeckitAnalyzeProject(basePath),
            SpeckitReadArtifact(basePath),
            SpeckitWriteArtifact(basePath),
            SpeckitParseCoverage(basePath),
            SpeckitListUncovered(basePath),
            SpeckitSetupFeature(basePath),
        )

        for (tool in specKitTools) {
            registry.registerTool(tool)
            log.info("Registered Spec-Kit tool: ${tool.toolDefinition.name}")
        }

        // 2. Register MCP tools as in-process tools (if configured)
        registerMcpTools(registry)

        // 3. Push full tool list to Copilot's LSP client
        val allTools = registry.getRegisteredTools()
        ConversationToolService.Companion.getInstance().registerTools(allTools)
        log.info("Pushed ${allTools.size} tools to Copilot LSP client")
    }

    private fun registerMcpTools(registry: ToolRegistryImpl) {
        // Add MCP server configs here as needed.
        // Each one is started locally (stdio) and its tools are flattened
        // into the Copilot tool registry as in-process tools.
        //
        // Example:
        //   val playwright = McpClient.start("npx", listOf("@playwright/mcp@latest"))
        //   for (tool in playwright.listTools()) {
        //       registry.registerTool(McpToolBridge(playwright, tool))
        //   }
    }
}
