package com.speckit.plugin.persistence

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.speckit.plugin.ui.ChatRun
import com.speckit.plugin.ui.ChatRunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class SessionPersistenceManager(private val project: Project) : Disposable {

    private val log = Logger.getInstance(SessionPersistenceManager::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)

    private val projectDb: SessionDatabaseService
        get() = project.service()

    private val userDb: UserDatabaseService
        get() = ApplicationManager.getApplication().service()

    fun initialize() {
        try {
            projectDb.initialize()
        } catch (e: Exception) {
            log.warn("Failed to initialize project DB", e)
        }
        try {
            userDb.initialize()
        } catch (e: Exception) {
            log.warn("Failed to initialize user DB", e)
        }
    }

    /**
     * Persist a newly created run. Called when a ChatRun is created with
     * Copilot's session.id as the primary key.
     */
    fun createRun(sessionId: String, agent: String, prompt: String, branch: String, startTimeMs: Long) {
        scope.launch {
            try {
                projectDb.insertSession(
                    sessionId = sessionId,
                    agent = agent,
                    prompt = prompt,
                    branch = branch,
                    status = ChatRunStatus.RUNNING.name,
                    startTimeMs = startTimeMs,
                    projectName = project.name,
                    projectPath = project.basePath
                )
            } catch (e: Exception) {
                log.warn("Failed to persist session creation: $sessionId", e)
            }
        }
    }

    /**
     * Update the conversation ID when the server assigns one.
     */
    fun attachConversationId(sessionId: String, conversationId: String) {
        scope.launch {
            try {
                projectDb.updateConversationId(sessionId, conversationId)
            } catch (e: Exception) {
                log.warn("Failed to update conversationId: $sessionId", e)
            }
        }
    }

    /**
     * Mark a session as completed and sync to user DB.
     */
    fun completeRun(sessionId: String, durationMs: Long) {
        scope.launch {
            try {
                projectDb.updateStatus(sessionId, ChatRunStatus.COMPLETED.name, durationMs)
                captureAndSync(sessionId)
            } catch (e: Exception) {
                log.warn("Failed to complete session: $sessionId", e)
            }
        }
    }

    /**
     * Mark a session as failed and sync to user DB.
     */
    fun failRun(sessionId: String, durationMs: Long, errorMessage: String?) {
        scope.launch {
            try {
                projectDb.updateStatus(sessionId, ChatRunStatus.FAILED.name, durationMs, errorMessage)
                captureAndSync(sessionId)
            } catch (e: Exception) {
                log.warn("Failed to record session failure: $sessionId", e)
            }
        }
    }

    /**
     * Mark a session as cancelled and sync to user DB.
     */
    fun cancelRun(sessionId: String, durationMs: Long) {
        scope.launch {
            try {
                projectDb.updateStatus(sessionId, ChatRunStatus.CANCELLED.name, durationMs)
                captureAndSync(sessionId)
            } catch (e: Exception) {
                log.warn("Failed to record session cancellation: $sessionId", e)
            }
        }
    }

    /**
     * Capture messages from CopilotAgentSessionManager and write to DB.
     * Called on terminal status (complete/fail/cancel).
     */
    @Suppress("unused")
    fun captureMessages(sessionId: String) {
        scope.launch {
            try {
                captureMessagesFromCopilot(sessionId)
            } catch (e: Exception) {
                log.warn("Failed to capture messages: $sessionId", e)
            }
        }
    }

    private suspend fun captureMessagesFromCopilot(sessionId: String) {
        try {
            val sessionManager = project.service<com.github.copilot.agent.session.CopilotAgentSessionManager>()
            val copilotSession = sessionManager.getSession(sessionId) ?: return

            val messages = copilotSession.messages
            for ((index, msg) in messages.withIndex()) {
                // CopilotAgentMessage uses getRole()/getContent() — access via
                // the toString() of the role enum and the content string.
                val role = try { msg.javaClass.getMethod("getRole").invoke(msg)?.toString() ?: "UNKNOWN" } catch (_: Exception) { "UNKNOWN" }
                val content = try { msg.javaClass.getMethod("getContent").invoke(msg)?.toString() ?: "" } catch (_: Exception) { "" }
                projectDb.insertMessage(
                    sessionId = sessionId,
                    role = role,
                    content = content,
                    sequenceNum = index,
                    timestampMs = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            log.warn("Could not capture Copilot session messages: $sessionId", e)
        }
    }

    /**
     * Capture messages from Copilot, then sync session + messages + tool calls to user DB.
     */
    private suspend fun captureAndSync(sessionId: String) {
        captureMessagesFromCopilot(sessionId)
        syncToUserDb(sessionId)
    }

    private fun syncToUserDb(sessionId: String) {
        try {
            val session = projectDb.getSession(sessionId) ?: return
            val messages = projectDb.getMessages(sessionId)
            val toolCalls = projectDb.getToolCalls(sessionId)
            userDb.syncSession(session, messages, toolCalls, project.name)
        } catch (e: Exception) {
            log.warn("Failed to sync session to user DB: $sessionId", e)
        }
    }

    /**
     * Load recent runs from the project DB. Returns on the calling thread.
     */
    fun loadRecentRuns(limit: Int = 100): List<ChatRun> {
        return try {
            projectDb.loadRecentSessions(limit)
        } catch (e: Exception) {
            log.warn("Failed to load recent sessions", e)
            emptyList()
        }
    }

    override fun dispose() {}
}
