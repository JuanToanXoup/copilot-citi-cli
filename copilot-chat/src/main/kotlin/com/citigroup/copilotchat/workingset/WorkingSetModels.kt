package com.citigroup.copilotchat.workingset

data class FileChange(
    val absolutePath: String,
    val relativePath: String,
    val originalContent: String?,
    val currentContent: String,
    val isNew: Boolean,
    val toolName: String,
)

sealed class WorkingSetEvent {
    data class FileChanged(val change: FileChange) : WorkingSetEvent()
    data class FileReverted(val relativePath: String) : WorkingSetEvent()
    object Cleared : WorkingSetEvent()
}
