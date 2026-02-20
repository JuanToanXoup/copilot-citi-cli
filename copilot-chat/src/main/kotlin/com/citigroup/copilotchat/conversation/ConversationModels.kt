package com.citigroup.copilotchat.conversation

import kotlinx.serialization.json.JsonObject

/** A single message in the conversation. */
data class ChatMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCallInfo> = emptyList(),
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

data class ToolCallInfo(
    val name: String,
    val input: String,
    val result: String? = null,
)

/** Events emitted by ConversationManager during a turn, consumed by the UI. */
sealed class ChatEvent {
    /** Streaming text delta from the assistant. */
    data class Delta(val text: String) : ChatEvent()

    /** A tool is being called by the agent. */
    data class ToolCall(val name: String, val input: JsonObject) : ChatEvent()

    /** A tool call has completed. */
    data class ToolResult(val name: String, val output: String) : ChatEvent()

    /** An agent round completed (contains reply text for that round). */
    data class AgentRound(val reply: String, val raw: JsonObject) : ChatEvent()

    /** The full turn is complete. */
    data class Done(val fullText: String) : ChatEvent()

    /** An error occurred. */
    data class Error(val message: String) : ChatEvent()
}

/** Full state of one conversation. */
data class ConversationState(
    val conversationId: String? = null,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val model: String = "gpt-4.1",
    val agentMode: Boolean = true,
    val isStreaming: Boolean = false,
)
