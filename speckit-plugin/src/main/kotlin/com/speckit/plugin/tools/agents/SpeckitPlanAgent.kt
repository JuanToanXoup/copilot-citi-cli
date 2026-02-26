package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitPlanAgent : SubagentTool(
    toolName = "speckit_plan",
    toolDescription = "Execute implementation planning. Generates design artifacts: research.md, data-model.md, contracts/, quickstart.md from the feature spec.",
    agentFileName = "speckit.plan.agent.md",
    chatModeSlug = "speckit.plan",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "feature" to mapOf("type" to "string", "description" to "Feature directory name under specs/ (overrides git branch detection)")
        ),
        "required" to listOf<String>()
    )
) {
    override fun gatherExtraContext(request: ToolInvocationRequest, basePath: String): String {
        val featureDir = resolveFeatureDir(request, basePath) ?: return ""

        return buildString {
            val spec = readFeatureArtifact(basePath, featureDir, "spec.md")
            if (spec != null) {
                appendLine("## Current Spec (specs/$featureDir/spec.md)")
                appendLine(spec)
                appendLine()
            }

            val planTemplate = readFileIfExists(basePath, ".specify/templates/plan-template.md")
            if (planTemplate != null) {
                appendLine("## Plan Template")
                appendLine(planTemplate)
                appendLine()
            }
        }
    }
}
