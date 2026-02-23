package com.citigroup.copilotchat.workingset

import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory

/**
 * Warns the developer if they're about to commit files that were modified by
 * Copilot via IntelliJ's normal commit flow. Suggests using the "Commit" button
 * in the Changes tab instead, which creates a dedicated Copilot-authored commit.
 */
class CopilotCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        return CopilotCheckinHandler(panel)
    }
}

private class CopilotCheckinHandler(private val panel: CheckinProjectPanel) : CheckinHandler() {

    override fun beforeCheckin(): ReturnResult {
        val project = panel.project ?: return ReturnResult.COMMIT
        val ws = WorkingSetService.getInstance(project)
        if (!ws.hasChanges()) return ReturnResult.COMMIT

        val trackedPaths = ws.getChanges().map { it.absolutePath }.toSet()
        val copilotFiles = panel.files.filter { it.path in trackedPaths }
        if (copilotFiles.isEmpty()) return ReturnResult.COMMIT

        // Warn: these files were changed by Copilot — committing under your name
        val fileList = copilotFiles.joinToString("\n") { "  ${it.name}" }
        val choice = javax.swing.JOptionPane.showOptionDialog(
            panel.component,
            "These ${copilotFiles.size} file(s) were modified by Copilot:\n$fileList\n\n" +
                "Committing now will attribute them to you.\n" +
                "Use the Commit button in the Changes tab to create a Copilot-authored commit instead.",
            "Copilot-Changed Files",
            javax.swing.JOptionPane.YES_NO_OPTION,
            javax.swing.JOptionPane.WARNING_MESSAGE,
            null,
            arrayOf("Commit Anyway", "Cancel"),
            "Cancel"
        )

        return if (choice == 0) ReturnResult.COMMIT else ReturnResult.CANCEL
    }
}
