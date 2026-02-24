package com.citigroup.copilotchat.conversation

import com.citigroup.copilotchat.auth.CopilotAuth
import com.citigroup.copilotchat.auth.CopilotBinaryLocator
import com.citigroup.copilotchat.config.CopilotChatSettings
import com.citigroup.copilotchat.lsp.CachedAuth
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
 *
 * @param cachedAuth Shared auth state; populated on first init, reused on subsequent inits.
 * @param toolFilter If non-empty, only register tools whose names are in this set.
 */
class LspSession(
    private val project: Project,
    private val lspClient: LspClient,
    private val scope: CoroutineScope,
    private val onServerRequest: suspend (method: String, id: Int, params: JsonObject) -> Unit,
    private val onMcpError: suspend (String) -> Unit,
    val cachedAuth: CachedAuth = CachedAuth(),
    private val toolFilter: Set<String> = emptySet(),
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

        // Use cached binary/env if available, otherwise discover
        val binary: String
        val lspEnv: Map<String, String>
        val proxyUrl: String

        if (cachedAuth.isResolved) {
            binary = cachedAuth.binaryPath!!
            lspEnv = cachedAuth.lspEnv
            proxyUrl = cachedAuth.proxyUrl
            log.info("LspSession[${lspClient.clientId}]: using cached binary + auth")
        } else {
            binary = settings.binaryPath.ifBlank { null }
                ?: CopilotBinaryLocator.discover()
                ?: throw RuntimeException(
                    "copilot-language-server not found. Install GitHub Copilot in a JetBrains IDE or set the path in settings."
                )
            proxyUrl = settings.proxyUrl
            val env = mutableMapOf<String, String>()
            if (proxyUrl.isNotBlank()) {
                env["HTTP_PROXY"] = proxyUrl
                env["HTTPS_PROXY"] = proxyUrl
            }
            lspEnv = env
        }

        // Start the LSP process
        lspClient.start(binary, lspEnv)

        // Set up tool router and server request handler
        toolRouter = ToolRouter(project)
        lspClient.serverRequestHandler = { method, id, params ->
            scope.launch { onServerRequest(method, id, params) }
        }

        // Read auth — use cached token if available
        val auth = if (cachedAuth.isResolved) {
            CopilotAuth.AuthInfo(cachedAuth.authToken!!, cachedAuth.authUser, cachedAuth.appId)
        } else {
            CopilotAuth.readAuth(settings.appsJsonPath.ifBlank { null })
        }

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

        // Wait briefly for feature flags — skip if cached
        if (cachedAuth.featureFlags.isNotEmpty()) {
            lspClient.featureFlags = cachedAuth.featureFlags
            log.info("LspSession[${lspClient.clientId}]: using cached feature flags")
        } else {
            val flagsStart = System.currentTimeMillis()
            while (lspClient.featureFlags.isEmpty() && System.currentTimeMillis() - flagsStart < 3_000) {
                delay(100)
            }
        }

        // Populate CachedAuth for subsequent pool clients
        if (!cachedAuth.isResolved) {
            cachedAuth.binaryPath = binary
            cachedAuth.lspEnv = lspEnv
            cachedAuth.authToken = auth.token
            cachedAuth.authUser = auth.user
            cachedAuth.appId = auth.appId
            cachedAuth.proxyUrl = proxyUrl
            cachedAuth.featureFlags = lspClient.featureFlags
            cachedAuth.isServerMcpEnabled = lspClient.isServerMcpEnabled
            log.info("LspSession: cached auth state populated")
        }

        // MCP: always use client-side MCP.
        //
        // Server-side MCP (when isServerMcpEnabled=true) routes tool calls through
        // the Copilot language server's content policy, which rejects browser
        // automation tools like Playwright with "sorry, can't assist with that".
        // Client-side MCP spawns MCP servers locally and handles tool calls
        // directly via invokeClientTool — no content policy filtering.
        val mcpSettings = CopilotChatSettings.getInstance().mcpServers
        val enabledMcpServers = mcpSettings.filter { it.enabled }

        if (enabledMcpServers.isNotEmpty()) {
            log.info("MCP: using client-side (${enabledMcpServers.size} server(s))")
            val manager = ClientMcpManager(proxyUrl = settings.proxyUrl)
            manager.addServers(enabledMcpServers)
            manager.startAll()
            clientMcpManager = manager

            // Surface MCP startup errors to the chat UI
            for (err in manager.startupErrors) {
                onMcpError("Client MCP: $err")
            }
        }

        // Register all tools once globally
        registerTools()

        initialized = true
        } // end initMutex.withLock
    }

    /**
     * Register client-side tools with the language server.
     * If [toolFilter] is non-empty, only tools whose names match the filter are registered.
     * The "ide" shorthand expands to all "ide_*" tools.
     *
     * Two-phase registration: built-in tools first, then MCP tools separately.
     * The server's content policy may reject MCP tools (e.g. Playwright) when
     * bundled with built-in tools. Registering them in a follow-up call lets
     * the built-in tools succeed regardless, and the MCP tools get appended.
     */
    suspend fun registerTools() {
        val settings = CopilotChatSettings.getInstance()

        // Collect MCP schemas (needed early for browser_record suppression)
        val mcpSchemas = clientMcpManager?.getToolSchemas { name ->
            settings.isToolEnabled(name)
        } ?: emptyList()

        val hasMcpBrowserTools = mcpSchemas.any { schema ->
            val name = try {
                json.parseToJsonElement(schema).jsonObject["name"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) { null }
            name != null && (name.startsWith("browser_") || name.contains("playwright"))
        }

        // ---- Phase 1: register built-in tools (PSI, ide-index, BuiltInTools) ----
        val builtInSchemas = toolRouter.getToolSchemas().toMutableList()

        // Suppress browser_record when Playwright MCP tools are available
        if (hasMcpBrowserTools) {
            builtInSchemas.removeAll { schema ->
                try {
                    json.parseToJsonElement(schema).jsonObject["name"]?.jsonPrimitive?.contentOrNull == "browser_record"
                } catch (_: Exception) { false }
            }
        }

        // Apply tool filter
        if (toolFilter.isNotEmpty()) {
            builtInSchemas.retainAll { schema ->
                val name = try {
                    json.parseToJsonElement(schema).jsonObject["name"]?.jsonPrimitive?.contentOrNull
                } catch (_: Exception) { null }
                name != null && isToolInFilter(name, toolFilter)
            }
        }

        if (builtInSchemas.isNotEmpty()) {
            val params = buildJsonObject {
                putJsonArray("tools") {
                    for (schema in builtInSchemas) {
                        add(json.parseToJsonElement(schema))
                    }
                }
            }
            lspClient.sendRequest("conversation/registerTools", params)
            val names = builtInSchemas.mapNotNull { schema ->
                try { json.parseToJsonElement(schema).jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                catch (_: Exception) { null }
            }
            log.info("Registered ${builtInSchemas.size} built-in tools: ${names.joinToString(", ")}")
        }

        // ---- Phase 2: register MCP tools separately ----
        if (mcpSchemas.isNotEmpty()) {
            var filteredMcp = mcpSchemas
            if (toolFilter.isNotEmpty()) {
                filteredMcp = mcpSchemas.filter { schema ->
                    val name = try {
                        json.parseToJsonElement(schema).jsonObject["name"]?.jsonPrimitive?.contentOrNull
                    } catch (_: Exception) { null }
                    name != null && isToolInFilter(name, toolFilter)
                }
            }

            if (filteredMcp.isNotEmpty()) {
                val mcpParams = buildJsonObject {
                    putJsonArray("tools") {
                        for (schema in filteredMcp) {
                            add(json.parseToJsonElement(schema))
                        }
                    }
                }
                val mcpResp = lspClient.sendRequest("conversation/registerTools", mcpParams)
                val hasError = mcpResp["error"] != null
                val mcpNames = filteredMcp.mapNotNull { schema ->
                    try { json.parseToJsonElement(schema).jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                    catch (_: Exception) { null }
                }
                if (hasError) {
                    log.warn("MCP tool registration rejected by server: ${mcpResp["error"]}. " +
                        "MCP tools (${mcpNames.joinToString(", ")}) will still work via client-side execution.")
                } else {
                    log.info("Registered ${filteredMcp.size} MCP tools: ${mcpNames.joinToString(", ")}")
                }
            }
        }

        if (toolFilter.isNotEmpty()) {
            val total = builtInSchemas.size + mcpSchemas.size
            log.info("LspSession[${lspClient.clientId}]: tool filter applied — $total tools retained from filter=$toolFilter")
        }
    }

    /** Check if [toolName] is in [filter], expanding the "ide" shorthand for "ide_*" tools. */
    private fun isToolInFilter(toolName: String, filter: Set<String>): Boolean =
        toolName in filter || (toolName.startsWith("ide_") && "ide" in filter)

    /**
     * Send MCP server configuration to the language server.
     * Port of client.py configure_mcp().
     *
     * Always uses client-side MCP to avoid server content policy blocking.
     * Restarts the ClientMcpManager with the updated config and re-registers tools.
     */
    suspend fun configureMcp(mcpConfig: Map<String, Map<String, Any>>) {
        ensureInitialized()

        // Always use client-side MCP: restart manager with new config
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
