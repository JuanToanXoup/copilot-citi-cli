package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest

class SpeckitSetupFeature(
    private val basePath: String
) : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_setup_feature",
        "Resolve the next feature branch name and spec scaffold paths. Auto-detects next feature number from existing branches and specs. Returns the branch name, paths, and template content — use run_in_terminal to create the branch and create_file for the spec.",
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
            val maxSuffix = 244 - 4
            val truncated = branchSuffix.take(maxSuffix).trimEnd('-')
            branchName = "$featureNum-$truncated"
        }

        // Load spec template (project-first, bundled-fallback)
        val specTemplate = ResourceLoader.readTemplate(basePath, "spec-template.md")

        val specDir = "$basePath/specs/$branchName"
        val specFile = "$specDir/spec.md"
        val hasGit = FeatureWorkspace.hasGit(basePath)

        val output = buildString {
            appendLine("## Feature Setup")
            appendLine("- **Branch name**: $branchName")
            appendLine("- **Feature number**: $featureNum")
            appendLine("- **Spec directory**: $specDir")
            appendLine("- **Spec file**: $specFile")
            appendLine("- **Git available**: $hasGit")
            appendLine()
            appendLine("## Next Steps")
            if (hasGit) {
                appendLine("1. Create the branch: run `git checkout -b $branchName` using `run_in_terminal`")
                appendLine("2. Create the spec directory: run `mkdir -p $specDir` using `run_in_terminal`")
            } else {
                appendLine("1. Create the spec directory: run `mkdir -p $specDir` using `run_in_terminal`")
            }
            if (specTemplate != null) {
                appendLine("3. Create `$specFile` using `create_file` with the template content below")
                appendLine()
                appendLine("## Spec Template Content")
                appendLine("```")
                appendLine(specTemplate)
                appendLine("```")
            } else {
                appendLine("3. Create an empty `$specFile` using `create_file`")
            }
        }

        return LanguageModelToolResult.Companion.success(output)
    }
}
