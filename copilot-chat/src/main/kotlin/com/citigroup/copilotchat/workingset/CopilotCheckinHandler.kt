package com.citigroup.copilotchat.workingset

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory

/**
 * Auto-injects Copilot attribution when the developer commits files that were
 * modified by the AI agent. Replaces the previous warning-based approach — there
 * is no "commit anyway under your name" escape hatch.
 *
 * When AI-tracked files are detected in the commit:
 * 1. CopilotCommitService commits them with --author="GitHub Copilot (model)" and Generated-by trailer
 * 2. The committed files are removed from WorkingSetService tracking
 * 3. If non-AI files remain, IntelliJ's normal commit proceeds for those
 * 4. If all files were AI-tracked, the commit dialog closes (CANCEL return)
 */
class CopilotCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        return CopilotCheckinHandler(panel)
    }
}

private class CopilotCheckinHandler(private val panel: CheckinProjectPanel) : CheckinHandler() {

    private val log = Logger.getInstance(CopilotCheckinHandler::class.java)

    override fun beforeCheckin(): ReturnResult {
        val project = panel.project ?: return ReturnResult.COMMIT
        val ws = WorkingSetService.getInstance(project)
        if (!ws.hasChanges()) return ReturnResult.COMMIT

        val trackedPaths = ws.getChanges().map { it.absolutePath }.toSet()
        val panelFiles = panel.files
        val copilotFiles = panelFiles.filter { it.path in trackedPaths }
        if (copilotFiles.isEmpty()) return ReturnResult.COMMIT

        // Gather the FileChange objects for the AI-tracked files
        val allChanges = ws.getChanges()
        val copilotPaths = copilotFiles.map { it.path }.toSet()
        val aiChanges = allChanges.filter { it.absolutePath in copilotPaths }

        if (aiChanges.isEmpty()) return ReturnResult.COMMIT

        // Use the panel's commit message for the AI files too
        val commitMessage = panel.commitMessage.ifBlank {
            if (aiChanges.size == 1) {
                "${if (aiChanges[0].isNew) "Add" else "Update"} ${aiChanges[0].relativePath}"
            } else {
                "Copilot: update ${aiChanges.size} files"
            }
        }

        // Commit AI files with Copilot attribution
        val commitService = CopilotCommitService.getInstance(project)
        val result = commitService.commitCopilotChanges(aiChanges, commitMessage)

        if (result.success && result.committedFiles > 0) {
            // Clear committed files from tracking
            for (change in aiChanges) {
                ws.accept(change.absolutePath)
            }

            // Notify the developer
            val nonAiCount = panelFiles.size - copilotFiles.size
            val message = if (nonAiCount > 0) {
                "Committed ${result.committedFiles} file(s) as GitHub Copilot. $nonAiCount file(s) remain for your commit."
            } else {
                "Committed ${result.committedFiles} file(s) as GitHub Copilot."
            }

            try {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Copilot Chat")
                    .createNotification(message, NotificationType.INFORMATION)
                    .notify(project)
            } catch (e: Exception) {
                log.warn("Failed to show notification: ${e.message}")
            }

            // If all files were AI-tracked, cancel IntelliJ's commit (ours already ran)
            // If non-AI files remain, let IntelliJ commit those under the developer's name
            return if (nonAiCount > 0) ReturnResult.COMMIT else ReturnResult.CANCEL
        } else {
            // Attribution commit failed — graceful fallback: let IntelliJ commit everything
            // under the developer's name, but warn them
            log.warn("Copilot attribution commit failed: ${result.message}")
            try {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Copilot Chat")
                    .createNotification(
                        "Copilot attribution failed: ${result.message}. Files will be committed under your name.",
                        NotificationType.WARNING,
                    )
                    .notify(project)
            } catch (e: Exception) {
                log.warn("Failed to show notification: ${e.message}")
            }
            return ReturnResult.COMMIT
        }
    }
}
