package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File
import java.time.LocalDate

class SpeckitUpdateAgents(
    private val basePath: String
) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_update_agents",
        "Update agent context files from the current feature's plan.md. Optionally target a specific agent type.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "agent_type" to mapOf(
                    "type" to "string",
                    "description" to "Optional agent type to update (e.g. claude, gemini, copilot, cursor-agent, qwen). Omit to update all."
                )
            ),
            "required" to listOf<String>()
        ),
        null,
        "function",
        "enabled"
    )

    // Agent type -> (relative file path, display name)
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
        val project = FeatureWorkspace.findProject(request)
        val agentType = request.input?.get("agent_type")?.asString

        val paths = FeatureWorkspace.getFeaturePaths(basePath)

        // Validate plan.md exists
        val planFile = File(paths.implPlan)
        if (!planFile.isFile) {
            return LanguageModelToolResult.Companion.error(
                "No plan.md found at ${paths.implPlan}\n" +
                "Make sure you're working on a feature with a corresponding spec directory."
            )
        }

        // Parse plan data
        val planData = parsePlanData(planFile)
        val currentDate = LocalDate.now().toString()
        val results = mutableListOf<String>()

        if (agentType != null) {
            // Update specific agent
            val config = agentConfigs[agentType]
                ?: return LanguageModelToolResult.Companion.error(
                    "Unknown agent type '$agentType'. " +
                    "Expected: ${agentConfigs.keys.sorted().joinToString("|")}"
                )
            val result = updateAgentFile(config, planData, paths.currentBranch, currentDate)
            results.add(result)
        } else {
            // Update all existing agent files
            var foundAgent = false
            for ((_, config) in agentConfigs) {
                val targetFile = File(basePath, config.relativePath)
                if (targetFile.isFile) {
                    val result = updateAgentFile(config, planData, paths.currentBranch, currentDate)
                    results.add(result)
                    foundAgent = true
                }
            }
            // If no agent files exist, create default Claude file
            if (!foundAgent) {
                val claudeConfig = agentConfigs["claude"]!!
                val result = updateAgentFile(claudeConfig, planData, paths.currentBranch, currentDate)
                results.add(result)
            }
        }

        // Refresh VFS
        val updatedPaths = agentConfigs.values.map { File(basePath, it.relativePath).absolutePath }.toTypedArray()
        FeatureWorkspace.refreshVfs(project, *updatedPaths)

        return LanguageModelToolResult.Companion.success(results.joinToString("\n"))
    }

    private fun parsePlanData(planFile: File): PlanData {
        val content = planFile.readText()
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

    private fun updateAgentFile(
        config: AgentConfig,
        planData: PlanData,
        branch: String,
        currentDate: String
    ): String {
        val targetFile = File(basePath, config.relativePath)

        // Create parent directories if needed
        targetFile.parentFile?.mkdirs()

        return if (targetFile.isFile) {
            updateExistingFile(targetFile, planData, branch, currentDate)
            "Updated ${config.displayName}: ${config.relativePath}"
        } else {
            createNewFile(targetFile, planData, branch, currentDate)
            "Created ${config.displayName}: ${config.relativePath}"
        }
    }

    private fun createNewFile(
        targetFile: File,
        planData: PlanData,
        branch: String,
        currentDate: String
    ) {
        val template = ResourceLoader.readTemplate(basePath, "agent-file-template.md")
        if (template == null) {
            // No template — create minimal agent file
            val techStack = formatTechStack(planData)
            targetFile.writeText(buildString {
                appendLine("# ${File(basePath).name}")
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
            })
            return
        }

        val projectName = File(basePath).name
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

        val output = template
            .replace("[PROJECT NAME]", projectName)
            .replace("[DATE]", currentDate)
            .replace("[EXTRACTED FROM ALL PLAN.MD FILES]", techEntry)
            .replace("[ACTUAL STRUCTURE FROM PLANS]", projectStructure)
            .replace("[ONLY COMMANDS FOR ACTIVE TECHNOLOGIES]", commands)
            .replace("[LANGUAGE-SPECIFIC, ONLY FOR LANGUAGES IN USE]", conventions)
            .replace("[LAST 3 FEATURES AND WHAT THEY ADDED]", recentChange)

        targetFile.writeText(output)
    }

    private fun updateExistingFile(
        targetFile: File,
        planData: PlanData,
        branch: String,
        currentDate: String
    ) {
        val lines = targetFile.readLines().toMutableList()
        val techStack = formatTechStack(planData)
        val existingContent = targetFile.readText()

        // Prepare new entries
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
        var hasTechSection = lines.any { it.startsWith("## Active Technologies") }
        var hasChangesSection = lines.any { it.startsWith("## Recent Changes") }

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
                    if (newChangeEntry != null) {
                        output.appendLine(newChangeEntry)
                    }
                    inChangesSection = true
                    changesAdded = true
                }
                inChangesSection && line.startsWith("## ") -> {
                    output.appendLine(line)
                    inChangesSection = false
                }
                inChangesSection && line.startsWith("- ") -> {
                    // Keep only first 2 existing changes
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

        // Add sections at end if they didn't exist
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

        targetFile.writeText(output.toString())
    }

    private fun formatTechStack(planData: PlanData): String {
        val parts = mutableListOf<String>()
        if (planData.language.isNotBlank()) parts.add(planData.language)
        if (planData.framework.isNotBlank()) parts.add(planData.framework)
        return parts.joinToString(" + ")
    }
}
