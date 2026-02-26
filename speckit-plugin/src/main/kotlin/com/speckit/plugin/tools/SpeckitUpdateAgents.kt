package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationManager
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.time.LocalDate

class SpeckitUpdateAgents : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_update_agents",
        "Generate updated agent context file content from the current feature's plan.md. Returns the file paths and content — use create_file or insert_edit_into_file to write them.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "agent_type" to mapOf(
                    "type" to "string",
                    "description" to "Optional agent type to update (e.g. claude, gemini, copilot, cursor-agent, qwen). Omit to update all existing."
                )
            ),
            "required" to listOf<String>()
        ),
        null,
        "function",
        "enabled"
    )

    private val agentConfigs = mapOf(
        "claude" to AgentConfig("CLAUDE.md", "Claude Code"),
        "gemini" to AgentConfig("GEMINI.md", "Gemini CLI"),
        "copilot" to AgentConfig(".github/agents/copilot-instructions.md", "GitHub Copilot"),
        "cursor-agent" to AgentConfig(".cursor/rules/specify-rules.mdc", "Cursor IDE"),
        "qwen" to AgentConfig("QWEN.md", "Qwen Code"),
        "opencode" to AgentConfig("AGENTS.md", "opencode"),
        "codex" to AgentConfig("AGENTS.md", "Codex CLI"),
        "windsurf" to AgentConfig(".windsurf/rules/specify-rules.md", "Windsurf"),
        "kilocode" to AgentConfig(".kilocode/rules/specify-rules.md", "Kilo Code"),
        "auggie" to AgentConfig(".augment/rules/specify-rules.md", "Auggie CLI"),
        "roo" to AgentConfig(".roo/rules/specify-rules.md", "Roo Code"),
        "codebuddy" to AgentConfig("CODEBUDDY.md", "CodeBuddy CLI"),
        "qodercli" to AgentConfig("QODER.md", "Qoder CLI"),
        "amp" to AgentConfig("AGENTS.md", "Amp"),
        "shai" to AgentConfig("SHAI.md", "SHAI"),
        "q" to AgentConfig("AGENTS.md", "Amazon Q Developer CLI"),
        "agy" to AgentConfig(".agent/rules/specify-rules.md", "Antigravity"),
        "bob" to AgentConfig("AGENTS.md", "IBM Bob"),
    )

    private data class AgentConfig(val relativePath: String, val displayName: String)

    private data class PlanData(
        val language: String,
        val framework: String,
        val database: String,
        val projectType: String,
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val invocationManager = ApplicationManager.getApplication().getService(ToolInvocationManager::class.java)
        val project = invocationManager.findProjectForInvocation(request.identifier)
            ?: return LanguageModelToolResult.Companion.error("No project found for invocation")
        val basePath = project.basePath
            ?: return LanguageModelToolResult.Companion.error("No project base path")

        val agentType = request.input?.get("agent_type")?.asString
        val lfs = LocalFileSystem.getInstance()

        val paths = FeatureWorkspace.getFeaturePaths(basePath)

        val planFile = lfs.findFileByIoFile(File(paths.implPlan))
        if (planFile == null || planFile.isDirectory) {
            return LanguageModelToolResult.Companion.error(
                "No plan.md found at ${paths.implPlan}\n" +
                "Make sure you're working on a feature with a plan."
            )
        }

        val planData = parsePlanData(planFile)
        val currentDate = LocalDate.now().toString()
        val filesToWrite = mutableListOf<FileAction>()

        if (agentType != null) {
            val config = agentConfigs[agentType]
                ?: return LanguageModelToolResult.Companion.error(
                    "Unknown agent type '$agentType'. " +
                    "Expected: ${agentConfigs.keys.sorted().joinToString("|")}"
                )
            filesToWrite.add(generateFileAction(basePath, config, planData, paths.currentBranch, currentDate))
        } else {
            var foundAgent = false
            for ((_, config) in agentConfigs) {
                val targetFile = lfs.findFileByIoFile(File(basePath, config.relativePath))
                if (targetFile != null && !targetFile.isDirectory) {
                    filesToWrite.add(generateFileAction(basePath, config, planData, paths.currentBranch, currentDate))
                    foundAgent = true
                }
            }
            if (!foundAgent) {
                val copilotConfig = agentConfigs["copilot"]!!
                filesToWrite.add(generateFileAction(basePath, copilotConfig, planData, paths.currentBranch, currentDate))
            }
        }

        val output = buildString {
            appendLine("## Agent Context Updates")
            appendLine("- **Branch**: ${paths.currentBranch}")
            appendLine("- **Technology**: ${formatTechStack(planData)}")
            appendLine("- **Files to update**: ${filesToWrite.size}")
            appendLine()
            appendLine("## Next Steps")
            appendLine("Use `create_file` or `insert_edit_into_file` to write each file below.")
            appendLine()
            for (action in filesToWrite) {
                appendLine("---")
                appendLine("### ${action.displayName}")
                appendLine("- **Path**: $basePath/${action.relativePath}")
                appendLine("- **Action**: ${action.action}")
                appendLine()
                appendLine("**Content:**")
                appendLine("```")
                appendLine(action.content)
                appendLine("```")
                appendLine()
            }
        }

        return LanguageModelToolResult.Companion.success(output)
    }

    private data class FileAction(
        val relativePath: String,
        val displayName: String,
        val action: String,
        val content: String,
    )

    private fun generateFileAction(
        basePath: String,
        config: AgentConfig,
        planData: PlanData,
        branch: String,
        currentDate: String
    ): FileAction {
        val targetFile = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, config.relativePath))
        return if (targetFile != null && !targetFile.isDirectory) {
            FileAction(
                config.relativePath, config.displayName, "update",
                generateUpdatedContent(targetFile, planData, branch, currentDate)
            )
        } else {
            FileAction(
                config.relativePath, config.displayName, "create",
                generateNewContent(basePath, planData, branch, currentDate)
            )
        }
    }

    private fun generateNewContent(basePath: String, planData: PlanData, branch: String, currentDate: String): String {
        val template = ResourceLoader.readTemplate(basePath, "agent-file-template.md")
        if (template == null) {
            val techStack = formatTechStack(planData)
            return buildString {
                appendLine("# ${basePath.substringAfterLast('/')}")
                appendLine()
                appendLine("**Last updated**: $currentDate")
                appendLine()
                if (techStack.isNotBlank()) {
                    appendLine("## Active Technologies")
                    appendLine("- $techStack ($branch)")
                    appendLine()
                }
                appendLine("## Recent Changes")
                appendLine("- $branch: Added $techStack")
            }
        }

        val projectName = basePath.substringAfterLast('/')
        val techStack = formatTechStack(planData)
        val techEntry = if (techStack.isNotBlank()) "- $techStack ($branch)" else "- ($branch)"
        val recentChange = if (techStack.isNotBlank()) "- $branch: Added $techStack" else "- $branch: Added"
        val projectStructure = if (planData.projectType.contains("web", ignoreCase = true)) {
            "backend/\nfrontend/\ntests/"
        } else {
            "src/\ntests/"
        }
        val commands = when {
            planData.language.contains("Python", ignoreCase = true) -> "cd src && pytest && ruff check ."
            planData.language.contains("Rust", ignoreCase = true) -> "cargo test && cargo clippy"
            planData.language.contains("JavaScript", ignoreCase = true) ||
            planData.language.contains("TypeScript", ignoreCase = true) -> "npm test && npm run lint"
            else -> "# Add commands for ${planData.language}"
        }
        val conventions = if (planData.language.isNotBlank()) {
            "${planData.language}: Follow standard conventions"
        } else {
            "Follow standard conventions"
        }

        return template
            .replace("[PROJECT NAME]", projectName)
            .replace("[DATE]", currentDate)
            .replace("[EXTRACTED FROM ALL PLAN.MD FILES]", techEntry)
            .replace("[ACTUAL STRUCTURE FROM PLANS]", projectStructure)
            .replace("[ONLY COMMANDS FOR ACTIVE TECHNOLOGIES]", commands)
            .replace("[LANGUAGE-SPECIFIC, ONLY FOR LANGUAGES IN USE]", conventions)
            .replace("[LAST 3 FEATURES AND WHAT THEY ADDED]", recentChange)
    }

    private fun generateUpdatedContent(
        targetFile: VirtualFile,
        planData: PlanData,
        branch: String,
        currentDate: String
    ): String {
        val existingContent = VfsUtilCore.loadText(targetFile)
        val lines = existingContent.lines()
        val techStack = formatTechStack(planData)

        val newTechEntries = mutableListOf<String>()
        if (techStack.isNotBlank() && !existingContent.contains(techStack)) {
            newTechEntries.add("- $techStack ($branch)")
        }
        if (planData.database.isNotBlank() && !existingContent.contains(planData.database)) {
            newTechEntries.add("- ${planData.database} ($branch)")
        }
        val newChangeEntry = when {
            techStack.isNotBlank() -> "- $branch: Added $techStack"
            planData.database.isNotBlank() -> "- $branch: Added ${planData.database}"
            else -> null
        }

        val output = StringBuilder()
        var inTechSection = false
        var inChangesSection = false
        var techAdded = false
        var changesAdded = false
        var existingChangesCount = 0
        val hasTechSection = lines.any { it.startsWith("## Active Technologies") }
        val hasChangesSection = lines.any { it.startsWith("## Recent Changes") }

        for (line in lines) {
            when {
                line == "## Active Technologies" -> {
                    output.appendLine(line)
                    inTechSection = true
                }
                inTechSection && line.startsWith("## ") -> {
                    if (!techAdded && newTechEntries.isNotEmpty()) {
                        newTechEntries.forEach { output.appendLine(it) }
                        techAdded = true
                    }
                    output.appendLine(line)
                    inTechSection = false
                }
                inTechSection && line.isBlank() -> {
                    if (!techAdded && newTechEntries.isNotEmpty()) {
                        newTechEntries.forEach { output.appendLine(it) }
                        techAdded = true
                    }
                    output.appendLine(line)
                }
                line == "## Recent Changes" -> {
                    output.appendLine(line)
                    if (newChangeEntry != null) output.appendLine(newChangeEntry)
                    inChangesSection = true
                    changesAdded = true
                }
                inChangesSection && line.startsWith("## ") -> {
                    output.appendLine(line)
                    inChangesSection = false
                }
                inChangesSection && line.startsWith("- ") -> {
                    if (existingChangesCount < 2) {
                        output.appendLine(line)
                        existingChangesCount++
                    }
                }
                line.contains("**Last updated**:") && line.contains(Regex("\\d{4}-\\d{2}-\\d{2}")) -> {
                    output.appendLine(line.replace(Regex("\\d{4}-\\d{2}-\\d{2}"), currentDate))
                }
                else -> output.appendLine(line)
            }
        }

        if (!hasTechSection && newTechEntries.isNotEmpty()) {
            output.appendLine()
            output.appendLine("## Active Technologies")
            newTechEntries.forEach { output.appendLine(it) }
        }
        if (!hasChangesSection && newChangeEntry != null) {
            output.appendLine()
            output.appendLine("## Recent Changes")
            output.appendLine(newChangeEntry)
        }

        return output.toString()
    }

    private fun parsePlanData(planFile: VirtualFile): PlanData {
        val content = VfsUtilCore.loadText(planFile)
        return PlanData(
            language = extractPlanField(content, "Language/Version"),
            framework = extractPlanField(content, "Primary Dependencies"),
            database = extractPlanField(content, "Storage"),
            projectType = extractPlanField(content, "Project Type"),
        )
    }

    private fun extractPlanField(content: String, fieldName: String): String {
        val pattern = Regex("^\\*\\*${Regex.escape(fieldName)}\\*\\*:\\s*(.+)$", RegexOption.MULTILINE)
        val match = pattern.find(content) ?: return ""
        val value = match.groupValues[1].trim()
        if (value == "NEEDS CLARIFICATION" || value == "N/A") return ""
        return value
    }

    private fun formatTechStack(planData: PlanData): String {
        val parts = mutableListOf<String>()
        if (planData.language.isNotBlank()) parts.add(planData.language)
        if (planData.framework.isNotBlank()) parts.add(planData.framework)
        return parts.joinToString(" + ")
    }
}
