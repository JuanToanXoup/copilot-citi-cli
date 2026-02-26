package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitSpecifyAgent : AgentTool(
    toolName = "speckit_specify",
    toolDescription = "Create or update a feature specification from a natural language description. Creates feature branch, initializes spec directory, fills spec sections.",
    agentFileName = "speckit.specify.agent.md",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "description" to mapOf("type" to "string", "description" to "Natural language feature description")
        ),
        "required" to listOf("description")
    )
) {
    override fun gatherExtraContext(request: ToolInvocationRequest, basePath: String): String {
        return buildString {
            val specTemplate = readFileIfExists(basePath, ".specify/templates/spec-template.md")
            if (specTemplate != null) {
                appendLine("## Spec Template")
                appendLine(specTemplate)
                appendLine()
            }

            val checklistTemplate = readFileIfExists(basePath, ".specify/templates/checklist-template.md")
            if (checklistTemplate != null) {
                appendLine("## Checklist Template")
                appendLine(checklistTemplate)
                appendLine()
            }
        }
    }
}
