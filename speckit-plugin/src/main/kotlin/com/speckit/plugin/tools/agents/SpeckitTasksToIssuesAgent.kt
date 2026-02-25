package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitTasksToIssuesAgent(private val basePath: String) : AgentTool(
    basePath = basePath,
    toolName = "speckit_taskstoissues",
    toolDescription = "Convert tasks from tasks.md into GitHub issues. Requires a GitHub remote URL.",
    agentFileName = "speckit.taskstoissues.agent.md"
) {
    override fun gatherExtraContext(request: ToolInvocationRequest): String {
        val featureDir = findCurrentFeatureDir() ?: return ""

        return buildString {
            val tasks = readFeatureArtifact(featureDir, "tasks.md")
            if (tasks != null) {
                appendLine("## tasks.md (specs/$featureDir/tasks.md)")
                appendLine(tasks)
                appendLine()
            }
        }
    }
}
