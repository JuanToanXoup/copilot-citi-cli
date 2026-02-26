package com.speckit.plugin.tools

import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationManager
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

class SpeckitAnalyzeProject : LanguageModelToolRegistration {

    override val toolDefinition = LanguageModelTool(
        "speckit_analyze_project",
        "Check spec-driven workflow prerequisites. Must be on a feature branch (NNN-name). Returns feature paths and available documents.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "mode" to mapOf(
                    "type" to "string",
                    "description" to "Output mode",
                    "enum" to listOf("json", "paths-only", "full")
                ),
                "require_tasks" to mapOf("type" to "boolean", "description" to "Require tasks.md to exist (for implementation phase)"),
                "include_tasks" to mapOf("type" to "boolean", "description" to "Include tasks.md in available docs list")
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
        val manager = ApplicationManager.getApplication().getService(ToolInvocationManager::class.java)
        val project = manager.findProjectForInvocation(request.identifier)
            ?: return LanguageModelToolResult.Companion.error("No project found for invocation")
        val basePath = project.basePath
            ?: return LanguageModelToolResult.Companion.error("No project base path")

        val mode = request.input?.get("mode")?.asString ?: "json"
        val requireTasks = request.input?.get("require_tasks")?.asBoolean ?: false
        val includeTasks = request.input?.get("include_tasks")?.asBoolean ?: false

        val paths = FeatureWorkspace.getFeaturePaths(basePath)
        val lfs = LocalFileSystem.getInstance()

        // paths-only mode: return paths without validation
        if (mode == "paths-only") {
            return LanguageModelToolResult.Companion.success(
                """{"REPO_ROOT":"${paths.repoRoot}","BRANCH":"${paths.currentBranch}","FEATURE_DIR":"${paths.featureDir}","FEATURE_SPEC":"${paths.featureSpec}","IMPL_PLAN":"${paths.implPlan}","TASKS":"${paths.tasks}"}"""
            )
        }

        // Validate feature branch (skip for non-git repos)
        if (paths.hasGit && !FeatureWorkspace.isFeatureBranch(paths.currentBranch)) {
            return LanguageModelToolResult.Companion.error(
                "Not on a feature branch. Current branch: ${paths.currentBranch}\n" +
                "Feature branches should be named like: 001-feature-name"
            )
        }

        // Validate feature directory exists
        val featureDirVf = lfs.findFileByIoFile(File(paths.featureDir))
        if (featureDirVf == null || !featureDirVf.isDirectory) {
            return LanguageModelToolResult.Companion.error(
                "Feature directory not found: ${paths.featureDir}\n" +
                "Run speckit_setup_feature first to create the feature structure."
            )
        }

        // Validate plan.md exists
        val planVf = lfs.findFileByIoFile(File(paths.implPlan))
        if (planVf == null || planVf.isDirectory) {
            return LanguageModelToolResult.Companion.error(
                "plan.md not found in ${paths.featureDir}\n" +
                "Run speckit_setup_plan first to create the implementation plan."
            )
        }

        // Validate tasks.md if required
        val tasksVf = lfs.findFileByIoFile(File(paths.tasks))
        if (requireTasks && (tasksVf == null || tasksVf.isDirectory)) {
            return LanguageModelToolResult.Companion.error(
                "tasks.md not found in ${paths.featureDir}\n" +
                "Run speckit_tasks first to create the task list."
            )
        }

        // Build list of available documents
        val docs = mutableListOf<String>()
        val researchVf = lfs.findFileByIoFile(File(paths.research))
        if (researchVf != null && !researchVf.isDirectory) docs.add("research.md")
        val dataModelVf = lfs.findFileByIoFile(File(paths.dataModel))
        if (dataModelVf != null && !dataModelVf.isDirectory) docs.add("data-model.md")
        val contractsVf = lfs.findFileByIoFile(File(paths.contractsDir))
        if (contractsVf != null && contractsVf.isDirectory && contractsVf.children.isNotEmpty()) {
            docs.add("contracts/")
        }
        val quickstartVf = lfs.findFileByIoFile(File(paths.quickstart))
        if (quickstartVf != null && !quickstartVf.isDirectory) docs.add("quickstart.md")
        if (includeTasks && tasksVf != null && !tasksVf.isDirectory) docs.add("tasks.md")

        // JSON output
        val jsonDocs = docs.joinToString(",") { "\"$it\"" }
        return LanguageModelToolResult.Companion.success(
            """{"FEATURE_DIR":"${paths.featureDir}","AVAILABLE_DOCS":[$jsonDocs]}"""
        )
    }
}
