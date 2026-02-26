package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitSpecifyAgent : AgentTool(
    toolName = "speckit_specify",
    toolDescription = "Create or update a feature specification from a natural language description. Creates feature branch, initializes spec directory, fills spec sections.",
    agentFileName = "speckit.specify.agent.md",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "description" to mapOf("type" to "string", "description" to "Natural language feature description"),
            "feature" to mapOf("type" to "string", "description" to "Explicit feature directory name under specs/ (skips branch creation). Used by coverage orchestrator.")
        ),
        "required" to listOf("description")
    )
) {
    override fun gatherExtraContext(request: ToolInvocationRequest, basePath: String): String {
        return buildString {
            val feature = request.input?.get("feature")?.asString
            if (feature != null) {
                appendLine("## Feature Directory Override")
                appendLine("A feature directory has been explicitly specified: `specs/$feature/`")
                appendLine("Use this as the feature directory. **Do not create a git branch.** Create the directory if it does not exist, then write spec artifacts there.")
                appendLine()
            }

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
