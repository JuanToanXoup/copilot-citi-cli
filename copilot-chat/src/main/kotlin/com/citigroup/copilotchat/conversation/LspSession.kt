package com.citigroup.copilotchat.conversation

import com.citigroup.copilotchat.auth.CopilotAuth
import com.citigroup.copilotchat.auth.CopilotBinaryLocator
import com.citigroup.copilotchat.config.CopilotChatSettings
import com.citigroup.copilotchat.lsp.LspClient
import com.citigroup.copilotchat.mcp.ClientMcpManager
import com.citigroup.copilotchat.tools.ToolRouter
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/**
 * Manages LSP server lifecycle: binary discovery, startup, authentication,
 * proxy configuration, MCP setup, and tool registration.
 *
 * Extracted from [ConversationManager] to separate initialization concerns
 * from chat/tool-call handling.
 */
class LspSession(
    private val project: Project,
    private val lspClient: LspClient,
    private val scope: CoroutineScope,
    private val onServerRequest: suspend (method: String, id: Int, params: JsonObject) -> Unit,
    private val onMcpError: suspend (String) -> Unit,
) {

    private val log = Logger.getInstance(LspSession::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val initMutex = Mutex()

    var initialized = false
        private set

    lateinit var toolRouter: ToolRouter
        private set

    var clientMcpManager: ClientMcpManager? = null
        private set

    /**
     * Ensure the LSP server is started and the initialization handshake is complete.
     * Thread-safe — uses a mutex to prevent concurrent initialization.
     */
    suspend fun ensureInitialized() {
        if (initialized && lspClient.isRunning) return
        initMutex.withLock {
            if (initialized && lspClient.isRunning) return

        val settings = CopilotChatSettings.getInstance()
        settings.ensureDefaults()

        // Discover or use configured binary
        val binary = settings.binaryPath.ifBlank { null }
            ?: CopilotBinaryLocator.discover()
            ?: throw RuntimeException(
                "copilot-language-server not found. Install GitHub Copilot in a JetBrains IDE or set the path in settings."
            )

        // Start the LSP process — pass proxy env vars like the Python CLI does
        val lspEnv = mutableMapOf<String, String>()
        val proxyUrl = settings.proxyUrl
        if (proxyUrl.isNotBlank()) {
            lspEnv["HTTP_PROXY"] = proxyUrl
            lspEnv["HTTPS_PROXY"] = proxyUrl
        }
        lspClient.start(binary, lspEnv)

        // Set up tool router and server request handler
        toolRouter = ToolRouter(project)
        lspClient.serverRequestHandler = { method, id, params ->
            scope.launch { onServerRequest(method, id, params) }
        }

        // Read auth
        val auth = CopilotAuth.readAuth(settings.appsJsonPath.ifBlank { null })

        val rootUri = project.basePath?.let { "file://$it" } ?: "file:///tmp/copilot-workspace"
        val folderName = project.name

        // initialize
        val initParams = buildJsonObject {
            put("processId", ProcessHandle.current().pid().toInt())
            putJsonObject("capabilities") {
                putJsonObject("textDocumentSync") {
                    put("openClose", true)
                    put("change", 1)
                    put("save", true)
                }
                putJsonObject("workspace") {
                    put("workspaceFolders", true)
                }
            }
            put("rootUri", rootUri)
            putJsonArray("workspaceFolders") {
                addJsonObject {
                    put("uri", rootUri)
                    put("name", folderName)
                }
            }
            putJsonObject("clientInfo") {
                put("name", "copilot-chat-intellij")
                put("version", "1.0.0")
            }
            putJsonObject("initializationOptions") {
                putJsonObject("editorInfo") {
                    put("name", "JetBrains-IC")
                    put("version", "2025.2")
                }
                putJsonObject("editorPluginInfo") {
                    put("name", "copilot-intellij")
                    put("version", "1.420.0")
                }
                putJsonObject("editorConfiguration") {}
                putJsonObject("networkProxy") {
                    if (proxyUrl.isNotBlank()) {
                        put("url", proxyUrl)
                    }
                }
                put("githubAppId", auth.appId.ifBlank { "Iv1.b507a08c87ecfe98" })
            }
        }

        val initResp = lspClient.sendRequest("initialize", initParams)
        val serverInfo = initResp["result"]?.jsonObject?.get("serverInfo")?.jsonObject
        log.info("LSP server: ${serverInfo?.get("name")} v${serverInfo?.get("version")}")

        // initialized notification
        lspClient.sendNotification("initialized", JsonObject(emptyMap()))

        // setEditorInfo
        lspClient.sendRequest("setEditorInfo", buildJsonObject {
            putJsonObject("editorInfo") {
                put("name", "JetBrains-IC")
                put("version", "2025.2")
            }
            putJsonObject("editorPluginInfo") {
                put("name", "copilot-intellij")
                put("version", "1.420.0")
            }
            putJsonObject("editorConfiguration") {}
            putJsonObject("networkProxy") {
                if (proxyUrl.isNotBlank()) {
                    put("url", proxyUrl)
                }
            }
        })

        // checkStatus — verify we're authenticated before proceeding
        var statusResp = lspClient.sendRequest("checkStatus", JsonObject(emptyMap()))
        var status = statusResp["result"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
        log.info("Copilot auth status: $status")

        // If not signed in, try signInConfirm with the OAuth token from apps.json
        if (status != "OK" && status != "MaybeOk") {
            log.info("Not authenticated, attempting signInConfirm with token...")
            try {
                lspClient.sendRequest("signInConfirm", buildJsonObject {
                    put("userCode", auth.token)
                })
                // Re-check status after sign-in
                statusResp = lspClient.sendRequest("checkStatus", JsonObject(emptyMap()))
                status = statusResp["result"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
                log.info("Auth status after signInConfirm: $status")
            } catch (e: Exception) {
                log.warn("signInConfirm failed: ${e.message}")
            }
        }

        if (status != "OK" && status != "MaybeOk") {
            val user = statusResp["result"]?.jsonObject?.get("user")?.jsonPrimitive?.contentOrNull ?: "unknown"
            throw RuntimeException(
                "GitHub Copilot is not authenticated (status: $status, user: $user). " +
                "Please sign in via the GitHub Copilot plugin first."
            )
        }

        // Configure proxy via workspace/didChangeConfiguration (matches Python CLI's configure_proxy)
        if (proxyUrl.isNotBlank()) {
            val parsed = java.net.URI(proxyUrl)
            val httpSettings = buildJsonObject {
                put("proxyStrictSSL", false)

                val userInfo = parsed.userInfo
                if (userInfo != null) {
                    // Extract credentials and build Basic auth header
                    val authHeader = "Basic " + java.util.Base64.getEncoder()
                        .encodeToString(userInfo.toByteArray())
                    put("proxyAuthorization", authHeader)
                    // Send clean URL without credentials
                    val cleanUri = java.net.URI(
                        parsed.scheme, null, parsed.host, parsed.port,
                        parsed.path, parsed.query, parsed.fragment
                    )
                    put("proxy", cleanUri.toString())
                } else {
                    put("proxy", proxyUrl)
                }
            }
            lspClient.sendNotification("workspace/didChangeConfiguration", buildJsonObject {
                putJsonObject("settings") {
                    put("http", httpSettings)
                }
            })
            log.info("Proxy configured: ${httpSettings["proxy"]}")
        }

        // Wait briefly for feature flags (they arrive shortly after init)
        val flagsStart = System.currentTimeMillis()
        while (lspClient.featureFlags.isEmpty() && System.currentTimeMillis() - flagsStart < 3_000) {
            delay(100)
        }

        // MCP: route to server-side or client-side based on feature flags
        val mcpSettings = CopilotChatSettings.getInstance().mcpServers
        val enabledMcpServers = mcpSettings.filter { it.enabled }

        if (enabledMcpServers.isNotEmpty()) {
            if (lspClient.isServerMcpEnabled) {
                // Server-side MCP: send config via workspace/didChangeConfiguration
                log.info("MCP: using server-side (org allows mcp)")
                loadMcpConfigFromSettings()
            } else {
                // Client-side MCP: spawn processes locally
                log.info("MCP: using client-side (org blocks server mcp)")
                val manager = ClientMcpManager(proxyUrl = settings.proxyUrl)
                manager.addServers(enabledMcpServers)
                manager.startAll()
                clientMcpManager = manager

                // Surface MCP startup errors to the chat UI
                for (err in manager.startupErrors) {
                    onMcpError("Client MCP: $err")
                }
            }
        }

        // Register all tools once globally
        registerTools()

        initialized = true
        } // end initMutex.withLock
    }

    private suspend fun loadMcpConfigFromSettings() {
        val settings = CopilotChatSettings.getInstance()
        val servers = settings.mcpServers
        if (servers.isEmpty()) return

        val mcpConfig = mutableMapOf<String, Map<String, Any>>()
        for (entry in servers) {
            if (!entry.enabled) continue
            val serverConfig = mutableMapOf<String, Any>()
            if (entry.url.isNotBlank()) {
                serverConfig["url"] = entry.url
            } else {
                serverConfig["command"] = entry.command
                if (entry.args.isNotBlank()) {
                    serverConfig["args"] = entry.args.split(" ").filter { it.isNotBlank() }
                }
            }
            if (entry.env.isNotBlank()) {
                val envMap = mutableMapOf<String, String>()
                entry.env.lines().filter { "=" in it }.forEach { line ->
                    val (k, v) = line.split("=", limit = 2)
                    envMap[k.trim()] = v.trim()
                }
                if (envMap.isNotEmpty()) serverConfig["env"] = envMap
            }
            mcpConfig[entry.name] = serverConfig
        }

        if (mcpConfig.isNotEmpty()) {
            // Send directly — do NOT call configureMcp() which calls ensureInitialized()
            // and would deadlock since we're still inside ensureInitialized()
            sendMcpConfigNotification(mcpConfig)
        }
    }

    /**
     * Register all client-side tools with the language server.
     * Called once at init and again when MCP config changes.
     *
     * Per-agent tool isolation is enforced at the execution level:
     * ConversationManager rejects tool calls not in the agent's allowed set.
     */
    suspend fun registerTools() {
        val schemas = toolRouter.getToolSchemas().toMutableList()

        // Append client-side MCP tool schemas (filtering disabled tools)
        val settings = CopilotChatSettings.getInstance()
        val mcpSchemas = clientMcpManager?.getToolSchemas { name ->
            settings.isToolEnabled(name)
        } ?: emptyList()
        schemas.addAll(mcpSchemas)

        // Suppress browser_record when Playwright MCP tools are available
        val hasMcpBrowserTools = mcpSchemas.any { schema ->
            val name = try {
                json.parseToJsonElement(schema).jsonObject["name"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) { null }
            name != null && (name.startsWith("browser_") || name.contains("playwright"))
        }
        if (hasMcpBrowserTools) {
            schemas.removeAll { schema ->
                try {
                    json.parseToJsonElement(schema).jsonObject["name"]?.jsonPrimitive?.contentOrNull == "browser_record"
                } catch (_: Exception) { false }
            }
        }

        if (schemas.isEmpty()) return

        val params = buildJsonObject {
            putJsonArray("tools") {
                for (schema in schemas) {
                    add(json.parseToJsonElement(schema))
                }
            }
        }
        val resp = lspClient.sendRequest("conversation/registerTools", params)
        val toolNames = schemas.mapNotNull { schema ->
            try { json.parseToJsonElement(schema).jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            catch (_: Exception) { null }
        }
        val mcpNote = if (mcpSchemas.isNotEmpty()) " + ${mcpSchemas.size} client-mcp" else ""
        log.info("Registered ${schemas.size} tools: ${toolNames.joinToString(", ")}$mcpNote")
    }

    /**
     * Send MCP server configuration to the language server.
     * Port of client.py configure_mcp().
     *
     * If client-side MCP is active (org blocks server-side), this restarts
     * the ClientMcpManager with the updated config and re-registers tools.
     */
    suspend fun configureMcp(mcpConfig: Map<String, Map<String, Any>>) {
        ensureInitialized()

        if (lspClient.isServerMcpEnabled) {
            // Server-side MCP: send config notification
            if (mcpConfig.isNotEmpty()) {
                sendMcpConfigNotification(mcpConfig)
            }
        } else {
            // Client-side MCP: restart manager with new config
            clientMcpManager?.stopAll()
            val settings = CopilotChatSettings.getInstance()
            val enabledMcpServers = settings.mcpServers.filter { it.enabled }
            if (enabledMcpServers.isNotEmpty()) {
                val manager = ClientMcpManager(proxyUrl = settings.proxyUrl)
                manager.addServers(enabledMcpServers)
                manager.startAll()
                clientMcpManager = manager
            } else {
                clientMcpManager = null
            }
            // Re-register tools with updated MCP schemas
            registerTools()
        }
    }

    /**
     * Send MCP config notification to the language server.
     * Separated from configureMcp() so it can be called during initialization
     * without triggering a recursive ensureInitialized() deadlock.
     */
    private suspend fun sendMcpConfigNotification(mcpConfig: Map<String, Map<String, Any>>) {
        val configObj = buildJsonObject {
            for ((serverName, serverConfig) in mcpConfig) {
                putJsonObject(serverName) {
                    for ((key, value) in serverConfig) {
                        when (value) {
                            is String -> put(key, value)
                            is List<*> -> putJsonArray(key) {
                                value.filterIsInstance<String>().forEach { add(it) }
                            }
                            is Map<*, *> -> putJsonObject(key) {
                                @Suppress("UNCHECKED_CAST")
                                (value as Map<String, String>).forEach { (k, v) -> put(k, v) }
                            }
                            else -> put(key, value.toString())
                        }
                    }
                }
            }
        }

        lspClient.sendNotification("workspace/didChangeConfiguration", buildJsonObject {
            putJsonObject("settings") {
                putJsonObject("github") {
                    putJsonObject("copilot") {
                        put("mcp", configObj.toString())
                    }
                }
            }
        })
        log.info("Sent MCP config with ${mcpConfig.size} server(s): ${mcpConfig.keys.joinToString()}")
    }

    /** Reset session state. Called by ConversationManager.newConversation(). */
    fun reset() {
        clientMcpManager?.stopAll()
        clientMcpManager = null
        initialized = false
    }

    /** Stop MCP and clean up. */
    fun dispose() {
        clientMcpManager?.stopAll()
        clientMcpManager = null
    }
}
