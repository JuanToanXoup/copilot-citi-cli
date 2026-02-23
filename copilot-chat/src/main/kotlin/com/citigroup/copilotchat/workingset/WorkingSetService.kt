package com.citigroup.copilotchat.workingset

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.InvokeAfterUpdateMode
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class WorkingSetService(private val project: Project) {

    private val log = Logger.getInstance(WorkingSetService::class.java)

    private val _events = MutableSharedFlow<WorkingSetEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WorkingSetEvent> = _events

    private val changes = ConcurrentHashMap<String, FileChange>()
    private val pendingSnapshots = ConcurrentHashMap<String, PendingSnapshot>()

    private data class PendingSnapshot(
        val toolName: String,
        val originalContent: String?,
        val existed: Boolean,
    )

    companion object {
        private const val CHANGELIST_NAME = "Copilot Changes"

        fun getInstance(project: Project): WorkingSetService =
            project.getService(WorkingSetService::class.java)
    }

    fun captureBeforeState(toolName: String, filePath: String) {
        val absPath = resolveAbsolutePath(filePath)
        val file = File(absPath)
        val content = if (file.exists()) file.readText() else null
        log.info("Working set: captureBeforeState tool=$toolName path=$absPath exists=${file.exists()}")
        pendingSnapshots[absPath] = PendingSnapshot(
            toolName = toolName,
            originalContent = content,
            existed = file.exists(),
        )
    }

    fun captureAfterState(filePath: String) {
        val absPath = resolveAbsolutePath(filePath)
        val snapshot = pendingSnapshots.remove(absPath)
        if (snapshot == null) {
            log.warn("Working set: captureAfterState — no pending snapshot for $absPath")
            return
        }
        val file = File(absPath)
        if (!file.exists()) {
            log.warn("Working set: captureAfterState — file does not exist after tool: $absPath")
            return
        }

        val currentContent = file.readText()

        // Skip if content unchanged
        if (snapshot.originalContent == currentContent) return

        val projectRoot = project.basePath ?: ""
        val relativePath = if (absPath.startsWith(projectRoot)) {
            absPath.removePrefix(projectRoot).removePrefix("/")
        } else {
            absPath
        }

        // Preserve the original snapshot from the first capture if file was already tracked
        val existing = changes[absPath]
        val change = FileChange(
            absolutePath = absPath,
            relativePath = relativePath,
            originalContent = existing?.originalContent ?: snapshot.originalContent,
            currentContent = currentContent,
            isNew = existing?.isNew ?: !snapshot.existed,
            toolName = snapshot.toolName,
        )
        changes[absPath] = change
        _events.tryEmit(WorkingSetEvent.FileChanged(change))
        log.info("Working set: tracked ${if (change.isNew) "new" else "modified"} file $relativePath")

        // Refresh VFS and move to "Copilot Changes" changelist
        moveToAgentChangelist(absPath)
    }

    fun revert(absolutePath: String) {
        val change = changes.remove(absolutePath) ?: return
        val file = File(absolutePath)
        if (change.isNew) {
            if (file.exists()) file.delete()
            log.info("Working set: deleted new file ${change.relativePath}")
        } else if (change.originalContent != null) {
            file.writeText(change.originalContent)
            log.info("Working set: reverted ${change.relativePath}")
        }
        // Refresh VFS so the file tree reflects the revert
        LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath)
        _events.tryEmit(WorkingSetEvent.FileReverted(change.relativePath))
    }

    fun revertAll() {
        val paths = changes.keys.toList()
        for (path in paths) {
            revert(path)
        }
    }

    fun accept(absolutePath: String) {
        val removed = changes.remove(absolutePath)
        if (removed != null) {
            log.info("Working set: accepted ${removed.relativePath}")
            if (changes.isEmpty()) {
                _events.tryEmit(WorkingSetEvent.Cleared)
            }
        }
    }

    fun acceptAll() {
        changes.clear()
        _events.tryEmit(WorkingSetEvent.Cleared)
        log.info("Working set: accepted all changes")
    }

    fun clear() {
        changes.clear()
        pendingSnapshots.clear()
        _events.tryEmit(WorkingSetEvent.Cleared)
        log.info("Working set: cleared")
    }

    fun getChanges(): List<FileChange> =
        changes.values.sortedBy { it.relativePath }

    fun hasChanges(): Boolean = changes.isNotEmpty()

    /**
     * Refresh VFS for the file, then move it into the "Copilot Changes" changelist
     * so agent-made modifications are visually separated in the Commit panel.
     */
    private fun moveToAgentChangelist(absPath: String) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(absPath) ?: return

        try {
            val clm = ChangeListManager.getInstance(project)

            // Create the changelist if it doesn't exist yet
            val copilotList = clm.findChangeList(CHANGELIST_NAME)
                ?: clm.addChangeList(CHANGELIST_NAME, "Changes made by the Copilot agent")

            // Schedule move after VCS detects the file change
            clm.invokeAfterUpdate(
                {
                    val change = clm.getChange(vf)
                    if (change != null) {
                        clm.moveChangesTo(copilotList, change)
                        log.info("Working set: moved $absPath to '$CHANGELIST_NAME' changelist")
                    } else {
                        log.info("Working set: no VCS change detected yet for $absPath")
                    }
                },
                InvokeAfterUpdateMode.SILENT,
                "Track Copilot change",
                null
            )
        } catch (e: Exception) {
            log.warn("Working set: failed to move to changelist: ${e.message}")
        }
    }

    private fun resolveAbsolutePath(filePath: String): String {
        val file = File(filePath)
        return if (file.isAbsolute) {
            file.absolutePath
        } else {
            File(project.basePath ?: "/tmp", filePath).absolutePath
        }
    }
}
