package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationManager
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SpeckitReadSpec : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_read_spec",
        "Read a spec file from a feature directory under specs/.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "feature" to mapOf("type" to "string", "description" to "Feature directory name, e.g. '001-analyze-copilot-chat'"),
                "file" to mapOf("type" to "string", "description" to "File name within the feature directory, e.g. 'spec.md', 'plan.md', 'tasks.md'")
            ),
            "required" to listOf("feature", "file")
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

        val feature = request.input?.get("feature")?.asString
            ?: return LanguageModelToolResult.Companion.error("Missing required parameter: feature")
        val fileName = request.input?.get("file")?.asString
            ?: return LanguageModelToolResult.Companion.error("Missing required parameter: file")

        if (!PathSandbox.isSafeName(feature)) {
            return LanguageModelToolResult.Companion.error("Invalid feature name: '$feature'")
        }
        if (!PathSandbox.isSafeName(fileName)) {
            return LanguageModelToolResult.Companion.error("Invalid file name: '$fileName'")
        }

        val file = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, "specs/$feature/$fileName"))

        if (file == null || file.isDirectory) {
            return LanguageModelToolResult.Companion.error("File not found: $basePath/specs/$feature/$fileName")
        }

        val content = withContext(Dispatchers.IO) {
            VfsUtilCore.loadText(file)
        }
        return LanguageModelToolResult.Companion.success(content)
    }
}
