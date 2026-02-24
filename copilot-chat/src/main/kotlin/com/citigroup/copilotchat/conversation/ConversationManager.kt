package com.citigroup.copilotchat.conversation

import com.citigroup.copilotchat.agent.AgentService
import com.citigroup.copilotchat.config.CopilotChatSettings
import com.citigroup.copilotchat.lsp.*
import com.citigroup.copilotchat.rag.RagQueryService
import com.citigroup.copilotchat.workingset.WorkingSetService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.*
import java.util.*

/**
 * Project-level service managing conversations with the Copilot language server.
 * LSP lifecycle and tool registration are delegated to [LspSession].
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

    private var currentJob: Job? = null
    private var currentWorkDoneToken: String? = null

    /** Per-conversation workspace root overrides (for worktree-isolated subagents). */
    private val workspaceOverrides = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun registerWorkspaceOverride(conversationId: String, workspaceRoot: String) {
        workspaceOverrides[conversationId] = workspaceRoot
    }

    fun removeWorkspaceOverride(conversationId: String) {
        workspaceOverrides.remove(conversationId)
    }

    private val lspClient: LspClient get() = LspClient.getInstance(project)

    private val lspSession by lazy {
        LspSession(
            project = project,
            lspClient = lspClient,
            scope = scope,
            onServerRequest = { method, id, params -> handleServerRequest(method, id, params) },
            onMcpError = { msg -> _events.emit(ChatEvent.Error(msg)) },
        )
    }

    companion object {
        fun getInstance(project: Project): ConversationManager =
            project.getService(ConversationManager::class.java)
    }

    /** Delegate to [LspSession.ensureInitialized]. */
    suspend fun ensureInitialized() = lspSession.ensureInitialized()

    /** Delegate to [LspSession.reregisterTools]. */
    fun reregisterTools() = lspSession.reregisterTools()

    /** Delegate to [LspSession.configureMcp]. */
    suspend fun configureMcp(mcpConfig: Map<String, Map<String, Any>>) = lspSession.configureMcp(mcpConfig)

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
                if (lspSession.clientMcpManager?.isMcpTool(name) == true) return@forEach

                val status = tc["status"]?.jsonPrimitive?.contentOrNull ?: ""
                val input = tc["input"]?.jsonObject ?: JsonObject(emptyMap())

                // Emit tool call event
                _events.emit(ChatEvent.ToolCall(name, input))

                // Emit result if the tool call is completed
                val resultData = tc["result"]?.jsonArray
                val error = tc["error"]?.jsonPrimitive?.contentOrNull
                val progressMessage = tc["progressMessage"]?.jsonPrimitive?.contentOrNull
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
                val mcpManager = lspSession.clientMcpManager
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
                    val result = lspSession.toolRouter.executeTool(toolName, toolInput, wsOverride)
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
        lspSession.reset()
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

    override fun dispose() {
        cancel()
        lspSession.dispose()
        scope.cancel()
    }
}
