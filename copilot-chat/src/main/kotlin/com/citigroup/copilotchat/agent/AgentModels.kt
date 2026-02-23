package com.citigroup.copilotchat.agent

import kotlinx.serialization.json.JsonObject

/** How the agent definition was sourced. */
enum class AgentSource { BUILT_IN, CUSTOM_PROJECT, CUSTOM_USER }

/** Model selection for an agent. */
enum class AgentModel {
    INHERIT,          // resolved by caller: lead → claude-sonnet-4, subagent → gpt-4.1
    GPT_4_1,          // "gpt-4.1" (free tier)
    CLAUDE_SONNET_4;  // "claude-sonnet-4" (premium tier)

    /**
     * Resolve to an actual model ID string accepted by the Copilot language server.
     * Falls back to [parentModel] if INHERIT.
     *
     * Lead callers pass "claude-sonnet-4" so INHERIT resolves to premium.
     * Subagent callers pass "gpt-4.1" so INHERIT resolves to free tier.
     *
     * These IDs must match the server's model catalog (e.g. from copilot/models).
     * Invalid IDs cause the server to create the conversation but produce no output.
     */
    fun resolveModelId(parentModel: String): String = when (this) {
        INHERIT -> parentModel
        GPT_4_1 -> "gpt-4.1"
        CLAUDE_SONNET_4 -> "claude-sonnet-4"
    }
}

/** Per-agent MCP server configuration (from .agent.md frontmatter). */
data class McpServerConfig(
    val type: String = "local",      // "local" or "sse"
    val command: String = "",
    val args: List<String> = emptyList(),
    val tools: List<String>? = null, // null = all
    val env: Map<String, String> = emptyMap(),
    val url: String = "",            // for SSE type
)

/** Definition of an agent type (built-in or loaded from .agent.md file). */
data class AgentDefinition(
    val agentType: String,
    val whenToUse: String,
    val tools: List<String>? = null,           // null = all tools
    val disallowedTools: List<String> = emptyList(),
    val source: AgentSource = AgentSource.BUILT_IN,
    val model: AgentModel = AgentModel.INHERIT,
    val systemPromptTemplate: String = "",     // supports {{AGENT_LIST}} placeholder for supervisors
    val forkContext: Boolean = false,
    val background: Boolean = false,
    val maxTurns: Int = 30,
    val disableModelInvocation: Boolean = false,   // agent requires manual selection
    val handoffs: List<String> = emptyList(),       // agent-to-agent transitions
    val mcpServers: Map<String, McpServerConfig> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(), // arbitrary key-value pairs
    val filePath: String? = null,                   // path to .agent.md (null for built-in)
    val subagents: List<String>? = null,            // null = worker. list = supervisor with scoped pool
    val target: String? = null,                     // "vscode", "github-copilot", or null (both)
)

/** Parsed input for the delegate_task tool call. */
data class DelegateTaskInput(
    val description: String,
    val prompt: String,
    val subagentType: String,
    val model: String? = null,
    val maxTurns: Int? = null,
)

/** Result returned from a subagent execution. */
data class SubagentResult(
    val agentType: String,
    val agentId: String,
    val result: String,
    val status: String,   // "success" or "error"
    val turnsUsed: Int,
)

/** Team configuration persisted to config.json. */
data class TeamConfig(
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val leadAgentId: String = "",
    val members: MutableList<TeamMember> = mutableListOf(),
)

/** A member of a team. */
data class TeamMember(
    val agentId: String,
    val name: String,
    val agentType: String,
    val model: String = "",
    val joinedAt: Long = System.currentTimeMillis(),
    val cwd: String = "",
)

/** A message in an agent's mailbox. */
data class MailboxMessage(
    val from: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val color: String? = null,
    val summary: String? = null,
    var read: Boolean = false,
)

/** Events emitted by the Agent tab, consumed by AgentPanel UI. */
sealed class AgentEvent {

    // Lead agent events
    data class LeadDelta(val text: String) : AgentEvent()
    data class LeadToolCall(val name: String, val input: JsonObject) : AgentEvent()
    data class LeadToolResult(val name: String, val output: String) : AgentEvent()
    data class LeadDone(val fullText: String = "") : AgentEvent()
    data class LeadError(val message: String) : AgentEvent()

    // Subagent events
    data class SubagentSpawned(val agentId: String, val agentType: String, val description: String, val prompt: String = "") : AgentEvent()
    data class SubagentDelta(val agentId: String, val text: String) : AgentEvent()
    data class SubagentToolCall(val agentId: String, val toolName: String) : AgentEvent()
    data class SubagentCompleted(val agentId: String, val result: String, val status: String) : AgentEvent()
    data class WorktreeChangesReady(val agentId: String, val changes: List<WorktreeFileChange>) : AgentEvent()

    // Team events
    data class TeamCreated(val teamName: String) : AgentEvent()
    data class TeammateJoined(val name: String, val agentType: String) : AgentEvent()
    data class TeammateIdle(val name: String) : AgentEvent()
    data class TeammateResumed(val name: String) : AgentEvent()
    data class MailboxMessageEvent(val from: String, val to: String, val summary: String) : AgentEvent()
    data class TeamDisbanded(val teamName: String) : AgentEvent()
}
