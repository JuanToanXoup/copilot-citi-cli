package com.speckit.companion.mcp

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal MCP client that communicates with a local MCP server over stdio.
 * Speaks JSON-RPC 2.0 with MCP's initialize/tools/list/tools/call methods.
 */
class McpClient private constructor(
    private val process: Process,
    private val writer: BufferedWriter,
    private val reader: BufferedReader
) {
    private val log = Logger.getInstance(McpClient::class.java)
    private val gson = Gson()
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CountDownLatch>()
    private val results = ConcurrentHashMap<Int, JsonObject>()
    private val readerThread: Thread

    init {
        readerThread = Thread({
            try {
                while (process.isAlive) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    try {
                        val msg = JsonParser.parseString(line).asJsonObject
                        val id = msg.get("id")?.asInt ?: continue
                        results[id] = msg
                        pending[id]?.countDown()
                    } catch (e: Exception) {
                        log.debug("Non-JSON line from MCP server: $line")
                    }
                }
            } catch (_: Exception) {
                // Process closed
            }
        }, "speckit-mcp-reader")
        readerThread.isDaemon = true
        readerThread.start()
    }

    private fun send(method: String, params: Any? = null): JsonObject {
        val id = nextId.getAndIncrement()
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            if (params != null) {
                add("params", gson.toJsonTree(params))
            }
        }

        val latch = CountDownLatch(1)
        pending[id] = latch

        val json = gson.toJson(request)
        synchronized(writer) {
            writer.write(json)
            writer.newLine()
            writer.flush()
        }

        if (!latch.await(30, TimeUnit.SECONDS)) {
            pending.remove(id)
            throw RuntimeException("MCP request timed out: $method")
        }

        val response = results.remove(id) ?: throw RuntimeException("No response for MCP request: $method")
        pending.remove(id)

        if (response.has("error")) {
            val error = response.getAsJsonObject("error")
            throw RuntimeException("MCP error: ${error.get("message")?.asString}")
        }

        return response.getAsJsonObject("result") ?: JsonObject()
    }

    fun initialize(): McpClient {
        send("initialize", mapOf(
            "protocolVersion" to "2024-11-05",
            "capabilities" to emptyMap<String, Any>(),
            "clientInfo" to mapOf("name" to "speckit-companion", "version" to "0.1.0")
        ))
        // Send initialized notification (no id)
        val notification = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", "notifications/initialized")
        }
        synchronized(writer) {
            writer.write(gson.toJson(notification))
            writer.newLine()
            writer.flush()
        }
        return this
    }

    fun listTools(): List<McpToolSchema> {
        val result = send("tools/list")
        val tools = result.getAsJsonArray("tools") ?: return emptyList()
        return tools.map { el ->
            val obj = el.asJsonObject
            McpToolSchema(
                name = obj.get("name").asString,
                description = obj.get("description")?.asString ?: "",
                inputSchema = gson.fromJson(obj.get("inputSchema"), Map::class.java) as Map<String, Any>
            )
        }
    }

    fun callTool(name: String, arguments: JsonObject?): String {
        val params = mapOf(
            "name" to name,
            "arguments" to (arguments ?: JsonObject())
        )
        val result = send("tools/call", params)
        val content = result.getAsJsonArray("content") ?: return result.toString()
        return content.joinToString("\n") { el ->
            val obj = el.asJsonObject
            obj.get("text")?.asString ?: obj.toString()
        }
    }

    fun stop() {
        try {
            writer.close()
            reader.close()
            process.destroyForcibly()
        } catch (_: Exception) {}
    }

    companion object {
        fun start(command: String, args: List<String> = emptyList(), env: Map<String, String> = emptyMap()): McpClient {
            val pb = ProcessBuilder(listOf(command) + args)
                .redirectErrorStream(false)
            pb.environment().putAll(env)

            val process = pb.start()
            val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            return McpClient(process, writer, reader).initialize()
        }
    }
}
