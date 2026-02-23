package com.citigroup.copilotchat.workingset

import com.citigroup.copilotchat.config.CopilotChatSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Executes Copilot-attributed git commits using a write-stage-restore strategy
 * via IntelliJ's GeneralCommandLine API.
 *
 * Because IntelliJ's CheckinHandler API cannot modify the commit author or message,
 * this service writes the agent's tracked content to disk, runs `git commit --author`
 * through GeneralCommandLine, then restores the original disk content.
 */
@Service(Service.Level.PROJECT)
class CopilotCommitService(private val project: Project) {

    private val log = Logger.getInstance(CopilotCommitService::class.java)

    data class CommitResult(
        val success: Boolean,
        val committedFiles: Int,
        val message: String,
    )

    companion object {
        private const val GIT_TIMEOUT_MS = 30_000

        fun getInstance(project: Project): CopilotCommitService =
            project.getService(CopilotCommitService::class.java)
    }

    /**
     * Commit AI-tracked files with Copilot as the author.
     *
     * Strategy:
     * 1. Save current disk content for each file
     * 2. Write the agent's known content (from WorkingSetService snapshots) to disk
     * 3. Run `git commit --author="GitHub Copilot (model) <copilot@copilot.example>" -- <paths>`
     * 4. Restore original disk content (in finally block)
     *
     * @param changes The AI-tracked file changes to commit
     * @param commitMessage The commit message (Generated-by trailer is appended automatically)
     */
    fun commitCopilotChanges(changes: List<FileChange>, commitMessage: String): CommitResult {
        if (changes.isEmpty()) return CommitResult(true, 0, "No files to commit")

        val workDir = project.basePath ?: return CommitResult(false, 0, "No project base path")
        val model = CopilotChatSettings.getInstance().defaultModel.ifBlank { "unknown" }
        val author = "GitHub Copilot ($model) <copilot@copilot.example>"

        val fullMessage = buildString {
            append(commitMessage)
            append("\n\n")
            append("Generated-by: github-copilot")
        }

        // Save disk state before writing agent content
        val diskSnapshots = mutableMapOf<String, String?>()
        try {
            // Step 1: snapshot current disk content and write agent content
            for (change in changes) {
                val file = File(change.absolutePath)
                diskSnapshots[change.absolutePath] = if (file.exists()) file.readText() else null
                file.parentFile?.mkdirs()
                file.writeText(change.currentContent)
            }

            // Step 2: git add -- <paths>
            val addCmd = GeneralCommandLine("git", "add", "--")
                .withWorkDirectory(workDir)
                .withCharset(StandardCharsets.UTF_8)
            for (change in changes) {
                addCmd.addParameter(change.absolutePath)
            }
            val addResult = CapturingProcessHandler(addCmd).runProcess(GIT_TIMEOUT_MS)
            if (addResult.exitCode != 0) {
                return CommitResult(false, 0, "git add failed: ${addResult.stderr.ifBlank { addResult.stdout }}")
            }

            // Step 3: git commit --author with trailer
            val commitCmd = GeneralCommandLine(
                "git", "commit",
                "--author", author,
                "-m", fullMessage,
                "--",
            )
                .withWorkDirectory(workDir)
                .withCharset(StandardCharsets.UTF_8)
            for (change in changes) {
                commitCmd.addParameter(change.absolutePath)
            }
            val commitResult = CapturingProcessHandler(commitCmd).runProcess(GIT_TIMEOUT_MS)
            if (commitResult.exitCode != 0) {
                return CommitResult(false, 0, "git commit failed: ${commitResult.stderr.ifBlank { commitResult.stdout }}")
            }

            log.info("Copilot commit created for ${changes.size} file(s): ${commitResult.stdout.trim()}")

            // Log hunk-level info for traceability
            for (change in changes) {
                val hunks = HunkDiffEngine.computeAgentHunks(change.originalContent, change.currentContent)
                val hunkSummary = hunks.joinToString(", ") { "L${it.startLine}-${it.endLine}" }
                log.info("  ${change.relativePath}: agent hunks [$hunkSummary]")
            }

            return CommitResult(true, changes.size, "Committed ${changes.size} file(s) as GitHub Copilot")

        } finally {
            // Step 4: restore original disk content
            for ((path, originalDisk) in diskSnapshots) {
                try {
                    val file = File(path)
                    if (originalDisk != null) {
                        file.writeText(originalDisk)
                    }
                } catch (e: Exception) {
                    log.warn("Failed to restore disk content for $path: ${e.message}")
                }
            }

            // Refresh VFS so IntelliJ sees the changes
            LocalFileSystem.getInstance().refresh(true)
        }
    }
}
