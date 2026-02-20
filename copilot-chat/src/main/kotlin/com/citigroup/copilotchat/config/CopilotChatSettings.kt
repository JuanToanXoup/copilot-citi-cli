package com.citigroup.copilotchat.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.XCollection

@Service(Service.Level.APP)
@State(name = "CopilotChatSettings", storages = [Storage("CopilotChatSettings.xml")])
class CopilotChatSettings : PersistentStateComponent<CopilotChatSettings.State> {

    data class McpServerEntry(
        var name: String = "",
        var command: String = "",
        var args: String = "",          // space-separated args
        var env: String = "",           // KEY=VALUE pairs, one per line
        var url: String = "",           // SSE URL (if not stdio)
        var enabled: Boolean = true,
    )

    data class WorkerEntry(
        var role: String = "",
        var description: String = "",
        var model: String = "",
        var systemPrompt: String = "",
        var enabled: Boolean = true,
    )

    data class State(
        var binaryPath: String = "",
        var appsJsonPath: String = "",
        var defaultModel: String = "gpt-4.1",
        var agentModeDefault: Boolean = true,
        var agentConfigPath: String = "",
        @XCollection(elementTypes = [McpServerEntry::class])
        var mcpServers: MutableList<McpServerEntry> = mutableListOf(),
        @XCollection(elementTypes = [WorkerEntry::class])
        var workers: MutableList<WorkerEntry> = mutableListOf(),
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    var binaryPath: String
        get() = myState.binaryPath
        set(value) { myState.binaryPath = value }

    var appsJsonPath: String
        get() = myState.appsJsonPath
        set(value) { myState.appsJsonPath = value }

    var defaultModel: String
        get() = myState.defaultModel
        set(value) { myState.defaultModel = value }

    var agentModeDefault: Boolean
        get() = myState.agentModeDefault
        set(value) { myState.agentModeDefault = value }

    var agentConfigPath: String
        get() = myState.agentConfigPath
        set(value) { myState.agentConfigPath = value }

    var mcpServers: MutableList<McpServerEntry>
        get() = myState.mcpServers
        set(value) { myState.mcpServers = value }

    var workers: MutableList<WorkerEntry>
        get() = myState.workers
        set(value) { myState.workers = value }

    companion object {
        fun getInstance(): CopilotChatSettings =
            ApplicationManager.getApplication().getService(CopilotChatSettings::class.java)
    }
}
