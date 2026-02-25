package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitPlanAgent(private val basePath: String) : AgentTool(
    basePath = basePath,
    toolName = "speckit_plan",
    toolDescription = "Execute implementation planning. Generates design artifacts: research.md, data-model.md, contracts/, quickstart.md from the feature spec.",
    agentFileName = "speckit.plan.agent.md"
) {
    override fun gatherExtraContext(request: ToolInvocationRequest): String {
        val featureDir = findCurrentFeatureDir() ?: return ""

        return buildString {
            val spec = readFeatureArtifact(featureDir, "spec.md")
            if (spec != null) {
                appendLine("## Current Spec (specs/$featureDir/spec.md)")
                appendLine(spec)
                appendLine()
            }

            val planTemplate = readFileIfExists(".specify/templates/plan-template.md")
            if (planTemplate != null) {
                appendLine("## Plan Template")
                appendLine(planTemplate)
                appendLine()
            }
        }
    }
}
