package com.citigroup.copilotchat.agent

import kotlinx.coroutines.flow.SharedFlow

/**
 * Decouples agent event emission from [AgentService] internals.
 *
 * [TeamService] and other components that need to emit [AgentEvent]s
 * depend on this interface instead of reaching into AgentService._events.
 *
 * ## emit() vs tryEmit() usage rules
 *
 * **Use [emit]** (suspending) when in a coroutine context where suspension is safe:
 * - Top-level service error handlers (e.g. `AgentService.sendMessage` catch block)
 * - Final result emission (e.g. `SubagentManager.awaitAll`)
 * - Team event emission via `scope.launch { emit(...) }`
 *
 * **Use [tryEmit]** (non-blocking, drops if buffer full) when suspension would
 * cause deadlocks or block shared thread pools:
 * - LSP progress callbacks on `Dispatchers.Default` (e.g. `handleLeadProgress`)
 * - High-frequency streaming deltas
 * - `WorkerSession.onEvent` callbacks (non-suspend lambda)
 */
interface AgentEventBus {
    val events: SharedFlow<AgentEvent>
    suspend fun emit(event: AgentEvent)
    /** Non-suspending emit for use in non-coroutine callbacks. Drops if buffer is full. */
    fun tryEmit(event: AgentEvent): Boolean
}
