package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationManager
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

class SpeckitListSpecs : LanguageModelToolRegistration {

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
        val manager = ApplicationManager.getApplication().getService(ToolInvocationManager::class.java)
        val project = manager.findProjectForInvocation(request.identifier)
            ?: return LanguageModelToolResult.Companion.error("No project found for invocation")
        val basePath = project.basePath
            ?: return LanguageModelToolResult.Companion.error("No project base path")

        val specsDir = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, "specs"))
        if (specsDir == null || !specsDir.isDirectory) {
            return LanguageModelToolResult.Companion.success("No specs/ directory found. Use speckit_setup_feature to create a feature first.")
        }

        val features = specsDir.children
            .filter { it.isDirectory }
            .sortedBy { it.name }

        if (features.isEmpty()) {
            return LanguageModelToolResult.Companion.success("specs/ directory is empty. Use speckit_setup_feature to create a feature.")
        }

        val output = buildString {
            appendLine("Feature specs (${features.size}):")
            for (feature in features) {
                appendLine("- ${feature.name}/")
                val files = feature.children.filter { !it.isDirectory }.sortedBy { it.name }
                for (file in files) {
                    appendLine("    ${file.name}")
                }
            }
        }

        return LanguageModelToolResult.Companion.success(output)
    }
}
