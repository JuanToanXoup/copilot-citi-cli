package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitChecklistAgent(private val basePath: String) : AgentTool(
    basePath = basePath,
    toolName = "speckit_checklist",
    toolDescription = "Generate a custom requirements quality checklist for the current feature. Validates quality of requirements, NOT implementation.",
    agentFileName = "speckit.checklist.agent.md",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "focus" to mapOf("type" to "string", "description" to "Optional focus area for the checklist, e.g. 'ux', 'api', 'security'")
        )
    )
) {
    override fun gatherExtraContext(request: ToolInvocationRequest): String {
        val featureDir = findCurrentFeatureDir() ?: return ""

        return buildString {
            for (artifact in listOf("spec.md", "plan.md", "tasks.md")) {
                val content = readFeatureArtifact(featureDir, artifact)
                if (content != null) {
                    appendLine("## $artifact (specs/$featureDir/$artifact)")
                    appendLine(content)
                    appendLine()
                }
            }

            val checklistTemplate = readFileIfExists(".specify/templates/checklist-template.md")
            if (checklistTemplate != null) {
                appendLine("## Checklist Template")
                appendLine(checklistTemplate)
                appendLine()
            }
        }
    }
}
