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

class SpeckitReadMemory : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_read_memory",
        "Read a memory file from .specify/memory/ (e.g. constitution.md).",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "File name, e.g. 'constitution.md'. Omit to list all memory files.")
            ),
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

        val name = request.input?.get("name")?.asString
        val memoryDir = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, ".specify/memory"))

        if (memoryDir == null || !memoryDir.isDirectory) {
            return LanguageModelToolResult.Companion.success(
                "No .specify/memory/ directory found. Memory will be created when speckit_write_memory is first used."
            )
        }

        if (name == null) {
            val files = memoryDir.children
                .filter { !it.isDirectory }
                .sortedBy { it.name }
                .map { it.name }
            return LanguageModelToolResult.Companion.success(
                "Memory files:\n${files.joinToString("\n") { "- $it" }}"
            )
        }

        val file = memoryDir.findChild(name)
        if (file == null || file.isDirectory) {
            return LanguageModelToolResult.Companion.error("Memory file not found: $name")
        }

        return LanguageModelToolResult.Companion.success(VfsUtilCore.loadText(file))
    }
}
