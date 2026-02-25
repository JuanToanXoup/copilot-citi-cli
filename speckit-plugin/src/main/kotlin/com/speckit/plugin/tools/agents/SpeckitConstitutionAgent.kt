package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitConstitutionAgent(private val basePath: String) : AgentTool(
    basePath = basePath,
    toolName = "speckit_constitution",
    toolDescription = "Create or update the project constitution (.specify/memory/constitution.md). Collects project principles, governance rules, and ensures dependent templates stay in sync.",
    agentFileName = "speckit.constitution.agent.md",
    inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "principles" to mapOf("type" to "string", "description" to "Optional comma-separated list of project principles to include")
        ),
        "required" to listOf<String>()
    )
) {
    override fun gatherExtraContext(request: ToolInvocationRequest): String {
        return buildString {
            val template = readFileIfExists(".specify/templates/constitution-template.md")
            if (template != null) {
                appendLine("## Constitution Template")
                appendLine(template)
                appendLine()
            }

            // List dependent templates that may need sync
            val templatesDir = File(basePath, ".specify/templates")
            if (templatesDir.isDirectory) {
                val templates = templatesDir.listFiles { f -> f.isFile }?.map { it.name } ?: emptyList()
                appendLine("## Dependent Templates (for sync validation)")
                templates.forEach { appendLine("- $it") }
                appendLine()
            }
        }
    }
}
