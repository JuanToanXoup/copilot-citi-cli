package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitAnalyzeAgent : AgentTool(
    toolName = "speckit_analyze",
    toolDescription = "Non-destructive cross-artifact analysis. Validates consistency between spec.md, plan.md, and tasks.md. Reports gaps, ambiguities, and constitution violations.",
    agentFileName = "speckit.analyze.agent.md",
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
            for (artifact in listOf("spec.md", "plan.md", "tasks.md")) {
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
