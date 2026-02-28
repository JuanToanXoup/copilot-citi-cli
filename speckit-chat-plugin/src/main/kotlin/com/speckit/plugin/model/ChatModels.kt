package com.speckit.plugin.model

enum class ChatRunStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

class ChatRun(
    val agent: String,
    val prompt: String,
    val branch: String,
    val startTimeMillis: Long = System.currentTimeMillis()
) {
    @Volatile var status: ChatRunStatus = ChatRunStatus.RUNNING
    @Volatile var durationMs: Long = 0
    @Volatile var sessionId: String? = null
    @Volatile var errorMessage: String? = null
}
