package com.citigroup.copilotchat.agent

import kotlinx.serialization.json.JsonObject

/**
 * Marker interface for all agent events, consumed by the UI via [AgentEventBus].
 *
 * Emit contract:
 * - **Structural events** (Spawned, Completed, Done, Error, Created, Disbanded):
 *   Use `emit()` (suspending) for guaranteed delivery.
 * - **Streaming events** (Delta, ToolCall, ToolResult):
 *   Use `tryEmit()` (non-suspending) — drops are acceptable since Done carries full text.
 */
sealed interface AgentEvent

/** Events emitted by [AgentService] during lead-agent conversation. */
sealed class LeadEvent : AgentEvent {
    data class Delta(val text: String) : LeadEvent()
    data class ToolCall(val name: String, val input: JsonObject) : LeadEvent()
    data class ToolResult(val name: String, val output: String) : LeadEvent()
    data class Done(val fullText: String = "") : LeadEvent()
    data class Error(val message: String) : LeadEvent()
}

/** Events emitted by [SubagentManager] during subagent execution. */
sealed class SubagentEvent : AgentEvent {
    data class Spawned(val agentId: String, val agentType: String, val description: String, val prompt: String = "") : SubagentEvent()
    data class Delta(val agentId: String, val text: String) : SubagentEvent()
    data class ToolCall(val agentId: String, val toolName: String) : SubagentEvent()
    data class Completed(val agentId: String, val result: String, val status: String, val durationMs: Long = 0) : SubagentEvent()
    data class WorktreeChangesReady(val agentId: String, val changes: List<WorktreeFileChange>) : SubagentEvent()
    data class HandoffsAvailable(val agentId: String, val completedAgentType: String, val handoffs: List<HandoffDefinition>) : SubagentEvent()
}

/** Events emitted by [TeamService] during team collaboration. */
sealed class TeamEvent : AgentEvent {
    data class Created(val teamName: String) : TeamEvent()
    data class MemberJoined(val name: String, val agentType: String) : TeamEvent()
    data class MemberIdle(val name: String) : TeamEvent()
    data class MemberResumed(val name: String) : TeamEvent()
    data class MailboxMessage(val from: String, val to: String, val summary: String) : TeamEvent()
    data class Disbanded(val teamName: String) : TeamEvent()
}
