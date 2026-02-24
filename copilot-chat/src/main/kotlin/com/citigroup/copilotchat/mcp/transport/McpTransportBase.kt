package com.citigroup.copilotchat.mcp.transport

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared JSON-RPC 2.0 protocol logic for MCP transports.
 *
 * Subclasses implement [sendRaw] (transport-specific message delivery) and
 * [start] (transport-specific connection setup). Everything else —
 * initialize, listTools, callTool, request/response correlation, timeouts —
 * is handled here.
 */
abstract class McpTransportBase(
    override val name: String,
    protected val initTimeout: Long = 60_000,
) : McpTransport {

    protected val log: Logger = Logger.getInstance(this::class.java)
    protected val json = Json { ignoreUnknownKeys = true }
    protected val requestId = AtomicInteger(0)
    // IO: blocking network/process I/O for JSON-RPC transport
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override var tools: List<JsonObject> = emptyList()
        protected set

    /** Pending request completions keyed by message id. */
    protected val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    // ── Protocol methods (shared) ────────────────────────────────────

    override suspend fun initialize() {
        val resp = sendRequest("initialize", buildJsonObject {
            put("protocolVersion", "2024-11-05")
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "copilot-chat")
                put("version", "1.0.0")
            }
        }, timeoutMs = initTimeout)

        val result = resp["result"]?.jsonObject
        val serverInfo = result?.get("serverInfo")?.jsonObject
        log.info("MCP $name: initialized (server: ${serverInfo?.get("name")})")

        sendNotification("notifications/initialized")
    }

    override suspend fun listTools(): List<JsonObject> {
        val resp = sendRequest("tools/list", buildJsonObject {})
        tools = resp["result"]?.jsonObject
            ?.get("tools")?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?: emptyList()
        return tools
    }

    override suspend fun callTool(toolName: String, arguments: JsonObject): String {
        val resp = sendRequest("tools/call", buildJsonObject {
            put("name", toolName)
            put("arguments", arguments)
        }, timeoutMs = 120_000)

        val result = resp["result"]?.jsonObject ?: return resp.toString()
        val content = result["content"]?.jsonArray ?: return result.toString()

        val parts = mutableListOf<String>()
        for (item in content) {
            val obj = item as? JsonObject ?: continue
            val text = obj["text"]?.jsonPrimitive?.contentOrNull
                ?: obj["value"]?.jsonPrimitive?.contentOrNull
            if (text != null) parts.add(text)
        }
        return if (parts.isNotEmpty()) parts.joinToString("\n") else result.toString()
    }

    // ── Request infrastructure (shared) ──────────────────────────────

    protected open suspend fun sendRequest(
        method: String,
        params: JsonElement? = null,
        timeoutMs: Long = 30_000,
    ): JsonObject {
        val id = requestId.incrementAndGet()
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred

        val msg = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        sendRaw(msg)

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingRequests.remove(id)
            throw RuntimeException("MCP server '$name': no response for $method (timeout ${timeoutMs}ms)")
        }
    }

    protected open fun sendNotification(method: String, params: JsonElement? = null) {
        val msg = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }
        sendRaw(msg)
    }

    /**
     * Dispatch an incoming JSON-RPC message to the correct pending request.
     * Called by subclass reader loops.
     */
    protected fun dispatchMessage(msg: JsonObject) {
        val id = msg["id"]?.let {
            when (it) {
                is JsonPrimitive -> if (it.isString) it.content.toIntOrNull() else it.intOrNull
                else -> null
            }
        }
        val method = msg["method"]?.jsonPrimitive?.contentOrNull

        when {
            // Response to our request (has id, no method)
            id != null && method == null -> {
                pendingRequests.remove(id)?.complete(msg)
            }
            // Server→client request — auto-respond with empty result
            id != null && method != null -> {
                sendRaw(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    putJsonObject("result") {}
                })
            }
        }
    }

    protected fun cancelPending() {
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
    }

    // ── Transport-specific (abstract) ────────────────────────────────

    /** Send a raw JSON-RPC message over the transport. */
    protected abstract fun sendRaw(msg: JsonObject)
}
