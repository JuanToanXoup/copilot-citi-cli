package com.speckit.companion.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitReadArtifact(private val basePath: String) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_read_artifact",
        "Read a spec-kit artifact file (spec.md, plan.md, tasks.md, constitution.md, etc.) from the specs/ or .specify/ directory.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "Artifact path relative to project root (e.g. specs/001-feature/spec.md or .specify/memory/constitution.md)")
            ),
            "required" to listOf("path")
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

        val file = File(basePath, path)
        if (!file.exists()) {
            return LanguageModelToolResult.Companion.error("File not found: $path")
        }
        if (!file.canonicalPath.startsWith(File(basePath).canonicalPath)) {
            return LanguageModelToolResult.Companion.error("Path traversal not allowed")
        }

        return LanguageModelToolResult.Companion.success(file.readText())
    }
}
