package com.citigroup.copilotchat.tools

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Reflection bridge to ide-index plugin's JsonRpcHandler.
 * No compile-time dependency — discovers McpServerService at runtime.
 */
object IdeIndexBridge {

    private val log = Logger.getInstance(IdeIndexBridge::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private const val SERVICE_CLASS = "com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService"

    /** Check if ide-index plugin is available. */
    fun isAvailable(): Boolean {
        return try {
            Class.forName(SERVICE_CLASS)
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    /**
     * Get tool definitions from ide-index's ToolRegistry.
     * Returns JSON strings of tool schemas, or empty list if unavailable.
     */
    fun getToolSchemas(): List<String> {
        if (!isAvailable()) return emptyList()

        return try {
            val serviceClass = Class.forName(SERVICE_CLASS)
            val getInstance = serviceClass.getMethod("getInstance")
            val service = getInstance.invoke(null)
            val getToolRegistry = service.javaClass.getMethod("getToolRegistry")
            val registry = getToolRegistry.invoke(service)
            val getToolDefs = registry.javaClass.getMethod("getToolDefinitions")
            @Suppress("UNCHECKED_CAST")
            val definitions = getToolDefs.invoke(registry) as List<Any>

            definitions.map { def ->
                // ToolDefinition has name, description, inputSchema fields
                val name = def.javaClass.getMethod("getName").invoke(def) as String
                val description = def.javaClass.getMethod("getDescription").invoke(def) as String
                val inputSchema = def.javaClass.getMethod("getInputSchema").invoke(def)

                buildJsonObject {
                    put("name", name)
                    put("description", description)
                    put("inputSchema", json.parseToJsonElement(inputSchema.toString()))
                }.toString()
            }
        } catch (e: Exception) {
            log.warn("Failed to get tool schemas from ide-index", e)
            emptyList()
        }
    }

    /** Get the set of tool names available from ide-index. */
    fun getToolNames(): Set<String> {
        if (!isAvailable()) return emptySet()

        return try {
            val serviceClass = Class.forName(SERVICE_CLASS)
            val getInstance = serviceClass.getMethod("getInstance")
            val service = getInstance.invoke(null)
            val getToolRegistry = service.javaClass.getMethod("getToolRegistry")
            val registry = getToolRegistry.invoke(service)
            val getAllTools = registry.javaClass.getMethod("getAllTools")
            @Suppress("UNCHECKED_CAST")
            val tools = getAllTools.invoke(registry) as List<Any>

            tools.map { tool ->
                tool.javaClass.getMethod("getName").invoke(tool) as String
            }.toSet()
        } catch (e: Exception) {
            log.warn("Failed to get tool names from ide-index", e)
            emptySet()
        }
    }

    /**
     * Execute a tool call via ide-index's JsonRpcHandler.
     * Constructs a JSON-RPC tools/call request and sends it through the handler.
     */
    suspend fun callTool(toolName: String, input: JsonObject, projectPath: String? = null): String? {
        if (!isAvailable()) return null

        return try {
            val serviceClass = Class.forName(SERVICE_CLASS)
            val getInstance = serviceClass.getMethod("getInstance")
            val service = getInstance.invoke(null)
            val getHandler = service.javaClass.getMethod("getJsonRpcHandler")
            val handler = getHandler.invoke(service)

            // Build a JSON-RPC request for tools/call
            val rpcRequest = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", toolName)
                    put("arguments", input)
                    if (projectPath != null) {
                        put("project_path", projectPath)
                    }
                }
            }

            val handleRequest = handler.javaClass.methods.first {
                it.name == "handleRequest" && it.parameterCount == 2
            }
            val result = suspendCoroutine<String?> { cont ->
                try {
                    val ret = handleRequest.invoke(handler, rpcRequest.toString(), cont)
                    if (ret !== COROUTINE_SUSPENDED) {
                        @Suppress("UNCHECKED_CAST")
                        cont.resume(ret as? String)
                    }
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }

            // Parse the response and extract the tool result
            if (result != null) {
                val resp = json.parseToJsonElement(result).jsonObject
                val content = resp["result"]?.jsonObject?.get("content")
                content?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                    ?: result
            } else null
        } catch (e: Exception) {
            log.warn("Failed to call ide-index tool: $toolName", e)
            null
        }
    }
}
