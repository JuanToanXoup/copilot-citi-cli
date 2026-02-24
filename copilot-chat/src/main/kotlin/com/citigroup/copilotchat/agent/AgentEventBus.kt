package com.citigroup.copilotchat.agent

import kotlinx.coroutines.flow.SharedFlow

/**
 * Decouples agent event emission from [AgentService] internals.
 *
 * [TeamService] and other components that need to emit [AgentEvent]s
 * depend on this interface instead of reaching into AgentService._events.
 */
interface AgentEventBus {
    val events: SharedFlow<AgentEvent>
    suspend fun emit(event: AgentEvent)
}
