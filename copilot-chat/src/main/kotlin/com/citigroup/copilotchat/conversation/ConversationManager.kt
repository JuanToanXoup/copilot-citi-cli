package com.citigroup.copilotchat.conversation

import com.citigroup.copilotchat.agent.AgentService
import com.citigroup.copilotchat.auth.CopilotAuth
import com.citigroup.copilotchat.auth.CopilotBinaryLocator
import com.citigroup.copilotchat.config.CopilotChatSettings
import com.citigroup.copilotchat.lsp.*
import com.citigroup.copilotchat.mcp.ClientMcpManager
import com.citigroup.copilotchat.rag.RagQueryService
import com.citigroup.copilotchat.tools.ToolRouter
import com.citigroup.copilotchat.workingset.WorkingSetService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.*

/**
 * Project-level service managing conversations with the Copilot language server.
 * Port of client.py's conversation_create, conversation_turn, _collect_chat_reply.
 */
@Service(Service.Level.PROJECT)
class ConversationManager(private val project: Project) : Disposable {

    private val log = Logger.getInstance(ConversationManager::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ChatEvent> = _events

    var state = ConversationState()
        private set

    private var initialized = false
    private var currentJob: Job? = null
    private var currentWorkDoneToken: String? = null
    private lateinit var toolRouter: ToolRouter
    private var clientMcpManager: ClientMcpManager? = null
    private val initMutex = kotlinx.coroutines.sync.Mutex()

    /** Per-conversation workspace root overrides (for worktree-isolated subagents). */
    private val workspaceOverrides = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun registerWorkspaceOverride(conversationId: String, workspaceRoot: String) {
        workspaceOverrides[conversationId] = workspaceRoot
    }

    fun removeWorkspaceOverride(conversationId: String) {
        workspaceOverrides.remove(conversationId)
    }

    private val lspClient: LspClient get() = LspClient.getInstance(project)

    companion object {
        fun getInstance(project: Project): ConversationManager =
            project.getService(ConversationManager::class.java)
    }

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
            scope.launch { handleServerRequest(method, id, params) }
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
                    _events.emit(ChatEvent.Error("Client MCP: $err"))
                }
            }
        }

        // Register tools (including client-side MCP tools if any)
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
     * Re-register tools with the language server (e.g., after toggling tools on/off).
     */
    fun reregisterTools() {
        if (!initialized || !lspClient.isRunning) return
        scope.launch {
            try {
                registerTools()
            } catch (e: Exception) {
                log.warn("Failed to re-register tools: ${e.message}", e)
            }
        }
    }

    private suspend fun registerTools() {
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
        log.info("Registered ${schemas.size} client tools: ${toolNames.joinToString(", ")}$mcpNote")
        log.info("registerTools response: $resp")
    }

    /**
     * Send a message — either creating a new conversation or continuing an existing one.
     */
    fun sendMessage(text: String, model: String? = null, agentMode: Boolean? = null) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                ensureInitialized()

                val useModel = model ?: state.model
                val useAgent = agentMode ?: state.agentMode

                // RAG: retrieve relevant code context and prepend to message for LSP
                val settings = CopilotChatSettings.getInstance()
                val lspText = if (settings.ragEnabled) {
                    log.info("RAG is enabled, retrieving context for query")
                    val ragContext = try {
                        withContext(Dispatchers.IO) {
                            RagQueryService.getInstance(project).retrieve(text, settings.ragTopK)
                        }
                    } catch (e: Exception) {
                        log.warn("RAG retrieval failed, continuing without context: ${e.message}")
                        ""
                    }
                    if (ragContext.isNotEmpty()) {
                        log.info("RAG: injected ${ragContext.length} chars of context")
                        "$ragContext\n\n$text"
                    } else {
                        log.info("RAG: no context retrieved")
                        text
                    }
                } else {
                    log.info("RAG is disabled, sending message without context")
                    text
                }

                state = state.copy(isStreaming = true)
                state.messages.add(ChatMessage(ChatMessage.Role.USER, text))

                val workDoneToken = "copilot-chat-${UUID.randomUUID().toString().take(8)}"
                currentWorkDoneToken = workDoneToken
                val replyParts = mutableListOf<String>()

                // Register progress listener
                lspClient.registerProgressListener(workDoneToken) { value ->
                    scope.launch { handleProgress(value, replyParts) }
                }

                try {
                    if (state.conversationId == null) {
                        // Create new conversation
                        val rootUri = project.basePath?.let { "file://$it" } ?: "file:///tmp"
                        val params = buildJsonObject {
                            put("workDoneToken", workDoneToken)
                            putJsonArray("turns") {
                                addJsonObject { put("request", lspText) }
                            }
                            putJsonObject("capabilities") {
                                put("allSkills", useAgent)
                            }
                            put("source", "panel")
                            if (useAgent) {
                                put("chatMode", "Agent")
                                put("needToolCallConfirmation", true)
                            }
                            if (useModel.isNotBlank()) put("model", useModel)
                            put("workspaceFolder", rootUri)
                            putJsonArray("workspaceFolders") {
                                addJsonObject {
                                    put("uri", rootUri)
                                    put("name", project.name)
                                }
                            }
                        }
                        val resp = lspClient.sendRequest("conversation/create", params, timeoutMs = 300_000)
                        val result = resp["result"]
                        val convId = when (result) {
                            is JsonArray -> result.firstOrNull()?.jsonObject?.get("conversationId")?.jsonPrimitive?.contentOrNull
                            is JsonObject -> result["conversationId"]?.jsonPrimitive?.contentOrNull
                            else -> null
                        }
                        state = state.copy(conversationId = convId)
                    } else {
                        // Follow-up turn
                        val rootUri = project.basePath?.let { "file://$it" } ?: "file:///tmp"
                        val params = buildJsonObject {
                            put("workDoneToken", workDoneToken)
                            put("conversationId", state.conversationId!!)
                            put("message", lspText)
                            put("source", "panel")
                            if (useAgent) {
                                put("chatMode", "Agent")
                                put("needToolCallConfirmation", true)
                            }
                            if (useModel.isNotBlank()) put("model", useModel)
                            put("workspaceFolder", rootUri)
                            putJsonArray("workspaceFolders") {
                                addJsonObject {
                                    put("uri", rootUri)
                                    put("name", project.name)
                                }
                            }
                        }
                        lspClient.sendRequest("conversation/turn", params, timeoutMs = 300_000)
                    }

                    // Wait for done (the progress listener handles streaming)
                    // The request returns when the conversation is created, but progress continues
                    val startTime = System.currentTimeMillis()
                    val timeout = if (useAgent) 300_000L else 60_000L
                    while (state.isStreaming && System.currentTimeMillis() - startTime < timeout) {
                        delay(100)
                    }
                } finally {
                    lspClient.removeProgressListener(workDoneToken)
                }

                currentWorkDoneToken = null
                val fullReply = replyParts.joinToString("")
                if (fullReply.isNotEmpty()) {
                    state.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, fullReply))
                }
                state = state.copy(isStreaming = false)
                _events.emit(ChatEvent.Done(fullReply))

            } catch (e: CancellationException) {
                state = state.copy(isStreaming = false)
                throw e
            } catch (e: Exception) {
                log.error("Error in sendMessage", e)
                state = state.copy(isStreaming = false)
                _events.emit(ChatEvent.Error(e.message ?: "Unknown error"))
            }
        }
    }

    private suspend fun handleProgress(value: JsonObject, replyParts: MutableList<String>) {
        val kind = value["kind"]?.jsonPrimitive?.contentOrNull

        if (kind == "end") {
            state = state.copy(isStreaming = false)
            return
        }

        // Reply/delta text
        val reply = value["reply"]?.jsonPrimitive?.contentOrNull
        if (reply != null) {
            replyParts.add(reply)
            _events.emit(ChatEvent.Delta(reply))
        }

        val delta = value["delta"]?.jsonPrimitive?.contentOrNull
        if (delta != null) {
            replyParts.add(delta)
            _events.emit(ChatEvent.Delta(delta))
        }

        val message = value["message"]?.jsonPrimitive?.contentOrNull
        if (message != null && kind != "begin") {
            replyParts.add(message)
            _events.emit(ChatEvent.Delta(message))
        }

        // Agent rounds — contain reply text AND tool calls (including MCP tools)
        val rounds = value["editAgentRounds"]?.jsonArray
        rounds?.forEach { roundEl ->
            val round = roundEl.jsonObject
            val roundReply = round["reply"]?.jsonPrimitive?.contentOrNull ?: ""
            if (roundReply.isNotEmpty()) {
                replyParts.add(roundReply)
            }

            // Extract tool calls from the round (server-side tools like MCP)
            // Skip client-side MCP tools — they already emit events in handleServerRequest()
            val toolCalls = round["toolCalls"]?.jsonArray
            toolCalls?.forEach { toolCallEl ->
                val tc = toolCallEl.jsonObject
                val name = tc["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach

                // Client-side MCP tools already emitted ToolCall/ToolResult
                // in handleServerRequest() — don't duplicate them
                if (clientMcpManager?.isMcpTool(name) == true) return@forEach

                val status = tc["status"]?.jsonPrimitive?.contentOrNull ?: ""
                val input = tc["input"]?.jsonObject ?: JsonObject(emptyMap())
                val progressMessage = tc["progressMessage"]?.jsonPrimitive?.contentOrNull
                val error = tc["error"]?.jsonPrimitive?.contentOrNull

                // Emit tool call event
                _events.emit(ChatEvent.ToolCall(name, input))

                // Emit result if the tool call is completed
                val resultData = tc["result"]?.jsonArray
                val resultText = if (error != null) {
                    "Error: $error"
                } else if (resultData != null && resultData.isNotEmpty()) {
                    resultData.firstOrNull()?.jsonObject
                        ?.get("content")?.jsonPrimitive?.contentOrNull
                        ?: resultData.toString().take(200)
                } else {
                    progressMessage ?: status
                }

                if (status == "completed" || status == "error" || error != null) {
                    _events.emit(ChatEvent.ToolResult(name, resultText))
                }
            }

            _events.emit(ChatEvent.AgentRound(roundReply, round))
        }
    }

    private suspend fun handleServerRequest(method: String, id: Int, params: JsonObject) {
        // Route Agent tab conversations to AgentService
        val callConvId = params["conversationId"]?.jsonPrimitive?.contentOrNull
        val agentService = try { AgentService.getInstance(project) } catch (_: Exception) { null }
        if (agentService != null && agentService.ownsConversation(callConvId)) {
            when (method) {
                "conversation/invokeClientToolConfirmation" -> {
                    val result = buildJsonArray {
                        addJsonObject { put("result", "accept") }
                        add(JsonNull)
                    }
                    lspClient.sendResponse(id, result)
                }
                "conversation/invokeClientTool" -> {
                    val toolName = params["name"]?.jsonPrimitive?.contentOrNull
                        ?: params["toolName"]?.jsonPrimitive?.contentOrNull
                        ?: "unknown"
                    val toolInput = params["input"]?.jsonObject
                        ?: params["arguments"]?.jsonObject
                        ?: JsonObject(emptyMap())
                    agentService.handleToolCall(id, toolName, toolInput, callConvId)
                }
                else -> {
                    // Fall through to default handlers below
                    handleDefaultServerRequest(method, id, params)
                }
            }
            return
        }

        when (method) {
            "conversation/invokeClientToolConfirmation" -> {
                val result = buildJsonArray {
                    addJsonObject { put("result", "accept") }
                    add(JsonNull)
                }
                lspClient.sendResponse(id, result)
            }
            "conversation/invokeClientTool" -> {
                val toolName = params["name"]?.jsonPrimitive?.contentOrNull
                    ?: params["toolName"]?.jsonPrimitive?.contentOrNull
                    ?: "unknown"
                val toolInput = params["input"]?.jsonObject
                    ?: params["arguments"]?.jsonObject
                    ?: JsonObject(emptyMap())

                // Only emit ChatEvents if this tool call belongs to the Chat tab's conversation.
                // Worker sessions have their own conversationIds and should not leak into Chat.
                val isChatConversation = callConvId == null || callConvId == state.conversationId

                if (isChatConversation) {
                    _events.emit(ChatEvent.ToolCall(toolName, toolInput))
                }

                // If AgentService is active, redirect agent-specific tools that
                // slipped past ownsConversation() (race: conversation ID mismatch).
                val agentSvc = try { AgentService.getInstance(project) } catch (_: Exception) { null }
                val agentOnlyTools = setOf("delegate_task", "create_team", "send_message", "delete_team")
                if (agentSvc != null && agentSvc.isActive() && toolName in agentOnlyTools) {
                    log.info("Re-routing agent tool '$toolName' (conv=$callConvId) to AgentService")
                    agentSvc.handleToolCall(id, toolName, toolInput, callConvId)
                    return
                }

                // Hard-enforce per-agent tool restrictions for subagent conversations
                if (agentSvc != null && !agentSvc.isToolAllowedForConversation(callConvId, toolName)) {
                    val errorResult = buildJsonArray {
                        addJsonObject {
                            putJsonArray("content") {
                                addJsonObject { put("value", "Tool '$toolName' is not available for this agent type.") }
                            }
                            put("status", "error")
                        }
                        add(JsonNull)
                    }
                    lspClient.sendResponse(id, errorResult)
                    return
                }

                // Check client-side MCP tools first
                val mcpManager = clientMcpManager
                if (mcpManager != null && mcpManager.isMcpTool(toolName)) {
                    val resultText = mcpManager.callTool(toolName, toolInput)
                    val result = buildJsonArray {
                        addJsonObject {
                            putJsonArray("content") {
                                addJsonObject { put("value", resultText) }
                            }
                            put("status", "success")
                        }
                        add(JsonNull)
                    }
                    lspClient.sendResponse(id, result)
                    if (isChatConversation) {
                        _events.emit(ChatEvent.ToolResult(toolName, resultText.take(200)))
                    }
                } else {
                    val wsOverride = if (callConvId != null) workspaceOverrides[callConvId] else null
                    val result = toolRouter.executeTool(toolName, toolInput, wsOverride)
                    lspClient.sendResponse(id, result)

                    if (isChatConversation) {
                        val outputText = result.jsonArray.firstOrNull()?.jsonObject
                            ?.get("content")?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("value")?.jsonPrimitive?.contentOrNull ?: ""
                        _events.emit(ChatEvent.ToolResult(toolName, outputText.take(200)))
                    }
                }
            }
            "copilot/watchedFiles" -> {
                lspClient.sendResponse(id, buildJsonObject {
                    putJsonArray("watchedFiles") {}
                })
            }
            "window/showMessageRequest" -> {
                lspClient.sendResponse(id, JsonNull)
            }
            else -> {
                log.debug("Unhandled server request: $method")
                lspClient.sendResponse(id, JsonNull)
            }
        }
    }

    private suspend fun handleDefaultServerRequest(method: String, id: Int, params: JsonObject) {
        when (method) {
            "copilot/watchedFiles" -> {
                lspClient.sendResponse(id, buildJsonObject {
                    putJsonArray("watchedFiles") {}
                })
            }
            "window/showMessageRequest" -> {
                lspClient.sendResponse(id, JsonNull)
            }
            else -> {
                log.debug("Unhandled server request: $method")
                lspClient.sendResponse(id, JsonNull)
            }
        }
    }

    /** Cancel the current streaming response. */
    fun cancel() {
        // Tell the LSP server to stop generating via workDoneProgress cancel
        val token = currentWorkDoneToken
        if (token != null && lspClient.isRunning) {
            lspClient.sendNotification(
                "window/workDoneProgress/cancel",
                buildJsonObject { put("token", token) }
            )
            lspClient.removeProgressListener(token)
            currentWorkDoneToken = null
        }

        currentJob?.cancel()
        currentJob = null
        state = state.copy(isStreaming = false)

        // Emit Done so the UI resets (send/stop button, input field, etc.)
        scope.launch { _events.emit(ChatEvent.Done("")) }
    }

    /** Start a fresh conversation. */
    fun newConversation() {
        cancel()
        clientMcpManager?.stopAll()
        clientMcpManager = null
        initialized = false
        state = ConversationState(model = state.model, agentMode = state.agentMode)
        WorkingSetService.getInstance(project).clear()
    }

    /** List available models from the server. */
    suspend fun listModels(): List<JsonObject> {
        ensureInitialized()
        val resp = lspClient.sendRequest("copilot/models", JsonObject(emptyMap()))
        val models = resp["result"]?.jsonArray ?: return emptyList()
        return models.mapNotNull { it as? JsonObject }
    }

    fun updateModel(model: String) {
        state = state.copy(model = model)
    }

    fun updateAgentMode(enabled: Boolean) {
        state = state.copy(agentMode = enabled)
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

    override fun dispose() {
        cancel()
        clientMcpManager?.stopAll()
        clientMcpManager = null
        scope.cancel()
    }
}
