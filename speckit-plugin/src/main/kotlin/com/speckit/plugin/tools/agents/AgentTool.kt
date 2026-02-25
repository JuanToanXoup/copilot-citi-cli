package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
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
        val agentFile = File(basePath, ".github/agents/$agentFileName")
        if (!agentFile.exists()) {
            return LanguageModelToolResult.Companion.error("Agent definition not found: $agentFileName")
        }

        val agentInstructions = agentFile.readText()

        val context = buildString {
            appendLine("# Agent: $toolName")
            appendLine()

            // Project context from prerequisites
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

            // Constitution (governance rules agents must follow)
            val constitution = File(basePath, ".specify/memory/constitution.md")
            if (constitution.exists()) {
                appendLine("## Constitution")
                appendLine(constitution.readText())
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

    protected fun readFileIfExists(relativePath: String): String? {
        val file = File(basePath, relativePath)
        return if (file.exists()) file.readText() else null
    }

    protected fun readFeatureArtifact(featureDir: String, fileName: String): String? {
        return readFileIfExists("specs/$featureDir/$fileName")
    }

    protected fun findCurrentFeatureDir(): String? {
        val specsDir = File(basePath, "specs")
        if (!specsDir.isDirectory) return null

        // Get current branch to match feature number
        val result = ScriptRunner.exec(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), basePath)
        if (!result.success) return null

        val branch = result.output.trim()
        val match = Regex("^(\\d{3})-").find(branch) ?: return null
        val prefix = match.groupValues[1]

        return specsDir.listFiles { f -> f.isDirectory && f.name.startsWith(prefix) }
            ?.firstOrNull()?.name
    }
}
