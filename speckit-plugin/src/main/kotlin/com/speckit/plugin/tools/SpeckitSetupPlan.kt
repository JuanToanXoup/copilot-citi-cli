package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitSetupPlan(
    private val basePath: String
) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_setup_plan",
        "Copy the plan template into the current feature's spec directory. Must be on a feature branch.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "json" to mapOf("type" to "boolean", "description" to "Return JSON output (default: true)")
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
        val project = FeatureWorkspace.findProject(request)
        val paths = FeatureWorkspace.getFeaturePaths(basePath)

        // Validate feature branch
        if (paths.hasGit && !FeatureWorkspace.isFeatureBranch(paths.currentBranch)) {
            return LanguageModelToolResult.Companion.error(
                "Not on a feature branch. Current branch: ${paths.currentBranch}\n" +
                "Feature branches should be named like: 001-feature-name"
            )
        }

        // Create feature directory if needed
        val featureDir = File(paths.featureDir)
        if (!featureDir.isDirectory) {
            featureDir.mkdirs()
        }

        // Copy plan template (project-first, bundled-fallback via ResourceLoader)
        val templateContent = ResourceLoader.readTemplate(basePath, "plan-template.md")
        val planFile = File(paths.implPlan)

        if (templateContent != null) {
            planFile.writeText(templateContent)
        } else {
            // No template found anywhere — create empty file
            planFile.createNewFile()
        }

        // Refresh VFS so IntelliJ sees the new files immediately
        FeatureWorkspace.refreshVfs(project, paths.featureDir, paths.implPlan)

        return LanguageModelToolResult.Companion.success(
            """{"FEATURE_SPEC":"${paths.featureSpec}","IMPL_PLAN":"${paths.implPlan}","SPECS_DIR":"${paths.featureDir}","BRANCH":"${paths.currentBranch}","HAS_GIT":"${paths.hasGit}"}"""
        )
    }
}
