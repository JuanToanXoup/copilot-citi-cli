package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitTasksAgent(private val basePath: String) : AgentTool(
    basePath = basePath,
    toolName = "speckit_tasks",
    toolDescription = "Generate actionable, dependency-ordered tasks.md from the feature's design artifacts (plan.md, spec.md, data-model.md, contracts/).",
    agentFileName = "speckit.tasks.agent.md"
) {
    override fun gatherExtraContext(request: ToolInvocationRequest): String {
        val featureDir = findCurrentFeatureDir() ?: return ""

        return buildString {
            for (artifact in listOf("spec.md", "plan.md", "data-model.md", "research.md", "quickstart.md")) {
                val content = readFeatureArtifact(featureDir, artifact)
                if (content != null) {
                    appendLine("## $artifact (specs/$featureDir/$artifact)")
                    appendLine(content)
                    appendLine()
                }
            }

            val tasksTemplate = readFileIfExists(".specify/templates/tasks-template.md")
            if (tasksTemplate != null) {
                appendLine("## Tasks Template")
                appendLine(tasksTemplate)
                appendLine()
            }
        }
    }
}
