package com.speckit.plugin.tools

import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

// Kotlin reimplementation of .specify/scripts/bash/common.sh
// Resolves feature branches, spec directories, and feature paths
// without requiring speckit init or shell scripts.
object FeatureWorkspace {

    data class FeaturePaths(
        val repoRoot: String,
        val currentBranch: String,
        val hasGit: Boolean,
        val featureDir: String,
        val featureSpec: String,
        val implPlan: String,
        val tasks: String,
        val research: String,
        val dataModel: String,
        val quickstart: String,
        val contractsDir: String,
    )

    private val FEATURE_BRANCH_PATTERN = Regex("^\\d{3}-")

    fun getCurrentBranch(basePath: String): String {
        // 1. SPECIFY_FEATURE env var (for non-git repos)
        System.getenv("SPECIFY_FEATURE")?.takeIf { it.isNotBlank() }?.let { return it }

        // 2. git rev-parse
        try {
            val result = ScriptRunner.exec(
                listOf("git", "rev-parse", "--abbrev-ref", "HEAD"),
                basePath, 10
            )
            if (result.success) return result.output.trim()
        } catch (_: Exception) {}

        // 3. Latest feature directory as fallback
        val specsDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(basePath, "specs"))
        if (specsDir != null && specsDir.isDirectory) {
            specsDir.children
                .filter { it.isDirectory && FEATURE_BRANCH_PATTERN.containsMatchIn(it.name) }
                .maxByOrNull { it.name.substring(0, 3).toIntOrNull() ?: 0 }
                ?.let { return it.name }
        }

        return "main"
    }

    fun isFeatureBranch(branchName: String): Boolean =
        FEATURE_BRANCH_PATTERN.containsMatchIn(branchName)

    // Find feature dir by numeric prefix (supports multiple branches per spec)
    fun findFeatureDir(basePath: String, branchName: String): String {
        val specsPath = "$basePath/specs"
        val match = Regex("^(\\d{3})-").find(branchName) ?: return "$specsPath/$branchName"

        val prefix = match.groupValues[1]
        val specsDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(specsPath))
        if (specsDir == null || !specsDir.isDirectory) return "$specsPath/$branchName"

        val matches = specsDir.children
            .filter { it.isDirectory && it.name.startsWith("$prefix-") }
            .map { it.name }

        return when (matches.size) {
            1 -> "$specsPath/${matches[0]}"
            else -> "$specsPath/$branchName"
        }
    }

    fun getFeaturePaths(basePath: String): FeaturePaths {
        val branch = getCurrentBranch(basePath)
        val featureDir = findFeatureDir(basePath, branch)

        return FeaturePaths(
            repoRoot = basePath,
            currentBranch = branch,
            hasGit = hasGit(basePath),
            featureDir = featureDir,
            featureSpec = "$featureDir/spec.md",
            implPlan = "$featureDir/plan.md",
            tasks = "$featureDir/tasks.md",
            research = "$featureDir/research.md",
            dataModel = "$featureDir/data-model.md",
            quickstart = "$featureDir/quickstart.md",
            contractsDir = "$featureDir/contracts",
        )
    }

    fun hasGit(basePath: String): Boolean {
        return try {
            ScriptRunner.exec(listOf("git", "rev-parse", "--show-toplevel"), basePath, 5).success
        } catch (_: Exception) {
            false
        }
    }

    // Scan specs/ directories and git branches to find the next available number
    fun getNextFeatureNumber(basePath: String): Int {
        var highest = 0

        // Check specs directory
        val specsDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(basePath, "specs"))
        if (specsDir != null && specsDir.isDirectory) {
            specsDir.children.filter { it.isDirectory }.forEach { dir ->
                val num = Regex("^(\\d+)").find(dir.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (num > highest) highest = num
            }
        }

        // Check git branches (local only — no network calls in a resolver)
        try {
            val result = ScriptRunner.exec(listOf("git", "branch", "-a"), basePath, 10)
            if (result.success) {
                for (line in result.output.lines()) {
                    val clean = line.trim().removePrefix("* ").replace(Regex("^remotes/[^/]+/"), "")
                    val num = Regex("^(\\d{3})-").find(clean)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (num > highest) highest = num
                }
            }
        } catch (_: Exception) {}

        return highest + 1
    }

    // Generate branch suffix from description with stop-word filtering
    fun generateBranchName(description: String): String {
        val stopWords = setOf(
            "i", "a", "an", "the", "to", "for", "of", "in", "on", "at", "by", "with",
            "from", "is", "are", "was", "were", "be", "been", "being", "have", "has",
            "had", "do", "does", "did", "will", "would", "should", "could", "can",
            "may", "might", "must", "shall", "this", "that", "these", "those", "my",
            "your", "our", "their", "want", "need", "add", "get", "set"
        )

        val words = description.lowercase()
            .replace(Regex("[^a-z0-9]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val meaningful = words.filter { it !in stopWords && it.length >= 3 }
        val maxWords = if (meaningful.size == 4) 4 else 3

        return if (meaningful.isNotEmpty()) {
            meaningful.take(maxWords).joinToString("-")
        } else {
            words.take(3).joinToString("-")
        }
    }

    fun cleanBranchName(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')

}
