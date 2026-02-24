package com.citigroup.copilotchat.mcp

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI

/**
 * SSE MCP server — connects to an HTTP SSE endpoint.
 *
 * Protocol logic (initialize, listTools, callTool, request correlation) lives
 * in [McpTransportBase]. This class handles only SSE connection, endpoint
 * discovery, and HTTP POST message delivery.
 *
 * SSE transport flow:
 * 1. GET the SSE endpoint — server sends an 'endpoint' event with a POST URL
 * 2. POST JSON-RPC requests to that URL
 * 3. Server sends responses as SSE 'message' events
 */
class McpSseServer(
    name: String,
    private val url: String,
    private val env: Map<String, String> = emptyMap(),
    initTimeout: Long = 60_000,
) : McpTransportBase(name, initTimeout) {

    /** POST URL discovered from the SSE endpoint event. */
    @Volatile
    private var postUrl: String? = null

    /** Signal that the SSE connection is established and postUrl is available. */
    private val connected = CompletableDeferred<Unit>()

    override suspend fun start() {
        scope.launch { sseLoop() }

        try {
            withTimeout(initTimeout) { connected.await() }
        } catch (e: TimeoutCancellationException) {
            throw RuntimeException("MCP server '$name': SSE endpoint did not send endpoint event")
        }
    }

    override fun stop() {
        scope.coroutineContext.cancelChildren()
        cancelPending()
    }

    override fun sendRaw(msg: JsonObject) {
        val targetUrl = postUrl ?: return
        scope.launch(Dispatchers.IO) {
            try {
                httpPost(targetUrl, msg.toString())
            } catch (_: Exception) {}
        }
    }

    override fun sendNotification(method: String, params: JsonElement?) {
        val targetUrl = postUrl ?: return
        val msg = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }
        scope.launch(Dispatchers.IO) {
            try {
                httpPost(targetUrl, msg.toString())
            } catch (_: Exception) {}
        }
    }

    /**
     * Override sendRequest to POST synchronously on the caller's coroutine
     * (so the response arrives via the SSE stream, not the POST response).
     */
    override suspend fun sendRequest(
        method: String,
        params: JsonElement?,
        timeoutMs: Long,
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

        withContext(Dispatchers.IO) {
            try {
                httpPost(targetUrl, msg.toString())
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

    // ── SSE transport ────────────────────────────────────────────────

    private suspend fun sseLoop() = withContext(Dispatchers.IO) {
        try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.connectTimeout = 30_000
            connection.readTimeout = 0
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

    private fun httpPost(targetUrl: String, body: String) {
        val connection = URI(targetUrl).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.doOutput = true
        connection.outputStream.use { out ->
            out.write(body.toByteArray(Charsets.UTF_8))
        }
        connection.responseCode
    }
}
