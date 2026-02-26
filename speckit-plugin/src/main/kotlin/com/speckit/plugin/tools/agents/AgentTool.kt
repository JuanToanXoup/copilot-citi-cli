package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.speckit.plugin.tools.ResourceLoader
import com.speckit.plugin.tools.ScriptRunner
import java.io.File

abstract class AgentTool(
    private val basePath: String,
    private val toolName: String,
    private val toolDescription: String,
    private val agentFileName: String,
    private val inputSchema: Map<String, Any> = mapOf("type" to "object", "properties" to mapOf<String, Any>(), "required" to listOf<String>())
) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        toolName,
        toolDescription,
        inputSchema,
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val agentInstructions = ResourceLoader.readAgent(basePath, agentFileName)
            ?: return LanguageModelToolResult.Companion.error(
                "Agent definition not found: $agentFileName (checked project .github/agents/ and bundled resources)"
            )

        val context = buildString {
            appendLine("# Agent: $toolName")
            appendLine()

            // Project context from prerequisites (only if script exists)
            if (ResourceLoader.hasScript(basePath, "check-prerequisites.sh")) {
                val prereqResult = ScriptRunner.execScript(
                    "$basePath/.specify/scripts/bash/check-prerequisites.sh",
                    listOf("--json"),
                    basePath
                )
                if (prereqResult.success) {
                    appendLine("## Project Context")
                    appendLine(prereqResult.output)
                    appendLine()
                }
            }

            // Constitution (governance rules agents must follow)
            val constitution = LocalFileSystem.getInstance()
                .findFileByIoFile(File(basePath, ".specify/memory/constitution.md"))
            if (constitution != null && !constitution.isDirectory) {
                appendLine("## Constitution")
                appendLine(VfsUtilCore.loadText(constitution))
                appendLine()
            }

            // Agent-specific context
            val extra = gatherExtraContext(request)
            if (extra.isNotEmpty()) {
                appendLine(extra)
            }

            appendLine("## Agent Instructions")
            appendLine(agentInstructions)
        }

        return LanguageModelToolResult.Companion.success(context)
    }

    protected open fun gatherExtraContext(request: ToolInvocationRequest): String = ""

    /**
     * Read a file from the project, falling back to bundled resources for
     * known paths (.github/agents/, .specify/templates/).
     */
    protected fun readFileIfExists(relativePath: String): String? {
        return ResourceLoader.readFile(basePath, relativePath)
    }

    protected fun readFeatureArtifact(featureDir: String, fileName: String): String? {
        // Feature artifacts are project-only (no bundled fallback)
        val file = LocalFileSystem.getInstance()
            .findFileByIoFile(File(basePath, "specs/$featureDir/$fileName"))
        return if (file != null && !file.isDirectory) VfsUtilCore.loadText(file) else null
    }

    protected fun findCurrentFeatureDir(): String? {
        val specsDir = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, "specs"))
        if (specsDir == null || !specsDir.isDirectory) return null

        val result = ScriptRunner.exec(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), basePath)
        if (!result.success) return null

        val branch = result.output.trim()
        val match = Regex("^(\\d{3})-").find(branch) ?: return null
        val prefix = match.groupValues[1]

        return specsDir.children
            .filter { it.isDirectory && it.name.startsWith(prefix) }
            .firstOrNull()?.name
    }
}
