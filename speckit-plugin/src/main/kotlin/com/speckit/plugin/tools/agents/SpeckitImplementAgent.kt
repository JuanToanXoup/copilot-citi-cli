package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitImplementAgent(private val basePath: String) : AgentTool(
    basePath = basePath,
    toolName = "speckit_implement",
    toolDescription = "Execute the implementation plan by processing tasks from tasks.md. Follows TDD, respects dependency order, marks completed tasks.",
    agentFileName = "speckit.implement.agent.md"
) {
    override fun gatherExtraContext(request: ToolInvocationRequest): String {
        val featureDir = findCurrentFeatureDir() ?: return ""

        return buildString {
            for (artifact in listOf("tasks.md", "plan.md", "spec.md", "data-model.md", "research.md", "quickstart.md")) {
                val content = readFeatureArtifact(featureDir, artifact)
                if (content != null) {
                    appendLine("## $artifact (specs/$featureDir/$artifact)")
                    appendLine(content)
                    appendLine()
                }
            }
        }
    }
}
