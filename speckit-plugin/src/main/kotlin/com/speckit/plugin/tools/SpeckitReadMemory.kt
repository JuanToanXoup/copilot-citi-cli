package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitReadMemory(private val basePath: String) : LanguageModelToolRegistration {

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
        val name = request.input?.get("name")?.asString
        val memoryDir = File(basePath, ".specify/memory")

        if (!memoryDir.isDirectory) {
            return LanguageModelToolResult.Companion.error("No .specify/memory/ directory found in project.")
        }

        if (name == null) {
            val files = memoryDir.listFiles { f -> f.isFile }
                ?.sortedBy { it.name }
                ?.map { it.name }
                ?: emptyList()
            return LanguageModelToolResult.Companion.success(
                "Memory files:\n${files.joinToString("\n") { "- $it" }}"
            )
        }

        val file = File(memoryDir, name)
        if (!file.exists()) {
            return LanguageModelToolResult.Companion.error("Memory file not found: $name")
        }

        return LanguageModelToolResult.Companion.success(file.readText())
    }
}
