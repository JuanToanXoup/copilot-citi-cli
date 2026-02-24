package com.citigroup.copilotchat.mcp

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * SSE MCP server — connects to an HTTP SSE endpoint.
 * Port of Python's MCPSSEServer from mcp.py.
 *
 * SSE transport flow:
 * 1. GET the SSE endpoint — server sends an 'endpoint' event with a POST URL
 * 2. POST JSON-RPC requests to that URL
 * 3. Server sends responses as SSE 'message' events
 */
class McpSseServer(
    override val name: String,
    private val url: String,
    private val env: Map<String, String> = emptyMap(),
    private val initTimeout: Long = 60_000,
) : McpTransport {
    private val log = Logger.getInstance(McpSseServer::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val requestId = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override var tools: List<JsonObject> = emptyList()
        private set

    /** Pending request completions keyed by message id. */
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    /** POST URL discovered from the SSE endpoint event. */
    @Volatile
    private var postUrl: String? = null

    /** Signal that the SSE connection is established and postUrl is available. */
    private val connected = CompletableDeferred<Unit>()

    /**
     * Connect to the SSE endpoint and discover the POST URL.
     */
    override suspend fun start() {
        // Start SSE reader in background
        scope.launch { sseLoop() }

        // Wait for the endpoint event
        try {
            withTimeout(initTimeout) { connected.await() }
        } catch (e: TimeoutCancellationException) {
            throw RuntimeException("MCP server '$name': SSE endpoint did not send endpoint event")
        }
    }

    /**
     * Perform MCP initialize handshake.
     */
    override suspend fun initialize() {
        val resp = sendRequest("initialize", buildJsonObject {
            put("protocolVersion", "2024-11-05")
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "copilot-chat")
                put("version", "1.0.0")
            }
        }, timeoutMs = initTimeout)

        log.info("MCP SSE $name: initialized")

        // Send initialized notification
        sendNotification("notifications/initialized")
    }

    /**
     * Discover tools via MCP tools/list.
     */
    override suspend fun listTools(): List<JsonObject> {
        val resp = sendRequest("tools/list", buildJsonObject {})
        tools = resp["result"]?.jsonObject
            ?.get("tools")?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?: emptyList()
        return tools
    }

    /**
     * Call an MCP tool and return the text result.
     */
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

    /**
     * Stop the SSE connection.
     */
    override fun stop() {
        scope.coroutineContext.cancelChildren()
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
    }

    private suspend fun sseLoop() = withContext(Dispatchers.IO) {
        try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.connectTimeout = 30_000
            connection.readTimeout = 0 // No read timeout for long-lived SSE stream
            connection.connect()

            val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
            var eventType: String? = null
            val dataLines = mutableListOf<String>()

            while (true) {
                val line = reader.readLine() ?: break

                when {
                    line.startsWith("event:") -> {
                        eventType = line.substring(6).trim()
                    }
                    line.startsWith("data:") -> {
                        dataLines.add(line.substring(5).trim())
                    }
                    line.isEmpty() -> {
                        // End of event
                        val data = dataLines.joinToString("\n")
                        dataLines.clear()

                        when (eventType) {
                            "endpoint" -> {
                                if (data.isNotEmpty()) {
                                    postUrl = if (data.startsWith("/")) {
                                        val parsed = URI(url)
                                        "${parsed.scheme}://${parsed.authority}$data"
                                    } else {
                                        data
                                    }
                                    connected.complete(Unit)
                                }
                            }
                            "message" -> {
                                if (data.isNotEmpty()) {
                                    try {
                                        val msg = json.parseToJsonElement(data).jsonObject
                                        val msgId = msg["id"]?.let {
                                            when (it) {
                                                is JsonPrimitive -> if (it.isString) it.content.toIntOrNull() else it.intOrNull
                                                else -> null
                                            }
                                        }
                                        if (msgId != null) {
                                            pendingRequests.remove(msgId)?.complete(msg)
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        eventType = null
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                log.warn("MCP SSE $name: connection ended: ${e.message}")
            }
        }
    }

    private suspend fun sendRequest(
        method: String,
        params: JsonElement? = null,
        timeoutMs: Long = 30_000,
    ): JsonObject {
        val targetUrl = postUrl ?: throw RuntimeException("MCP server '$name': not connected (no POST URL)")

        val id = requestId.incrementAndGet()
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred

        val msg = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }

        // POST the request
        withContext(Dispatchers.IO) {
            try {
                val connection = URI(targetUrl).toURL().openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.doOutput = true
                connection.outputStream.use { out ->
                    out.write(msg.toString().toByteArray(Charsets.UTF_8))
                }
                connection.responseCode // trigger the request
            } catch (e: Exception) {
                pendingRequests.remove(id)
                deferred.completeExceptionally(RuntimeException("POST failed: ${e.message}"))
            }
        }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingRequests.remove(id)
            throw RuntimeException("MCP server '$name': no response for $method (timeout ${timeoutMs}ms)")
        }
    }

    private fun sendNotification(method: String, params: JsonElement? = null) {
        val targetUrl = postUrl ?: return

        val msg = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }

        scope.launch(Dispatchers.IO) {
            try {
                val connection = URI(targetUrl).toURL().openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.doOutput = true
                connection.outputStream.use { out ->
                    out.write(msg.toString().toByteArray(Charsets.UTF_8))
                }
                connection.responseCode
            } catch (_: Exception) {}
        }
    }
}
