package com.speckit.plugin.service

import com.github.copilot.api.CopilotChatService
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.speckit.plugin.model.ChatRun
import com.speckit.plugin.model.ChatRunStatus
import com.speckit.plugin.persistence.SessionPersistenceManager
import com.speckit.plugin.ui.SessionPanel

class ChatRunLauncher(
    private val project: Project,
    private val sessionPanel: SessionPanel,
    private val persistenceManager: SessionPersistenceManager?
) {
    /**
     * Launch a new Copilot agent session with full lifecycle tracking.
     *
     * @param prompt        Full prompt string sent to the agent
     * @param agent         Agent slug for display (e.g. "discovery", "implement")
     * @param promptSummary Short summary shown in the sessions table
     * @param branch        Git branch name; defaults to current branch
     * @param onSessionReceived Optional callback when sessionId is assigned (EDT)
     * @param onDone        Optional callback on successful completion (EDT)
     * @param onFail        Optional callback on error or cancel (EDT). Receives error message or null for cancel.
     * @return The ChatRun object for tracking
     */
    fun launch(
        prompt: String,
        agent: String,
        promptSummary: String,
        branch: String = sessionPanel.currentGitBranch(),
        onSessionReceived: ((String) -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        onFail: ((String?) -> Unit)? = null
    ): ChatRun {
        val run = ChatRun(agent, promptSummary, branch)
        sessionPanel.registerRun(run)

        val chatService = project.getService(CopilotChatService::class.java)
            ?: return run
        val dataContext = SimpleDataContext.getProjectContext(project)

        chatService.query(dataContext) {
            withInput(prompt)
            withAgentMode()
            withNewSession()
            withSessionIdReceiver { sessionId ->
                invokeLater {
                    run.sessionId = sessionId
                    sessionPanel.notifyRunChanged()
                    onSessionReceived?.invoke(sessionId)
                }
                persistenceManager?.createRun(sessionId, run.agent, run.prompt, run.branch, run.startTimeMillis)
            }

            onComplete {
                invokeLater {
                    run.status = ChatRunStatus.COMPLETED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    sessionPanel.notifyRunChanged()
                    onDone?.invoke()
                }
                run.sessionId?.let { persistenceManager?.completeRun(it, System.currentTimeMillis() - run.startTimeMillis) }
            }

            onError { message, _, _, _, _ ->
                invokeLater {
                    run.status = ChatRunStatus.FAILED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    run.errorMessage = message
                    sessionPanel.notifyRunChanged()
                    onFail?.invoke(message)
                }
                run.sessionId?.let { persistenceManager?.failRun(it, System.currentTimeMillis() - run.startTimeMillis, message) }
            }

            onCancel {
                invokeLater {
                    run.status = ChatRunStatus.CANCELLED
                    run.durationMs = System.currentTimeMillis() - run.startTimeMillis
                    sessionPanel.notifyRunChanged()
                    onFail?.invoke(null)
                }
                run.sessionId?.let { persistenceManager?.cancelRun(it, System.currentTimeMillis() - run.startTimeMillis) }
            }
        }
        return run
    }
}
