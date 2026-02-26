package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitTasksAgent : AgentTool(
    toolName = "speckit_tasks",
    toolDescription = "Generate actionable, dependency-ordered tasks.md from the feature's design artifacts (plan.md, spec.md, data-model.md, contracts/).",
    agentFileName = "speckit.tasks.agent.md",
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
            for (artifact in listOf("spec.md", "plan.md", "data-model.md", "research.md", "quickstart.md")) {
                val content = readFeatureArtifact(basePath, featureDir, artifact)
                if (content != null) {
                    appendLine("## $artifact (specs/$featureDir/$artifact)")
                    appendLine(content)
                    appendLine()
                }
            }

            val tasksTemplate = readFileIfExists(basePath, ".specify/templates/tasks-template.md")
            if (tasksTemplate != null) {
                appendLine("## Tasks Template")
                appendLine(tasksTemplate)
                appendLine()
            }
        }
    }
}
