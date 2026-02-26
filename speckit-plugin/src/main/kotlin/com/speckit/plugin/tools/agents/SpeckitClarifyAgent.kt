package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitClarifyAgent : AgentTool(
    toolName = "speckit_clarify",
    toolDescription = "Identify underspecified areas in the current feature spec and ask targeted clarification questions. Updates the spec with clarifications.",
    agentFileName = "speckit.clarify.agent.md"
) {
    override fun gatherExtraContext(request: ToolInvocationRequest, basePath: String): String {
        val featureDir = findCurrentFeatureDir(basePath) ?: return ""

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
