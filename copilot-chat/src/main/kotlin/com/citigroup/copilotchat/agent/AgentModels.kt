package com.citigroup.copilotchat.agent


/** How the agent definition was sourced. */
enum class AgentSource { BUILT_IN, CUSTOM_PROJECT, CUSTOM_USER }

/** Model selection for an agent. */
enum class AgentModel {
    INHERIT,          // resolved by caller: lead → gpt-4.1, subagent → gpt-4.1
    GPT_4_1,          // "gpt-4.1" (free tier)
    CLAUDE_SONNET_4;  // "claude-sonnet-4" (premium tier)

    /**
     * Resolve to an actual model ID string accepted by the Copilot language server.
     * Falls back to [parentModel] if INHERIT.
     *
     * Lead callers pass "gpt-4.1" so INHERIT resolves to gpt-4.1.
     * Subagent callers pass "gpt-4.1" so INHERIT resolves to gpt-4.1.
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
    val tools: List<String> = emptyList(),      // explicit tool allowlist (empty = unrestricted)
    val source: AgentSource = AgentSource.BUILT_IN,
    val model: AgentModel = AgentModel.INHERIT,
    val systemPromptTemplate: String = "",     // supports {{AGENT_LIST}} placeholder for supervisors
    val forkContext: Boolean = false,
    val background: Boolean = false,
    val maxTurns: Int = 30,
    val disableModelInvocation: Boolean = false,   // agent requires manual selection
    val handoffs: List<HandoffDefinition> = emptyList(),  // agent-to-agent transitions
    val mcpServers: Map<String, McpServerConfig> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(), // arbitrary key-value pairs
    val filePath: String? = null,                   // path to .agent.md (null for built-in)
    val subagents: List<String>? = null,            // null = worker. list = supervisor with scoped pool
    val target: String? = null,                     // "vscode", "github-copilot", or null (both)
) {
    /** True when no tool restrictions are defined — agent can use all registered tools. */
    val hasUnrestrictedTools: Boolean get() = tools.isEmpty()

    /**
     * Check whether [toolName] is allowed for this agent.
     * Handles the "ide" shorthand: if "ide" is in the tools list,
     * all "ide_*" tool names are allowed.
     */
    fun isToolAllowed(toolName: String): Boolean =
        hasUnrestrictedTools || toolName in tools ||
            (toolName.startsWith("ide_") && "ide" in tools)
}

/** A structured handoff: when this agent completes, offer or auto-trigger another agent. */
data class HandoffDefinition(
    val label: String,           // display text (e.g., "Build Technical Plan")
    val agent: String,           // target agentType (e.g., "speckit.plan")
    val prompt: String = "",     // initial prompt for the target agent
    val send: Boolean = false,   // true = auto-trigger on completion; false = UI button
)

/** Parsed input for the delegate_task tool call. */
data class DelegateTaskInput(
    val description: String,
    val prompt: String,
    val subagentType: String,
    val model: String? = null,
    val maxTurns: Int? = null,
    val waitForResult: Boolean = false,
    val timeoutSeconds: Int = 300,
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

