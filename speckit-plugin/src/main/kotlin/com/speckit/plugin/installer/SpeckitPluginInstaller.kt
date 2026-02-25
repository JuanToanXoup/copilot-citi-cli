package com.speckit.plugin.installer

import com.github.copilot.chat.conversation.agent.tool.ConversationToolService
import com.github.copilot.chat.conversation.agent.tool.ToolRegistryImpl
import com.github.copilot.chat.conversation.agent.tool.ToolRegistryProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.speckit.plugin.tools.SpeckitAnalyzeProject
import com.speckit.plugin.tools.SpeckitDiscover
import com.speckit.plugin.tools.SpeckitListAgents
import com.speckit.plugin.tools.SpeckitParseCoverage
import com.speckit.plugin.tools.SpeckitRunTests
import com.speckit.plugin.tools.SpeckitListSpecs
import com.speckit.plugin.tools.SpeckitListTemplates
import com.speckit.plugin.tools.SpeckitReadAgent
import com.speckit.plugin.tools.SpeckitReadMemory
import com.speckit.plugin.tools.SpeckitReadSpec
import com.speckit.plugin.tools.SpeckitReadTemplate
import com.speckit.plugin.tools.SpeckitSetupFeature
import com.speckit.plugin.tools.SpeckitSetupPlan
import com.speckit.plugin.tools.SpeckitUpdateAgents
import com.speckit.plugin.tools.SpeckitWriteMemory
import com.speckit.plugin.tools.agents.SpeckitAnalyzeAgent
import com.speckit.plugin.tools.agents.SpeckitChecklistAgent
import com.speckit.plugin.tools.agents.SpeckitClarifyAgent
import com.speckit.plugin.tools.agents.SpeckitConstitutionAgent
import com.speckit.plugin.tools.agents.SpeckitImplementAgent
import com.speckit.plugin.tools.agents.SpeckitPlanAgent
import com.speckit.plugin.tools.agents.SpeckitSpecifyAgent
import com.speckit.plugin.tools.agents.SpeckitTasksAgent
import com.speckit.plugin.tools.agents.SpeckitCoverageAgent
import com.speckit.plugin.tools.agents.SpeckitTasksToIssuesAgent

class SpeckitPluginInstaller : StartupActivity.DumbAware {

    private val log = Logger.getInstance(SpeckitPluginInstaller::class.java)

    override fun runActivity(project: Project) {
        val basePath = project.basePath ?: return

        try {
            registerTools(basePath)
        } catch (e: Exception) {
            log.warn("Spec-Kit plugin tool registration failed", e)
        }
    }

    private fun registerTools(basePath: String) {
        val registry = ToolRegistryProvider.getInstance()
        if (registry !is ToolRegistryImpl) {
            log.warn("ToolRegistry is not ToolRegistryImpl — cannot register tools")
            return
        }

        val tools = listOf(
            // Workflow agents (the speckit pipeline)
            SpeckitConstitutionAgent(basePath),
            SpeckitSpecifyAgent(basePath),
            SpeckitClarifyAgent(basePath),
            SpeckitPlanAgent(basePath),
            SpeckitTasksAgent(basePath),
            SpeckitAnalyzeAgent(basePath),
            SpeckitChecklistAgent(basePath),
            SpeckitImplementAgent(basePath),
            SpeckitTasksToIssuesAgent(basePath),
            // Coverage orchestrator
            SpeckitCoverageAgent(basePath),
            // Workflow tools (reimplemented in Kotlin, no shell scripts needed)
            SpeckitAnalyzeProject(basePath),
            SpeckitSetupPlan(basePath),
            SpeckitSetupFeature(basePath),
            SpeckitUpdateAgents(basePath),
            // Discovery and coverage tools
            SpeckitDiscover(basePath),
            SpeckitRunTests(basePath),
            SpeckitParseCoverage(basePath),
            // File access tools
            SpeckitListAgents(basePath),
            SpeckitReadAgent(basePath),
            SpeckitListTemplates(basePath),
            SpeckitReadTemplate(basePath),
            SpeckitListSpecs(basePath),
            SpeckitReadSpec(basePath),
            SpeckitReadMemory(basePath),
            SpeckitWriteMemory(basePath),
        )

        for (tool in tools) {
            registry.registerTool(tool)
            log.info("Registered Spec-Kit tool: ${tool.toolDefinition.name}")
        }

        val allTools = registry.getRegisteredTools()
        ConversationToolService.Companion.getInstance().registerTools(allTools)
        log.info("Pushed ${allTools.size} tools to Copilot LSP client (${tools.size} from speckit-plugin)")
    }
}
