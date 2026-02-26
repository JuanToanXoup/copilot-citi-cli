package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitClarifyAgent : AgentTool(
    toolName = "speckit_clarify",
    toolDescription = "Identify underspecified areas in the current feature spec and ask targeted clarification questions. Updates the spec with clarifications.",
    agentFileName = "speckit.clarify.agent.md",
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
        }
    }
}
