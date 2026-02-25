package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import java.io.File

class SpeckitSetupFeature(
    private val basePath: String
) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_setup_feature",
        "Create a new feature branch and spec scaffold. Auto-detects next feature number, creates specs/NNN-name/ directory with spec-template.md.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "description" to mapOf("type" to "string", "description" to "Short feature description for branch naming"),
                "short_name" to mapOf("type" to "string", "description" to "Optional custom short name (2-4 words) for the branch"),
                "number" to mapOf("type" to "integer", "description" to "Optional branch number (overrides auto-detection)")
            ),
            "required" to listOf("description")
        ),
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val project = FeatureWorkspace.findProject(request)
        val description = request.input?.get("description")?.asString
            ?: return LanguageModelToolResult.Companion.error("Missing required parameter: description")
        val shortName = request.input?.get("short_name")?.asString
        val numberOverride = request.input?.get("number")?.asInt

        // Generate branch suffix
        val branchSuffix = if (shortName != null) {
            FeatureWorkspace.cleanBranchName(shortName)
        } else {
            FeatureWorkspace.generateBranchName(description)
        }

        // Determine feature number
        val featureNumber = numberOverride ?: FeatureWorkspace.getNextFeatureNumber(basePath)
        val featureNum = String.format("%03d", featureNumber)
        var branchName = "$featureNum-$branchSuffix"

        // GitHub enforces a 244-byte limit on branch names
        if (branchName.length > 244) {
            val maxSuffix = 244 - 4 // NNN-
            val truncated = branchSuffix.take(maxSuffix).trimEnd('-')
            branchName = "$featureNum-$truncated"
        }

        // Create git branch (if git available)
        val hasGit = FeatureWorkspace.hasGit(basePath)
        if (hasGit) {
            val result = ScriptRunner.exec(
                listOf("git", "checkout", "-b", branchName),
                basePath, 10
            )
            if (!result.success) {
                return LanguageModelToolResult.Companion.error(
                    "Failed to create branch '$branchName': ${result.output}"
                )
            }
        }

        // Create feature directory
        val specsDir = File(basePath, "specs")
        val featureDir = File(specsDir, branchName)
        featureDir.mkdirs()

        // Copy spec template (project-first, bundled-fallback via ResourceLoader)
        val specFile = File(featureDir, "spec.md")
        val templateContent = ResourceLoader.readTemplate(basePath, "spec-template.md")
        if (templateContent != null) {
            specFile.writeText(templateContent)
        } else {
            specFile.createNewFile()
        }

        // Refresh VFS so IntelliJ sees the new files immediately
        FeatureWorkspace.refreshVfs(project, featureDir.absolutePath, specFile.absolutePath)

        return LanguageModelToolResult.Companion.success(
            """{"BRANCH_NAME":"$branchName","SPEC_FILE":"${specFile.absolutePath}","FEATURE_NUM":"$featureNum"}"""
        )
    }
}
