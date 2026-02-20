package com.citigroup.copilotchat.mcp

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stdio MCP server — spawns a process, speaks newline-delimited JSON-RPC 2.0.
 * Port of Python's MCPServer from mcp.py.
 *
 * MCP stdio transport: each message is a single line of JSON followed by \n.
 * This is different from LSP which uses Content-Length headers.
 */
class McpServer(
    val name: String,
    private val command: String,
    private val args: List<String> = emptyList(),
    private val env: Map<String, String> = emptyMap(),
    private val initTimeout: Long = 60_000,
) {
    private val log = Logger.getInstance(McpServer::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val requestId = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var process: Process? = null
    var tools: List<JsonObject> = emptyList()
        private set

    /** Pending request completions keyed by message id. */
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    /**
     * Spawn the MCP server process and start the reader coroutine.
     */
    suspend fun start() {
        val processEnv = System.getenv().toMutableMap()
        processEnv.putAll(env)

        // Resolve command — check if it exists on PATH
        val resolvedCommand = resolveCommand(command)

        val cmdLine = mutableListOf(resolvedCommand)
        cmdLine.addAll(args)

        val pb = ProcessBuilder(cmdLine)
        pb.environment().clear()
        pb.environment().putAll(processEnv)
        pb.redirectErrorStream(false)

        process = pb.start()

        // Reader coroutine — reads newline-delimited JSON-RPC from stdout
        scope.launch {
            try {
                process!!.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        try {
                            val msg = json.parseToJsonElement(line).jsonObject
                            dispatchMessage(msg)
                        } catch (e: Exception) {
                            log.debug("MCP $name: failed to parse line: ${line.take(200)}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    log.debug("MCP $name: reader ended: ${e.message}")
                }
            }
        }

        // Drain stderr — log warnings, filter noise
        scope.launch {
            try {
                process!!.errorStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        // Suppress Java stack traces and common noise
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("at ") || trimmed.startsWith("Caused by:") || trimmed.startsWith("...")) continue
                        if ("WARN" in line || "SEVERE" in line) continue
                        if (line.startsWith("Java HotSpot") || line.startsWith("java.") ||
                            line.startsWith("com.intellij") || line.startsWith("org.jetbrains") ||
                            line.startsWith("kotlin.") || line.startsWith("kotlinx.") ||
                            line.startsWith("sun.") || line.startsWith("jdk.")
                        ) continue
                        log.info("MCP $name stderr: $line")
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Perform MCP initialize handshake.
     */
    suspend fun initialize() {
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

        // Send initialized notification
        sendNotification("notifications/initialized")
    }

    /**
     * Discover tools via MCP tools/list.
     */
    suspend fun listTools(): List<JsonObject> {
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
    suspend fun callTool(toolName: String, arguments: JsonObject): String {
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
     * Stop the MCP server process gracefully.
     */
    fun stop() {
        scope.coroutineContext.cancelChildren()
        process?.let {
            try {
                it.destroy()
                it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (it.isAlive) it.destroyForcibly()
            } catch (_: Exception) {}
        }
        process = null
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
    }

    private fun dispatchMessage(msg: JsonObject) {
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
            // Server→client request — auto-respond
            id != null && method != null -> {
                sendRaw(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    putJsonObject("result") {}
                })
            }
        }
    }

    private suspend fun sendRequest(
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

    private fun sendNotification(method: String, params: JsonElement? = null) {
        val msg = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }
        sendRaw(msg)
    }

    private fun sendRaw(msg: JsonObject) {
        try {
            val line = msg.toString() + "\n"
            process?.outputStream?.let {
                it.write(line.toByteArray(Charsets.UTF_8))
                it.flush()
            }
        } catch (_: Exception) {}
    }

    private fun resolveCommand(cmd: String): String {
        // Try to find the command on PATH (handles .cmd/.bat on Windows)
        val pathDirs = System.getenv("PATH")?.split(java.io.File.pathSeparator) ?: return cmd
        for (dir in pathDirs) {
            val file = java.io.File(dir, cmd)
            if (file.canExecute()) return file.absolutePath
            // Windows: check .cmd and .bat extensions
            for (ext in listOf(".cmd", ".bat", ".exe")) {
                val withExt = java.io.File(dir, cmd + ext)
                if (withExt.canExecute()) return withExt.absolutePath
            }
        }
        return cmd
    }
}
