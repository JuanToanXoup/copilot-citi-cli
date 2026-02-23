package com.citigroup.copilotchat.orchestrator

import com.citigroup.copilotchat.lsp.LspClient
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.*

/**
 * Lightweight conversation wrapper for a single worker agent.
 * Each WorkerSession gets its own conversationId on the shared LspClient,
 * providing isolated context while reusing the same LSP process.
 *
 * Port of Python QueueWorker._handle_task() logic.
 */
class WorkerSession(
    val workerId: String,
    val role: String,
    private val systemPrompt: String,
    private val model: String,
    private val agentMode: Boolean,
    private val toolsEnabled: List<String>?,  // null = all tools
    private val projectName: String,
    private val workspaceRoot: String,
) {
    private val log = Logger.getInstance(WorkerSession::class.java)
    private val lspClient: LspClient get() = LspClient.getInstance()

    private var conversationId: String? = null
    private var isFirstTurn = true

    /** Tracks cumulative reply length per round index to emit only incremental deltas. */
    private val roundReplyLengths = mutableMapOf<Int, Int>()

    /** Callback for streaming events during task execution. */
    var onEvent: ((WorkerEvent) -> Unit)? = null

    /** Callback fired once when the conversationId is first captured. */
    var onConversationId: ((String) -> Unit)? = null

    /**
     * Execute a task in this worker's conversation.
     * Creates a new conversation on first call, continues via conversation/turn on subsequent calls.
     */
    suspend fun executeTask(
        task: String,
        dependencyContext: Map<String, String> = emptyMap(),
    ): String {
        val prompt = buildPrompt(task, dependencyContext)
        val workDoneToken = "worker-${workerId}-${UUID.randomUUID().toString().take(8)}"
        // Must be thread-safe: progress handler writes from the LSP reader thread (Dispatchers.IO)
        // while this coroutine reads from Dispatchers.Default.
        val replyParts = Collections.synchronizedList(mutableListOf<String>())

        // Register progress listener for this worker's token
        lspClient.registerProgressListener(workDoneToken) { value ->
            handleProgress(value, replyParts)
        }

        try {
            val rootUri = "file://$workspaceRoot"

            if (conversationId == null) {
                // Create new conversation for this worker
                val params = buildJsonObject {
                    put("workDoneToken", workDoneToken)
                    putJsonArray("turns") {
                        addJsonObject { put("request", prompt) }
                    }
                    putJsonObject("capabilities") {
                        put("allSkills", agentMode)
                    }
                    put("source", "panel")
                    if (agentMode) {
                        put("chatMode", "Agent")
                        put("needToolCallConfirmation", true)
                    }
                    if (model.isNotBlank()) put("model", model)
                    put("workspaceFolder", rootUri)
                    putJsonArray("workspaceFolders") {
                        addJsonObject {
                            put("uri", rootUri)
                            put("name", projectName)
                        }
                    }
                }

                log.info("WorkerSession[$role] creating conversation (agent=$agentMode, model=$model)")
                val resp = lspClient.sendRequest("conversation/create", params, timeoutMs = 300_000)
                val result = resp["result"]
                conversationId = when (result) {
                    is JsonArray -> result.firstOrNull()?.jsonObject?.get("conversationId")?.jsonPrimitive?.contentOrNull
                    is JsonObject -> result["conversationId"]?.jsonPrimitive?.contentOrNull
                    else -> null
                }
                log.info("WorkerSession[$role] got conversationId=$conversationId")
                conversationId?.let { onConversationId?.invoke(it) }

                // Fallback: extract reply text from the create response if progress
                // events didn't deliver any (can happen with invalid/unsupported models).
                val hasRealParts = synchronized(replyParts) { replyParts.any { it != DONE_SENTINEL } }
                if (!hasRealParts) {
                    val responseReply = extractReplyFromResponse(result)
                    if (responseReply.isNotBlank()) {
                        log.info("WorkerSession[$role] extracted ${responseReply.length} chars from create response (no progress events)")
                        replyParts.add(0, responseReply)
                    }
                }
            } else {
                // Follow-up turn on existing conversation
                val params = buildJsonObject {
                    put("workDoneToken", workDoneToken)
                    put("conversationId", conversationId!!)
                    put("message", prompt)
                    put("source", "panel")
                    if (agentMode) {
                        put("chatMode", "Agent")
                        put("needToolCallConfirmation", true)
                    }
                    if (model.isNotBlank()) put("model", model)
                    put("workspaceFolder", rootUri)
                    putJsonArray("workspaceFolders") {
                        addJsonObject {
                            put("uri", rootUri)
                            put("name", projectName)
                        }
                    }
                }

                log.info("WorkerSession[$role] continuing conversation $conversationId")
                lspClient.sendRequest("conversation/turn", params, timeoutMs = 300_000)
            }

            // Wait for streaming to complete (progress listener handles the data)
            val startTime = System.currentTimeMillis()
            val timeout = if (agentMode) 300_000L else 60_000L
            while (System.currentTimeMillis() - startTime < timeout) {
                delay(100)
                // Check if we got an "end" progress event
                val done = synchronized(replyParts) {
                    if (replyParts.lastOrNull() == DONE_SENTINEL) {
                        replyParts.removeLast()
                        true
                    } else false
                }
                if (done) break
            }

        } finally {
            lspClient.removeProgressListener(workDoneToken)
            isFirstTurn = false
        }

        val fullReply = synchronized(replyParts) { replyParts.joinToString("") }
        if (fullReply.isEmpty()) {
            log.warn("WorkerSession[$role] task complete with EMPTY result (model=$model, agent=$agentMode)")
        } else {
            log.info("WorkerSession[$role] task complete (${fullReply.length} chars)")
        }
        return fullReply
    }

    /**
     * Try to extract reply text from the conversation/create response.
     * The response can be a JsonObject or JsonArray containing reply/message fields,
     * or editAgentRounds with reply text in each round.
     */
    private fun extractReplyFromResponse(result: JsonElement?): String {
        if (result == null) return ""
        return try {
            val obj = when (result) {
                is JsonArray -> result.firstOrNull()?.jsonObject
                is JsonObject -> result
                else -> null
            } ?: return ""

            // Try common reply fields
            val directReply = obj["reply"]?.jsonPrimitive?.contentOrNull
                ?: obj["message"]?.jsonPrimitive?.contentOrNull
            if (!directReply.isNullOrBlank()) return directReply

            // Try extracting from editAgentRounds (agent mode responses)
            val rounds = obj["editAgentRounds"]?.jsonArray
            if (rounds != null && rounds.isNotEmpty()) {
                val roundReplies = rounds.mapNotNull { roundEl ->
                    roundEl.jsonObject["reply"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                }
                if (roundReplies.isNotEmpty()) return roundReplies.joinToString("")
            }

            ""
        } catch (_: Exception) { "" }
    }

    /**
     * Cancel any active work on this session.
     */
    fun cancel() {
        // No per-session workDoneToken tracking needed — the orchestrator
        // cancels via coroutine cancellation which interrupts the suspend.
    }

    /**
     * Build the full prompt with system instructions, tool restrictions,
     * dependency context, and the actual task.
     */
    private fun buildPrompt(task: String, dependencyContext: Map<String, String>): String {
        val parts = mutableListOf<String>()

        // System instructions on first turn only
        if (isFirstTurn && systemPrompt.isNotBlank()) {
            parts.add("<system_instructions>\n$systemPrompt\n</system_instructions>")
        }

        // Tool restrictions if filtered
        if (toolsEnabled != null) {
            val toolList = toolsEnabled.joinToString(", ")
            parts.add(
                "<tool_restrictions>\n" +
                "You may ONLY use these tools: $toolList\n" +
                "Do not attempt to use any other tools.\n" +
                "</tool_restrictions>"
            )
        }

        // Dependency context from completed tasks
        if (dependencyContext.isNotEmpty()) {
            val contextJson = buildJsonObject {
                for ((key, value) in dependencyContext) {
                    put(key, value)
                }
            }
            parts.add("<shared_context>\n$contextJson\n</shared_context>")
        }

        parts.add(task)
        return parts.joinToString("\n\n")
    }

    /**
     * Handle progress events from the LSP server for this worker's workDoneToken.
     * Mirrors ConversationManager.handleProgress().
     */
    private fun handleProgress(value: JsonObject, replyParts: MutableList<String>) {
        val kind = value["kind"]?.jsonPrimitive?.contentOrNull

        // Extract text from any event (including "end" which can carry final reply)
        val reply = value["reply"]?.jsonPrimitive?.contentOrNull
        if (reply != null) {
            replyParts.add(reply)
            onEvent?.invoke(WorkerEvent.Delta(workerId, reply))
        }

        val delta = value["delta"]?.jsonPrimitive?.contentOrNull
        if (delta != null) {
            replyParts.add(delta)
            onEvent?.invoke(WorkerEvent.Delta(workerId, delta))
        }

        val message = value["message"]?.jsonPrimitive?.contentOrNull
        if (message != null && kind != "begin") {
            replyParts.add(message)
            onEvent?.invoke(WorkerEvent.Delta(workerId, message))
        }

        // Agent rounds — reply text is cumulative, so track what we've already seen
        val rounds = value["editAgentRounds"]?.jsonArray
        rounds?.forEachIndexed { idx, roundEl ->
            val round = roundEl.jsonObject
            val roundReply = round["reply"]?.jsonPrimitive?.contentOrNull ?: ""
            val prevLen = roundReplyLengths[idx] ?: 0
            if (roundReply.length > prevLen) {
                val newText = roundReply.substring(prevLen)
                roundReplyLengths[idx] = roundReply.length
                replyParts.add(newText)
                onEvent?.invoke(WorkerEvent.Delta(workerId, newText))
            }

            val toolCalls = round["toolCalls"]?.jsonArray
            toolCalls?.forEach { toolCallEl ->
                val tc = toolCallEl.jsonObject
                val name = tc["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                onEvent?.invoke(WorkerEvent.ToolCall(workerId, name))
            }
        }

        if (kind == "end") {
            replyParts.add(DONE_SENTINEL)
            onEvent?.invoke(WorkerEvent.Done(workerId))
        }
    }

    companion object {
        /** Sentinel value to signal that streaming is done. Never appears in actual replies. */
        private const val DONE_SENTINEL = "\u0000__DONE__\u0000"
    }
}

/** Events emitted by a WorkerSession during task execution. */
sealed class WorkerEvent {
    data class Delta(val workerId: String, val text: String) : WorkerEvent()
    data class ToolCall(val workerId: String, val toolName: String) : WorkerEvent()
    data class Done(val workerId: String) : WorkerEvent()
    data class Error(val workerId: String, val message: String) : WorkerEvent()
}
