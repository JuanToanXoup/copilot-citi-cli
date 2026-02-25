package com.speckit.companion.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitWriteArtifact(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_write_artifact",
        "Write content to a spec-kit artifact file (spec.md, plan.md, tasks.md, etc.). Creates parent directories if needed.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "Artifact path relative to project root"),
                "content" to mapOf("type" to "string", "description" to "File content to write")
            ),
            "required" to listOf("path", "content")
        ),
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val path = request.input?.get("path")?.asString
            ?: return LanguageModelToolResult.Companion.error("Missing required parameter: path")
        val content = request.input?.get("content")?.asString
            ?: return LanguageModelToolResult.Companion.error("Missing required parameter: content")

        val file = File(basePath, path)
        if (!file.canonicalPath.startsWith(File(basePath).canonicalPath)) {
            return LanguageModelToolResult.Companion.error("Path traversal not allowed")
        }

        file.parentFile?.mkdirs()
        file.writeText(content)

        return LanguageModelToolResult.Companion.success("Written ${content.length} bytes to $path")
    }
}
