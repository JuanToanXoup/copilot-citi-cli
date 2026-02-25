package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitWriteMemory(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_write_memory",
        "Write or update a memory file in .specify/memory/.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "File name, e.g. 'constitution.md'"),
                "content" to mapOf("type" to "string", "description" to "Full content to write to the file")
            ),
            "required" to listOf("name", "content")
        ),
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val name = request.input?.get("name")?.asString
            ?: return LanguageModelToolResult.Companion.error("Missing required parameter: name")
        val content = request.input?.get("content")?.asString
            ?: return LanguageModelToolResult.Companion.error("Missing required parameter: content")

        val memoryDir = File(basePath, ".specify/memory")
        if (!memoryDir.isDirectory) {
            memoryDir.mkdirs()
        }

        val file = File(memoryDir, name)
        file.writeText(content)

        return LanguageModelToolResult.Companion.success("Written ${content.length} chars to .specify/memory/$name")
    }
}
