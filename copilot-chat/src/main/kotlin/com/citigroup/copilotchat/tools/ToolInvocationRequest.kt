package com.citigroup.copilotchat.tools

import kotlinx.serialization.json.JsonObject

/**
 * Mirrors the reference Copilot plugin's ToolInvocationRequest.
 *
 * Bundles the tool name, input, and conversation identifiers so that
 * tools can resolve Project via [ToolInvocationManager.findProjectForInvocation].
 */
data class ToolInvocationRequest(
    val name: String,
    val input: JsonObject,
    val conversationId: String? = null,
    val turnId: String? = null,
    val toolCallId: String? = null,
    val roundId: Int = 0,
    val workspaceRoot: String = "/tmp",
) {
    val identifier get() = ToolInvocationIdentifier(conversationId, turnId, toolCallId, roundId)
}

data class ToolInvocationIdentifier(
    val conversationId: String?,
    val turnId: String?,
    val toolCallId: String?,
    val roundId: Int = 0,
)
