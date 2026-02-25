package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitListSpecs(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_list_specs",
        "List all feature spec directories and their files from specs/.",
        mapOf(
            "type" to "object",
            "properties" to mapOf<String, Any>(),
            "required" to listOf<String>()
        ),
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val specsDir = File(basePath, "specs")
        if (!specsDir.isDirectory) {
            return LanguageModelToolResult.Companion.success("No specs/ directory found. Use speckit_setup_feature to create a feature first.")
        }

        val features = specsDir.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name }
            ?: emptyList()

        if (features.isEmpty()) {
            return LanguageModelToolResult.Companion.success("specs/ directory is empty. Use speckit_setup_feature to create a feature.")
        }

        val output = buildString {
            appendLine("Feature specs (${features.size}):")
            for (feature in features) {
                appendLine("- ${feature.name}/")
                val files = feature.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
                for (file in files) {
                    appendLine("    ${file.name}")
                }
            }
        }

        return LanguageModelToolResult.Companion.success(output)
    }
}
