package com.citigroup.copilotchat.lsp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Application-level LSP client service.
 * Spawns copilot-language-server, handles JSON-RPC dispatch, request/response matching,
 * and progress routing. Port of client.py's CopilotClient.
 */
@Service(Service.Level.APP)
class LspClient : Disposable {

    private val log = Logger.getInstance(LspClient::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val requestId = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var process: Process? = null
    private var transport: LspTransport? = null

    /** Pending request completions keyed by message id. */
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    /** Progress listeners keyed by workDoneToken. */
    private val progressListeners = ConcurrentHashMap<String, (JsonObject) -> Unit>()

    /** Per-project handlers for server→client requests. Returns true if handled. */
    private val serverRequestHandlers = ConcurrentHashMap<String, (method: String, id: Int, params: JsonObject) -> Boolean>()

    /** Feature flags received from the server via featureFlagsNotification. */
    @Volatile
    var featureFlags: Map<String, Any> = emptyMap()
        private set

    /** Check if server-side MCP is allowed (from org feature flags). */
    val isServerMcpEnabled: Boolean get() = featureFlags["mcp"] == true

    val isRunning: Boolean get() = process?.isAlive == true

    companion object {
        fun getInstance(): LspClient =
            ApplicationManager.getApplication().getService(LspClient::class.java)
    }

    /**
     * Spawn the copilot-language-server process and start the reader coroutine.
     */
    fun start(binaryPath: String, env: Map<String, String> = emptyMap()) {
        if (isRunning) return

        val pb = ProcessBuilder(binaryPath, "--stdio")
        pb.environment().putAll(env)
        pb.redirectErrorStream(false)

        process = pb.start()
        transport = LspTransport(process!!.inputStream, process!!.outputStream)

        // Reader coroutine
        scope.launch {
            transport!!.readLoop { rawJson ->
                try {
                    dispatchMessage(rawJson)
                } catch (e: Exception) {
                    log.warn("Error dispatching LSP message", e)
                }
            }
        }

        // Drain stderr
        scope.launch(Dispatchers.IO) {
            try {
                process!!.errorStream.bufferedReader().use { reader ->
                    while (true) {
                        reader.readLine() ?: break
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun dispatchMessage(rawJson: String) {
        val obj = json.parseToJsonElement(rawJson).jsonObject
        val id = obj["id"]?.let {
            when (it) {
                is JsonPrimitive -> if (it.isString) it.content.toIntOrNull() else it.intOrNull
                else -> null
            }
        }
        val method = obj["method"]?.jsonPrimitive?.contentOrNull

        when {
            // Response to our request (has id, no method)
            id != null && method == null -> {
                pendingRequests.remove(id)?.complete(obj)
            }
            // Server→client request (has both id and method)
            id != null && method != null -> {
                val params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
                val handlers = serverRequestHandlers.values.toList()
                if (handlers.isEmpty()) {
                    sendResponse(id, JsonNull)
                } else {
                    // Try each project handler; the one that owns the
                    // conversationId returns true and handles the request.
                    val handled = handlers.any { it.invoke(method, id, params) }
                    if (!handled) {
                        // No project claimed it — respond with null to avoid
                        // blocking the server (e.g. unknown conversationId).
                        log.debug("No project handler claimed server request: $method")
                        sendResponse(id, JsonNull)
                    }
                }
            }
            // $/progress notification
            method == "\$/progress" -> {
                val params = obj["params"]?.jsonObject ?: return
                val token = params["token"]?.jsonPrimitive?.contentOrNull ?: return
                val value = params["value"]?.jsonObject ?: return
                progressListeners[token]?.invoke(value)
            }
            // Feature flags notification from the server
            method == "featureFlagsNotification" -> {
                val params = obj["params"]?.jsonObject ?: return
                val flags = mutableMapOf<String, Any>()
                for ((key, value) in params) {
                    when (value) {
                        is JsonPrimitive -> {
                            if (value.booleanOrNull != null) flags[key] = value.boolean
                            else if (value.intOrNull != null) flags[key] = value.int
                            else flags[key] = value.content
                        }
                        else -> flags[key] = value.toString()
                    }
                }
                featureFlags = flags
                log.info("Feature flags received: $flags")
            }
            // Other notifications — log and ignore
            else -> {
                log.debug("LSP notification: $method")
            }
        }
    }

    /**
     * Send a JSON-RPC request and suspend until the response arrives.
     */
    suspend fun sendRequest(
        method: String,
        params: JsonElement? = null,
        timeoutMs: Long = 120_000,
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

        transport?.sendMessage(msg.toString())
            ?: throw IllegalStateException("LSP transport not started")

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingRequests.remove(id)
            throw RuntimeException("LSP request timed out: $method (id=$id)")
        }
    }

    /** Send a JSON-RPC notification (no response expected). */
    fun sendNotification(method: String, params: JsonElement? = null) {
        val msg = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }
        transport?.sendMessage(msg.toString())
    }

    /** Send a JSON-RPC response to a server→client request. */
    fun sendResponse(id: Int, result: JsonElement) {
        val msg = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }
        transport?.sendMessage(msg.toString())
    }

    /** Register a per-project handler for server→client requests. Handler returns true if it claimed the request. */
    fun registerServerRequestHandler(projectKey: String, handler: (method: String, id: Int, params: JsonObject) -> Boolean) {
        serverRequestHandlers[projectKey] = handler
    }

    /** Remove a project's server request handler. */
    fun removeServerRequestHandler(projectKey: String) {
        serverRequestHandlers.remove(projectKey)
    }

    /** Register a progress listener for a workDoneToken. */
    fun registerProgressListener(token: String, listener: (JsonObject) -> Unit) {
        progressListeners[token] = listener
    }

    /** Remove a progress listener. */
    fun removeProgressListener(token: String) {
        progressListeners.remove(token)
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
        process?.let {
            it.destroy()
            it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (it.isAlive) it.destroyForcibly()
        }
        process = null
        transport = null
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
        progressListeners.clear()
        serverRequestHandlers.clear()
    }

    override fun dispose() {
        stop()
        scope.cancel()
    }
}
