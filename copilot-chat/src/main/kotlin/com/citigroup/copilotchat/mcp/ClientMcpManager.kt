package com.citigroup.copilotchat.mcp

import com.citigroup.copilotchat.config.CopilotChatSettings
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/**
 * Orchestrates multiple client-side MCP servers.
 * Direct port of Python's ClientMCPManager from mcp.py.
 *
 * Spawns MCP server processes locally, discovers tools, registers them as
 * client tools with prefixed names (mcp_<server>_<tool>), and routes
 * invokeClientTool calls to the correct server.
 */
class ClientMcpManager(
    /** Proxy URL to inject into MCP server processes (HTTP_PROXY/HTTPS_PROXY). */
    private val proxyUrl: String = "",
) {

    private val log = Logger.getInstance(ClientMcpManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** Active MCP servers keyed by name. */
    private val stdioServers = mutableMapOf<String, McpServer>()
    private val sseServers = mutableMapOf<String, McpSseServer>()

    /** Maps server name -> list of original tool schemas (for compound routing). */
    private val serverToolIndex = mutableMapOf<String, Map<String, JsonObject>>()

    /** Set of compound tool names (one per server). */
    private val compoundToolNames = mutableSetOf<String>()

    /**
     * Build environment vars that every MCP server process needs:
     * proxy settings + augmented PATH for IntelliJ (which launches with a minimal PATH).
     */
    private fun buildBaseEnv(): Map<String, String> {
        val base = mutableMapOf<String, String>()

        // Inject proxy so npm/npx can reach registries behind corporate proxies
        if (proxyUrl.isNotBlank()) {
            base["HTTP_PROXY"] = proxyUrl
            base["HTTPS_PROXY"] = proxyUrl
            // npm also reads this lowercase variant
            base["http_proxy"] = proxyUrl
            base["https_proxy"] = proxyUrl
        }

        // Augment PATH — IntelliJ on macOS often has a minimal PATH that
        // misses /usr/local/bin, homebrew, nvm, volta, etc.
        val extraPaths = listOf(
            "/usr/local/bin",
            "/opt/homebrew/bin",
            "/opt/homebrew/sbin",
            "${System.getProperty("user.home")}/.nvm/versions/node/*/bin",  // nvm
            "${System.getProperty("user.home")}/.volta/bin",                // volta
            "${System.getProperty("user.home")}/.local/bin",
        )
        val currentPath = System.getenv("PATH") ?: ""
        val currentDirs = currentPath.split(java.io.File.pathSeparator).toSet()

        // Resolve glob patterns (nvm node version dirs) and add missing dirs
        val resolved = extraPaths.flatMap { pattern ->
            if ("*" in pattern) {
                try {
                    java.nio.file.FileSystems.getDefault()
                        .getPathMatcher("glob:$pattern")
                    // Simple glob: list parent dir and filter
                    val parent = java.io.File(pattern.substringBefore("*"))
                    if (parent.isDirectory) {
                        parent.listFiles()
                            ?.filter { it.isDirectory }
                            ?.map { java.io.File(it, pattern.substringAfterLast("*/")).absolutePath }
                            ?.filter { java.io.File(it).isDirectory }
                            ?: emptyList()
                    } else emptyList()
                } catch (_: Exception) { emptyList() }
            } else {
                listOf(pattern)
            }
        }.filter { it !in currentDirs && java.io.File(it).isDirectory }

        if (resolved.isNotEmpty()) {
            base["PATH"] = (resolved + currentPath).joinToString(java.io.File.pathSeparator)
        }

        return base
    }

    /**
     * Add MCP servers from settings entries.
     */
    fun addServers(entries: List<CopilotChatSettings.McpServerEntry>) {
        val baseEnv = buildBaseEnv()

        for (entry in entries) {
            if (!entry.enabled) continue

            val envMap = mutableMapOf<String, String>()
            envMap.putAll(baseEnv)
            if (entry.env.isNotBlank()) {
                entry.env.lines().filter { "=" in it }.forEach { line ->
                    val (k, v) = line.split("=", limit = 2)
                    envMap[k.trim()] = v.trim()
                }
            }

            if (entry.url.isNotBlank()) {
                // SSE transport
                sseServers[entry.name] = McpSseServer(
                    name = entry.name,
                    url = entry.url,
                    env = envMap,
                )
            } else if (entry.command.isNotBlank()) {
                // Stdio transport
                val args = if (entry.args.isNotBlank()) {
                    entry.args.split(" ").filter { it.isNotBlank() }
                } else {
                    emptyList()
                }
                stdioServers[entry.name] = McpServer(
                    name = entry.name,
                    command = entry.command,
                    args = args,
                    env = envMap,
                )
            } else {
                log.warn("MCP: skipping '${entry.name}': need command (stdio) or url (SSE)")
            }
        }
    }

    /** Errors from the last startAll() — surfaced in the chat UI. */
    var startupErrors: List<String> = emptyList()
        private set

    /**
     * Start all servers, initialize them, and discover tools.
     */
    suspend fun startAll(onProgress: ((String) -> Unit)? = null) {
        val errors = mutableListOf<String>()

        // Start stdio servers
        for ((name, server) in stdioServers) {
            try {
                onProgress?.invoke("Starting MCP: $name...")
                server.start()
                delay(500)
                server.initialize()
                delay(500)
                server.listTools()
                log.info("MCP $name: ${server.tools.size} tools discovered")
            } catch (e: Exception) {
                val msg = "MCP $name: ${e.message}"
                log.warn(msg, e)
                errors.add(msg)
            }
        }

        // Start SSE servers
        for ((name, server) in sseServers) {
            try {
                onProgress?.invoke("Starting MCP SSE: $name...")
                server.start()
                server.initialize()
                server.listTools()
                log.info("MCP SSE $name: ${server.tools.size} tools discovered")
            } catch (e: Exception) {
                val msg = "MCP SSE $name: ${e.message}"
                log.warn(msg, e)
                errors.add(msg)
            }
        }

        startupErrors = errors

        // Build server tool index (for compound tool routing)
        serverToolIndex.clear()
        compoundToolNames.clear()
        for ((name, server) in stdioServers) {
            if (server.tools.isNotEmpty()) {
                serverToolIndex[name] = server.tools.associateBy {
                    it["name"]?.jsonPrimitive?.contentOrNull ?: ""
                }
                compoundToolNames.add(name)
            }
        }
        for ((name, server) in sseServers) {
            if (server.tools.isNotEmpty()) {
                serverToolIndex[name] = server.tools.associateBy {
                    it["name"]?.jsonPrimitive?.contentOrNull ?: ""
                }
                compoundToolNames.add(name)
            }
        }
    }

    /**
     * Returns tool schemas for conversation/registerTools.
     * Each MCP server is registered as a single compound tool (server name = tool name).
     * The model picks the server tool, then specifies an "action" to route to the right sub-tool.
     * @param isEnabled optional filter — receives the original tool name, returns true if enabled.
     */
    fun getToolSchemas(isEnabled: ((String) -> Boolean)? = null): List<String> {
        val schemas = mutableListOf<String>()

        for ((name, server) in stdioServers) {
            val tools = filterTools(server.tools, isEnabled)
            if (tools.isNotEmpty()) {
                schemas.add(buildCompoundSchema(name, tools))
            }
        }
        for ((name, server) in sseServers) {
            val tools = filterTools(server.tools, isEnabled)
            if (tools.isNotEmpty()) {
                schemas.add(buildCompoundSchema(name, tools))
            }
        }

        return schemas
    }

    private fun filterTools(tools: List<JsonObject>, isEnabled: ((String) -> Boolean)?): List<JsonObject> {
        if (isEnabled == null) return tools
        return tools.filter { tool ->
            val toolName = tool["name"]?.jsonPrimitive?.contentOrNull ?: return@filter true
            isEnabled(toolName)
        }
    }

    /**
     * Check if a tool name is a client-side MCP compound tool.
     */
    fun isMcpTool(name: String): Boolean = name in compoundToolNames

    /**
     * Call a client-side MCP tool. The input must contain an "action" field
     * that maps to the original tool name within the server.
     */
    suspend fun callTool(name: String, input: JsonObject): String {
        val action = input["action"]?.jsonPrimitive?.contentOrNull
            ?: return "Error: 'action' parameter is required for MCP tool '$name'"

        val serverTools = serverToolIndex[name]
            ?: return "Unknown MCP server: $name"

        if (action !in serverTools) {
            return "Unknown action '$action' for MCP server '$name'. Available: ${serverTools.keys.joinToString(", ")}"
        }

        // Strip "action" from the input, pass the rest to the underlying tool
        val toolInput = JsonObject(input.filterKeys { it != "action" })

        // Try stdio servers first, then SSE
        val stdioServer = stdioServers[name]
        if (stdioServer != null) {
            return try {
                stdioServer.callTool(action, toolInput)
            } catch (e: Exception) {
                "MCP '$name' action '$action' error: ${e.message}"
            }
        }

        val sseServer = sseServers[name]
        if (sseServer != null) {
            return try {
                sseServer.callTool(action, toolInput)
            } catch (e: Exception) {
                "MCP '$name' action '$action' error: ${e.message}"
            }
        }

        return "MCP server '$name' not found"
    }

    /**
     * Stop all MCP servers.
     */
    fun stopAll() {
        for (server in stdioServers.values) {
            try { server.stop() } catch (_: Exception) {}
        }
        for (server in sseServers.values) {
            try { server.stop() } catch (_: Exception) {}
        }
        stdioServers.clear()
        sseServers.clear()
        serverToolIndex.clear()
        compoundToolNames.clear()
    }

    /**
     * Build a single compound tool schema for an MCP server.
     * All the server's tools become "actions" within one tool.
     *
     * Example: a "playwright" server with 22 browser tools becomes:
     *   { name: "playwright", inputSchema: { action: "browser_click", element: "Submit", ref: "e3" } }
     */
    private fun buildCompoundSchema(serverName: String, tools: List<JsonObject>): String {
        // Collect action names and build per-action descriptions
        val actionNames = mutableListOf<String>()
        val actionDocs = StringBuilder()

        // Merge all action-specific properties into a flat set
        val allProperties = mutableMapOf<String, JsonObject>()

        for (tool in tools) {
            val toolName = tool["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val description = tool["description"]?.jsonPrimitive?.contentOrNull ?: toolName
            actionNames.add(toolName)

            // Build action doc line: "- action_name: description. Params: {p1, p2}"
            val inputSchema = tool["inputSchema"]?.jsonObject
            val paramNames = inputSchema?.get("properties")?.jsonObject?.keys ?: emptySet()
            val paramsHint = if (paramNames.isNotEmpty()) " Params: {${paramNames.joinToString(", ")}}" else ""
            actionDocs.appendLine("- $toolName: $description$paramsHint")

            // Merge this tool's properties into the flat set
            if (inputSchema != null) {
                val props = inputSchema["properties"]?.jsonObject
                if (props != null) {
                    for ((propName, propValue) in props) {
                        if (propName !in allProperties && propValue is JsonObject) {
                            allProperties[propName] = sanitizeSchema(propValue)
                        }
                    }
                }
            }
        }

        // Build the compound schema
        val compoundSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "The action to perform")
                    putJsonArray("enum") { actionNames.forEach { add(it) } }
                }
                for ((propName, propSchema) in allProperties) {
                    put(propName, propSchema)
                }
            }
            putJsonArray("required") { add("action") }
        }

        val desc = buildString {
            append("$serverName MCP server. Use 'action' to pick the operation.\n\nActions:\n")
            append(actionDocs.toString().trimEnd())
        }

        return buildJsonObject {
            put("name", serverName)
            put("description", desc)
            put("inputSchema", compoundSchema)
        }.toString()
    }

    /**
     * Fix JSON Schema constructs that the Copilot server rejects.
     * Port of Python's _sanitize_schema() from mcp.py.
     *
     * - Converts array-typed "type" (e.g. ["object", "null"]) to a string
     * - Flattens anyOf/oneOf unions into a single type
     * - Ensures every property has a "type" field
     * - Recursively processes properties, items, additionalProperties
     */
    private fun sanitizeSchema(schema: JsonObject): JsonObject {
        val mutable = schema.toMutableMap()

        // Handle anyOf / oneOf
        for (keyword in listOf("anyOf", "oneOf")) {
            val variants = mutable[keyword]?.jsonArray
            if (variants != null) {
                val types = variants
                    .mapNotNull { it as? JsonObject }
                    .mapNotNull { v ->
                        val t = v["type"]?.jsonPrimitive?.contentOrNull
                        if (t != null && t != "null") t else null
                    }
                // Collect extra fields from the first non-null variant
                val extra = mutableMapOf<String, JsonElement>()
                for (v in variants) {
                    val obj = v as? JsonObject ?: continue
                    val t = obj["type"]?.jsonPrimitive?.contentOrNull
                    if (t != null && t != "null") {
                        for ((k, value) in obj) {
                            if (k != "type") extra[k] = value
                        }
                        break
                    }
                }

                mutable.remove(keyword)
                mutable["type"] = JsonPrimitive(types.firstOrNull() ?: "string")
                mutable.putAll(extra)
            }
        }

        // Handle array "type" (e.g. ["object", "null"])
        val typeEl = mutable["type"]
        if (typeEl is JsonArray) {
            val types = typeEl.mapNotNull {
                val t = it.jsonPrimitive.contentOrNull
                if (t != null && t != "null") t else null
            }
            mutable["type"] = JsonPrimitive(types.firstOrNull() ?: "string")
        }

        // Ensure "type" exists
        if ("type" !in mutable && "properties" !in mutable) {
            mutable["type"] = JsonPrimitive("string")
        }

        // Recursively sanitize properties
        val properties = mutable["properties"]?.jsonObject
        if (properties != null) {
            val sanitizedProps = buildJsonObject {
                for ((propName, propValue) in properties) {
                    if (propValue is JsonObject) {
                        put(propName, sanitizeSchema(propValue))
                    } else {
                        put(propName, propValue)
                    }
                }
            }
            mutable["properties"] = sanitizedProps
        }

        // Recursively sanitize items and additionalProperties
        for (kw in listOf("items", "additionalProperties")) {
            val nested = mutable[kw]
            if (nested is JsonObject) {
                mutable[kw] = sanitizeSchema(nested)
            }
        }

        return JsonObject(mutable)
    }
}
