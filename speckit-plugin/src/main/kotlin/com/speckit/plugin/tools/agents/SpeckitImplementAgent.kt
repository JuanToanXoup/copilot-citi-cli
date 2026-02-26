package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitImplementAgent : SubagentTool(
    toolName = "speckit_implement",
    toolDescription = "Execute the implementation plan by processing tasks from tasks.md. Follows TDD, respects dependency order, marks completed tasks.",
    agentFileName = "speckit.implement.agent.md",
    chatModeSlug = "speckit.implement",
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
            for (artifact in listOf("tasks.md", "plan.md", "spec.md", "data-model.md", "research.md", "quickstart.md")) {
                val content = readFeatureArtifact(basePath, featureDir, artifact)
                if (content != null) {
                    appendLine("## $artifact (specs/$featureDir/$artifact)")
                    appendLine(content)
                    appendLine()
                }
            }
        }
    }
}
