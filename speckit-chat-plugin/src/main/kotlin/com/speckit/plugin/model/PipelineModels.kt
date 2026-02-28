package com.speckit.plugin.model

import java.io.File

enum class StepStatus { NOT_STARTED, READY, IN_PROGRESS, COMPLETED, BLOCKED }

data class ArtifactCheck(
    val relativePath: String,
    val label: String,
    val isDirectory: Boolean = false,
    val requireNonEmpty: Boolean = false,
    val isRepoRelative: Boolean = false,
    val altRelativePath: String? = null
)

data class CheckResult(val artifact: ArtifactCheck, val exists: Boolean, val detail: String, val resolvedFile: File? = null)

data class ChecklistStatus(
    val fileName: String,
    val total: Int,
    val completed: Int,
    val file: File
) {
    val isComplete get() = completed >= total
}

data class PipelineStepDef(
    val number: Int,
    val id: String,
    val name: String,
    val description: String,
    val isOptional: Boolean,
    val prerequisites: List<ArtifactCheck>,
    val outputs: List<ArtifactCheck>,
    val handsOffTo: List<String>,
    val agentFileName: String,
    val parentId: String? = null,
    val defaultArgs: String = ""
)

class PipelineStepState {
    @Volatile var status: StepStatus = StepStatus.NOT_STARTED
    var prerequisiteResults: List<CheckResult> = emptyList()
    var outputResults: List<CheckResult> = emptyList()
}

data class FeaturePaths(
    val basePath: String,
    val branch: String,
    val isFeatureBranch: Boolean,
    val featureDir: String?
)

data class FeatureEntry(
    val dirName: String,
    val path: String,
    var completedSteps: Int = 0,
    var totalOutputSteps: Int = 0
)
