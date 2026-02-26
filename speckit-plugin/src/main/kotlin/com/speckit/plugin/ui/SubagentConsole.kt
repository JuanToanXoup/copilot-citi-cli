package com.speckit.plugin.ui

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

@Service(Service.Level.PROJECT)
class SubagentConsole(private val project: Project) : Disposable {

    private val tracker get() = project.service<AgentRunTracker>()

    @Volatile
    var panel: AgentRunPanel? = null

    fun logStart(agentName: String): AgentRun {
        val run = tracker.startRun(agentName)
        tracker.appendOutput(run, "━━━ $agentName started ━━━\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        showToolWindow()
        invokeLater { panel?.selectRun(0) }
        panel?.refreshDetailIfSelected(run)
        return run
    }

    fun logStep(run: AgentRun, agentName: String, stepTitle: String) {
        tracker.addToolCall(run, stepTitle)
        tracker.appendOutput(run, "[$agentName] $stepTitle\n", ConsoleViewContentType.NORMAL_OUTPUT)
        panel?.refreshDetailIfSelected(run)
    }

    fun logReply(run: AgentRun, text: String) {
        tracker.appendOutput(run, text, ConsoleViewContentType.USER_INPUT)
        panel?.refreshDetailIfSelected(run)
    }

    fun logEnd(run: AgentRun, agentName: String, durationMs: Long) {
        val seconds = durationMs / 1000.0
        tracker.appendOutput(
            run,
            "\n━━━ $agentName completed (${String.format("%.1f", seconds)}s) ━━━\n\n",
            ConsoleViewContentType.SYSTEM_OUTPUT
        )
        tracker.completeRun(run, durationMs)
        panel?.refreshDetailIfSelected(run)
    }

    fun logError(run: AgentRun, agentName: String, error: String) {
        tracker.appendOutput(run, "[$agentName] ERROR: $error\n", ConsoleViewContentType.ERROR_OUTPUT)
        panel?.refreshDetailIfSelected(run)
    }

    fun failRun(run: AgentRun, agentName: String, durationMs: Long) {
        val seconds = durationMs / 1000.0
        tracker.appendOutput(
            run,
            "\n━━━ $agentName failed (${String.format("%.1f", seconds)}s) ━━━\n\n",
            ConsoleViewContentType.ERROR_OUTPUT
        )
        tracker.failRun(run, durationMs)
        panel?.refreshDetailIfSelected(run)
    }

    private fun showToolWindow() {
        invokeLater {
            if (project.isDisposed) return@invokeLater
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Speckit")
            toolWindow?.show()
        }
    }

    override fun dispose() {
        panel = null
    }
}
