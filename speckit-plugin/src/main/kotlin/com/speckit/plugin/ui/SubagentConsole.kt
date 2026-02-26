package com.speckit.plugin.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager

@Service(Service.Level.PROJECT)
class SubagentConsole(private val project: Project) : Disposable {

    @Volatile
    private var consoleView: ConsoleView? = null

    fun getOrCreateConsole(): ConsoleView {
        consoleView?.let { return it }
        synchronized(this) {
            consoleView?.let { return it }
            val console = TextConsoleBuilderFactory.getInstance()
                .createBuilder(project)
                .console
            Disposer.register(this, console)
            consoleView = console
            return console
        }
    }

    fun logStart(agentName: String) {
        printLn("\n━━━ $agentName started ━━━", ConsoleViewContentType.SYSTEM_OUTPUT)
        showToolWindow()
    }

    fun logStep(agentName: String, stepTitle: String) {
        printLn("[$agentName] $stepTitle", ConsoleViewContentType.NORMAL_OUTPUT)
    }

    fun logReply(agentName: String, text: String) {
        // Reply text streams in chunks — append without newline
        print("$text", ConsoleViewContentType.USER_INPUT)
    }

    fun logEnd(agentName: String, durationMs: Long) {
        val seconds = durationMs / 1000.0
        printLn("\n━━━ $agentName completed (${String.format("%.1f", seconds)}s) ━━━\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    fun logError(agentName: String, error: String) {
        printLn("[$agentName] ERROR: $error", ConsoleViewContentType.ERROR_OUTPUT)
    }

    private fun print(text: String, type: ConsoleViewContentType) {
        val console = consoleView ?: return
        invokeLater {
            if (!project.isDisposed) {
                console.print(text, type)
            }
        }
    }

    private fun printLn(text: String, type: ConsoleViewContentType) {
        print("$text\n", type)
    }

    private fun showToolWindow() {
        invokeLater {
            if (project.isDisposed) return@invokeLater
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Speckit")
            toolWindow?.show()
        }
    }

    override fun dispose() {
        consoleView = null
    }
}
