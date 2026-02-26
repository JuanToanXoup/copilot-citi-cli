package com.speckit.plugin.ui

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.components.Service

enum class AgentRunStatus {
    RUNNING, COMPLETED, FAILED
}

class AgentRun(
    val agentName: String,
    val startTimeMillis: Long = System.currentTimeMillis()
) {
    @Volatile var status: AgentRunStatus = AgentRunStatus.RUNNING
    @Volatile var durationMs: Long = 0
    val toolCalls: MutableList<String> = mutableListOf()
    val output: MutableList<Pair<String, ConsoleViewContentType>> = mutableListOf()
}

@Service(Service.Level.PROJECT)
class AgentRunTracker {

    private val _runs = mutableListOf<AgentRun>()
    val runs: List<AgentRun> get() = synchronized(_runs) { _runs.toList() }

    private val listeners = mutableListOf<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun startRun(agentName: String): AgentRun {
        val run = AgentRun(agentName = agentName)
        synchronized(_runs) { _runs.add(0, run) }
        fireChanged()
        return run
    }

    fun completeRun(run: AgentRun, durationMs: Long) {
        run.status = AgentRunStatus.COMPLETED
        run.durationMs = durationMs
        fireChanged()
    }

    fun failRun(run: AgentRun, durationMs: Long) {
        run.status = AgentRunStatus.FAILED
        run.durationMs = durationMs
        fireChanged()
    }

    fun addToolCall(run: AgentRun, toolName: String) {
        synchronized(run.toolCalls) { run.toolCalls.add(toolName) }
        fireChanged()
    }

    fun appendOutput(run: AgentRun, text: String, type: ConsoleViewContentType) {
        synchronized(run.output) { run.output.add(text to type) }
    }

    private fun fireChanged() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it() }
    }
}
