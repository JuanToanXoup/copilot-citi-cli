package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationManager
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import com.intellij.openapi.application.ApplicationManager

class SpeckitSetupPlan : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_setup_plan",
        "Resolve the plan template path for the current feature. Returns the target file path and template content — use create_file to write the plan.",
        mapOf(
            "type" to "object",
            "properties" to mapOf<String, Any>(),
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

        val paths = FeatureWorkspace.getFeaturePaths(basePath)

        // Validate feature branch
        if (paths.hasGit && !FeatureWorkspace.isFeatureBranch(paths.currentBranch)) {
            return LanguageModelToolResult.Companion.error(
                "Not on a feature branch. Current branch: ${paths.currentBranch}\n" +
                "Feature branches should be named like: 001-feature-name"
            )
        }

        // Load plan template (project-first, bundled-fallback)
        val templateContent = ResourceLoader.readTemplate(basePath, "plan-template.md")

        val output = buildString {
            appendLine("## Plan Setup")
            appendLine("- **Branch**: ${paths.currentBranch}")
            appendLine("- **Feature directory**: ${paths.featureDir}")
            appendLine("- **Plan file path**: ${paths.implPlan}")
            appendLine()
            appendLine("## Next Steps")
            appendLine("1. Create the feature directory if it doesn't exist: run `mkdir -p ${paths.featureDir}` using `run_in_terminal`")
            if (templateContent != null) {
                appendLine("2. Create `${paths.implPlan}` using `create_file` with the template content below")
                appendLine()
                appendLine("## Plan Template Content")
                appendLine("```")
                appendLine(templateContent)
                appendLine("```")
            } else {
                appendLine("2. Create an empty `${paths.implPlan}` using `create_file`")
            }
        }

        return LanguageModelToolResult.Companion.success(output)
    }
}
