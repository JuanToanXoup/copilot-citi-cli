package com.citigroup.copilotchat.tools

import kotlinx.serialization.json.JsonObject

/**
 * Registry that aggregates all built-in tool groups.
 * Each domain (file ops, search, execution, etc.) lives in its own ToolGroup.
 */
object BuiltInTools {

    private val groups: List<ToolGroup> = listOf(
        FileTools,
        SearchTools,
        ExecutionTools,
        InfoTools,
        WebTools,
        MemoryTools,
    )

    // Agent stub executors — actual execution is handled by AgentService.
    private val agentStubs: Map<String, (JsonObject, String) -> String> = mapOf(
        "delegate_task" to { _, _ -> "Error: delegate_task is only available in the Agent tab" },
        "create_team" to { _, _ -> "Error: create_team is only available in the Agent tab" },
        "send_message" to { _, _ -> "Error: send_message is only available in the Agent tab" },
        "delete_team" to { _, _ -> "Error: delete_team is only available in the Agent tab" },
    )

    private val allExecutors: Map<String, (JsonObject, String) -> String> by lazy {
        val map = mutableMapOf<String, (JsonObject, String) -> String>()
        for (group in groups) map.putAll(group.executors)
        map.putAll(agentStubs)
        map
    }

    val toolNames: Set<String> get() = allExecutors.keys

    /** Tool schemas in the format expected by conversation/registerTools. */
    val schemas: List<String> by lazy {
        groups.flatMap { it.schemas } + agentSchemas
    }

    fun execute(name: String, input: JsonObject, workspaceRoot: String): String {
        val executor = allExecutors[name] ?: return "Error: Unknown built-in tool: $name"
        return try {
            executor(input, workspaceRoot)
        } catch (e: Exception) {
            "Error executing $name: ${e.message}"
        }
    }

    private val agentSchemas: List<String> = listOf(
        """{"name":"delegate_task","description":"Delegate a task to a specialized sub-agent for autonomous execution. Available agent types: explore (fast codebase search), plan (architecture and design), bash (shell commands), general-purpose (all tools).","inputSchema":{"type":"object","properties":{"description":{"type":"string","description":"A short (3-5 word) description of the task"},"prompt":{"type":"string","description":"The detailed task for the agent to perform"},"subagent_type":{"type":"string","description":"The type of agent: explore, plan, bash, or general-purpose"},"model":{"type":"string","description":"Optional model override"},"max_turns":{"type":"integer","description":"Maximum number of agentic turns before stopping"}},"required":["description","prompt","subagent_type"]}}""",
        """{"name":"create_team","description":"Create a new agent team with persistent teammate agents that communicate via mailboxes.","inputSchema":{"type":"object","properties":{"name":{"type":"string","description":"Team name"},"description":{"type":"string","description":"What this team is for"},"members":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string","description":"Teammate name"},"agentType":{"type":"string","description":"Agent type (e.g., explore, general-purpose)"},"initialPrompt":{"type":"string","description":"Initial task for this teammate"},"model":{"type":"string","description":"Optional model override"}},"required":["name","agentType","initialPrompt"]},"description":"List of teammates to spawn"}},"required":["name","members"]}}""",
        """{"name":"send_message","description":"Send a message to a teammate's mailbox.","inputSchema":{"type":"object","properties":{"to":{"type":"string","description":"Recipient teammate name"},"text":{"type":"string","description":"Message content"},"summary":{"type":"string","description":"Optional brief summary"}},"required":["to","text"]}}""",
        """{"name":"delete_team","description":"Disband the active team and stop all teammates.","inputSchema":{"type":"object","properties":{"name":{"type":"string","description":"Team name to delete"}},"required":["name"]}}""",
    )
}
