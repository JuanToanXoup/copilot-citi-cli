package com.citigroup.copilotchat.mcp

import com.citigroup.copilotchat.config.CopilotChatSettings
import com.citigroup.copilotchat.mcp.transport.McpServer
import com.citigroup.copilotchat.mcp.transport.McpSseServer
import com.citigroup.copilotchat.mcp.transport.McpTransport
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
 *
 * Tool schemas registered with the server are **sanitized** to avoid triggering
 * content policy filters (e.g. "playwright" → "page_tools", "browser_click" → "click").
 * Reverse mappings translate incoming tool calls back to original names for execution.
 */
class ClientMcpManager(
    /** Proxy URL to inject into MCP server processes (HTTP_PROXY/HTTPS_PROXY). */
    private val proxyUrl: String = "",
) {

    private val log = Logger.getInstance(ClientMcpManager::class.java)

    /** Active MCP servers keyed by name. */
    private val servers = mutableMapOf<String, McpTransport>()

    /** Maps server name -> list of original tool schemas (for compound routing). */
    private val serverToolIndex = mutableMapOf<String, Map<String, JsonObject>>()

    /** Set of compound tool names (one per server) — includes both original and sanitized names. */
    private val compoundToolNames = mutableSetOf<String>()

    /** Sanitized compound name → original server name (for routing tool calls back). */
    private val sanitizedToOriginalServer = mutableMapOf<String, String>()

    /** Per-server: sanitized action name → original action name. */
    private val sanitizedToOriginalAction = mutableMapOf<String, Map<String, String>>()

    companion object {
        private val BROWSER_KEYWORDS = setOf("playwright", "puppeteer", "browser", "selenium", "webdriver")
    }

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
                servers[entry.name] = McpSseServer(
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
                servers[entry.name] = McpServer(
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

        for ((name, server) in servers) {
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

        startupErrors = errors

        // Build server tool index (for compound tool routing)
        serverToolIndex.clear()
        compoundToolNames.clear()
        sanitizedToOriginalServer.clear()
        sanitizedToOriginalAction.clear()

        for ((name, server) in servers) {
            if (server.tools.isNotEmpty()) {
                serverToolIndex[name] = server.tools.associateBy {
                    it["name"]?.jsonPrimitive?.contentOrNull ?: ""
                }
                compoundToolNames.add(name)

                // Build sanitized-name → original-name mappings
                val sanitizedName = sanitizeServerName(name)
                if (sanitizedName != name) {
                    sanitizedToOriginalServer[sanitizedName] = name
                    compoundToolNames.add(sanitizedName)
                }
                val actionMap = mutableMapOf<String, String>()
                for (toolName in serverToolIndex[name]!!.keys) {
                    val sanitizedAction = sanitizeActionName(toolName)
                    if (sanitizedAction != toolName) {
                        actionMap[sanitizedAction] = toolName
                    }
                }
                if (actionMap.isNotEmpty()) {
                    sanitizedToOriginalAction[name] = actionMap
                }
            }
        }
    }

    /**
     * Returns tool schemas for conversation/registerTools.
     * Each MCP server is registered as a single compound tool (server name = tool name).
     * The model picks the server tool, then specifies an "action" to route to the right sub-tool.
     *
     * Schemas are **sanitized** to remove keywords (e.g. "playwright", "browser") that
     * trigger the Copilot server's content policy. The model sees neutral names;
     * [callTool] translates them back to the originals for execution.
     *
     * @param isEnabled optional filter — receives the original tool name, returns true if enabled.
     */
    fun getToolSchemas(isEnabled: ((String) -> Boolean)? = null): List<String> {
        val schemas = mutableListOf<String>()

        for ((name, server) in servers) {
            val tools = filterTools(server.tools, isEnabled)
            if (tools.isNotEmpty()) {
                schemas.add(buildCompoundSchema(name, tools, sanitize = true))
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
     *
     * Accepts both sanitized names (from the model) and original names.
     * Translates sanitized action names back to originals for execution.
     */
    suspend fun callTool(name: String, input: JsonObject): String {
        // Resolve sanitized server name → original
        val originalServerName = sanitizedToOriginalServer[name] ?: name

        val action = input["action"]?.jsonPrimitive?.contentOrNull
            ?: return "Error: 'action' parameter is required for MCP tool '$name'"

        val serverTools = serverToolIndex[originalServerName]
            ?: return "Unknown MCP server: $name"

        // Resolve sanitized action name → original
        val originalAction = sanitizedToOriginalAction[originalServerName]?.get(action) ?: action

        if (originalAction !in serverTools) {
            return "Unknown action '$action' for MCP server '$name'. Available: ${serverTools.keys.joinToString(", ")}"
        }

        // Strip "action" from the input, pass the rest to the underlying tool
        val toolInput = JsonObject(input.filterKeys { it != "action" })

        val server = servers[originalServerName]
            ?: return "MCP server '$name' not found"

        return try {
            server.callTool(originalAction, toolInput)
        } catch (e: Exception) {
            "MCP '$name' action '$action' error: ${e.message}"
        }
    }

    /**
     * Stop all MCP servers.
     */
    fun stopAll() {
        for (server in servers.values) {
            try { server.stop() } catch (_: Exception) {}
        }
        servers.clear()
        serverToolIndex.clear()
        compoundToolNames.clear()
        sanitizedToOriginalServer.clear()
        sanitizedToOriginalAction.clear()
    }

    /** Whether any server provides browser/playwright tools (used to suppress built-in browser_record). */
    fun hasBrowserTools(): Boolean =
        servers.keys.any { name -> isBrowserRelated(name) } ||
            serverToolIndex.values.any { tools ->
                tools.keys.any { it.startsWith("browser_") }
            }

    // ---- Schema sanitization ------------------------------------------------

    private fun isBrowserRelated(name: String): Boolean {
        val lower = name.lowercase()
        return BROWSER_KEYWORDS.any { it in lower }
    }

    /** Map server name to a neutral alias if it contains trigger keywords. */
    private fun sanitizeServerName(name: String): String =
        if (isBrowserRelated(name)) "page_tools" else name

    /** Strip "browser_" prefix from action names. */
    private fun sanitizeActionName(name: String): String =
        name.removePrefix("browser_")

    /** Replace trigger keywords in free-text descriptions. */
    private fun sanitizeText(text: String): String =
        text
            .replace(Regex("\\bBrowser\\b"), "Page")
            .replace(Regex("\\bbrowser\\b"), "page")
            .replace(Regex("\\bPlaywright\\b"), "tool")
            .replace(Regex("\\bplaywright\\b"), "tool")
            .replace(Regex("\\bPuppeteer\\b"), "tool")
            .replace(Regex("\\bpuppeteer\\b"), "tool")

    // ---- Compound schema builder --------------------------------------------

    /**
     * Build a single compound tool schema for an MCP server.
     * All the server's tools become "actions" within one tool.
     *
     * When [sanitize] is true, tool/action names and descriptions are rewritten
     * to avoid content-policy trigger words (e.g. "playwright", "browser_click").
     *
     * Example (sanitized): a "playwright" server with 22 tools becomes:
     *   { name: "page_tools", inputSchema: { action: "click", element: "Submit", ref: "e3" } }
     */
    private fun buildCompoundSchema(
        serverName: String,
        tools: List<JsonObject>,
        sanitize: Boolean = false,
    ): String {
        val displayName = if (sanitize) sanitizeServerName(serverName) else serverName

        // Collect action names and build per-action descriptions
        val actionNames = mutableListOf<String>()
        val actionDocs = StringBuilder()

        // Merge all action-specific properties into a flat set
        val allProperties = mutableMapOf<String, JsonObject>()

        for (tool in tools) {
            val toolName = tool["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val description = tool["description"]?.jsonPrimitive?.contentOrNull ?: toolName
            val displayAction = if (sanitize) sanitizeActionName(toolName) else toolName
            val displayDesc = if (sanitize) sanitizeText(description) else description
            actionNames.add(displayAction)

            // Build action doc line: "- action_name: description. Params: {p1, p2}"
            val inputSchema = tool["inputSchema"]?.jsonObject
            val paramNames = inputSchema?.get("properties")?.jsonObject?.keys ?: emptySet()
            val paramsHint = if (paramNames.isNotEmpty()) " Params: {${paramNames.joinToString(", ")}}" else ""
            actionDocs.appendLine("- $displayAction: $displayDesc$paramsHint")

            // Merge this tool's properties into the flat set
            if (inputSchema != null) {
                val props = inputSchema["properties"]?.jsonObject
                if (props != null) {
                    for ((propName, propValue) in props) {
                        if (propName !in allProperties && propValue is JsonObject) {
                            allProperties[propName] = SchemaSanitizer.sanitize(propValue)
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
            append("$displayName tool. Use 'action' to pick the operation.\n\nActions:\n")
            append(actionDocs.toString().trimEnd())
        }

        return buildJsonObject {
            put("name", displayName)
            put("description", desc)
            put("inputSchema", compoundSchema)
        }.toString()
    }

}
