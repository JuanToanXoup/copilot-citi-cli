package com.citigroup.copilotchat.agent

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/** Metadata about a subagent's worktree. */
data class WorktreeInfo(
    val worktreePath: String,
    val branchName: String,
    val agentId: String,
)

/** A single file change detected in a worktree relative to the parent branch. */
data class WorktreeFileChange(
    val relativePath: String,
    val originalContent: String?,
    val newContent: String,
    val isNew: Boolean,
)

/**
 * Manages git worktree lifecycle for subagents with `forkContext: true`.
 *
 * Each worktree is created under `<project>/.copilot-chat/worktrees/<agentId>/`
 * on a branch named `copilot-worktree-<agentId>`.
 */
object WorktreeManager {

    private val log = Logger.getInstance(WorktreeManager::class.java)

    /**
     * Create a git worktree for the given subagent.
     * @throws RuntimeException if the git command fails.
     */
    fun createWorktree(projectBasePath: String, agentId: String): WorktreeInfo {
        val branchName = "copilot-worktree-$agentId"
        val worktreeDir = File(projectBasePath, ".copilot-chat/worktrees/$agentId")
        worktreeDir.parentFile?.mkdirs()

        runGit(projectBasePath, "worktree", "add", "-b", branchName, worktreeDir.absolutePath)
        log.info("Created worktree at ${worktreeDir.absolutePath} on branch $branchName")

        return WorktreeInfo(
            worktreePath = worktreeDir.absolutePath,
            branchName = branchName,
            agentId = agentId,
        )
    }

    /**
     * Generate a list of file changes between the worktree branch and its parent (HEAD at creation).
     * Returns an empty list if no files were modified.
     */
    fun generateDiff(worktreeInfo: WorktreeInfo, mainWorkspace: String): List<WorktreeFileChange> {
        // Get list of changed files: status\trelativePath
        val diffOutput = runGit(
            worktreeInfo.worktreePath,
            "diff", "--name-status", "HEAD~0..HEAD",
        )

        // Also check uncommitted changes in the worktree
        val uncommitted = runGit(worktreeInfo.worktreePath, "diff", "--name-only")
        val untracked = runGit(worktreeInfo.worktreePath, "ls-files", "--others", "--exclude-standard")

        // Collect all changed relative paths with their status
        val changedFiles = mutableMapOf<String, Boolean>() // relativePath -> isNew

        // Parse committed changes (git diff --name-status against the base)
        val baseRef = runGit(worktreeInfo.worktreePath, "merge-base", "HEAD", worktreeInfo.branchName).trim()
        val committedDiff = runGit(worktreeInfo.worktreePath, "diff", "--name-status", baseRef)
        for (line in committedDiff.lines().filter { it.isNotBlank() }) {
            val parts = line.split("\t", limit = 2)
            if (parts.size < 2) continue
            val status = parts[0].trim()
            val relPath = parts[1].trim()
            changedFiles[relPath] = status == "A"
        }

        // Parse uncommitted modified files
        for (line in uncommitted.lines().filter { it.isNotBlank() }) {
            val relPath = line.trim()
            if (relPath !in changedFiles) changedFiles[relPath] = false
        }

        // Parse untracked files
        for (line in untracked.lines().filter { it.isNotBlank() }) {
            changedFiles[line.trim()] = true
        }

        if (changedFiles.isEmpty()) return emptyList()

        return changedFiles.map { (relPath, isNew) ->
            val worktreeFile = File(worktreeInfo.worktreePath, relPath)
            val mainFile = File(mainWorkspace, relPath)

            WorktreeFileChange(
                relativePath = relPath,
                originalContent = if (isNew || !mainFile.exists()) null else mainFile.readText(),
                newContent = if (worktreeFile.exists()) worktreeFile.readText() else "",
                isNew = isNew,
            )
        }
    }

    /**
     * Copy changed files from the worktree back to the main workspace.
     */
    fun applyChanges(changes: List<WorktreeFileChange>, mainWorkspace: String) {
        for (change in changes) {
            val target = File(mainWorkspace, change.relativePath)
            target.parentFile?.mkdirs()
            target.writeText(change.newContent)
            log.info("Applied worktree change: ${change.relativePath}")
        }
    }

    /**
     * Remove the worktree directory and delete the branch.
     */
    fun removeWorktree(worktreeInfo: WorktreeInfo, projectBasePath: String) {
        try {
            runGit(projectBasePath, "worktree", "remove", "--force", worktreeInfo.worktreePath)
        } catch (e: Exception) {
            // Worktree may already be removed; try manual cleanup
            log.warn("git worktree remove failed, attempting manual cleanup: ${e.message}")
            File(worktreeInfo.worktreePath).deleteRecursively()
            try {
                runGit(projectBasePath, "worktree", "prune")
            } catch (_: Exception) {}
        }

        try {
            runGit(projectBasePath, "branch", "-D", worktreeInfo.branchName)
        } catch (e: Exception) {
            log.warn("Failed to delete worktree branch ${worktreeInfo.branchName}: ${e.message}")
        }

        log.info("Removed worktree for ${worktreeInfo.agentId}")
    }

    /**
     * Clean up any stale worktrees left from a previous crash.
     * Called at startup.
     */
    fun cleanupStaleWorktrees(projectBasePath: String) {
        val worktreesDir = File(projectBasePath, ".copilot-chat/worktrees")
        if (!worktreesDir.exists()) return

        val dirs = worktreesDir.listFiles()?.filter { it.isDirectory } ?: return
        if (dirs.isEmpty()) return

        log.info("Cleaning up ${dirs.size} stale worktree(s)")
        for (dir in dirs) {
            try {
                runGit(projectBasePath, "worktree", "remove", "--force", dir.absolutePath)
            } catch (_: Exception) {
                dir.deleteRecursively()
            }
            // Try to remove the branch too
            try {
                runGit(projectBasePath, "branch", "-D", "copilot-worktree-${dir.name}")
            } catch (_: Exception) {}
        }

        try {
            runGit(projectBasePath, "worktree", "prune")
        } catch (_: Exception) {}
    }

    private fun runGit(workDir: String, vararg args: String): String {
        val cmd = listOf("git") + args.toList()
        val process = ProcessBuilder(cmd)
            .directory(File(workDir))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("git ${args.first()} failed (exit $exitCode): $output")
        }
        return output
    }
}
