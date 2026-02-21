package com.citigroup.copilotchat.agent

import com.citigroup.copilotchat.conversation.ConversationManager
import com.citigroup.copilotchat.lsp.LspClient
import com.citigroup.copilotchat.tools.ToolRouter
import com.citigroup.copilotchat.orchestrator.WorkerSession
import com.citigroup.copilotchat.orchestrator.WorkerEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.*
import java.util.*
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Core service for the Agent tab. Manages the lead agent conversation,
 * delegates tasks to subagents via WorkerSession, and routes tool calls.
 *
 * Data flow:
 * 1. User sends message -> lead agent conversation created/continued
 * 2. Lead model calls delegate_task -> subagent spawned with scoped tools
 * 3. Subagent completes -> result returned to lead conversation
 * 4. Lead model continues with the result
 */
@Service(Service.Level.PROJECT)
class AgentService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(AgentService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AgentEvent> = _events

    private val lspClient: LspClient get() = LspClient.getInstance()
    private val conversationManager: ConversationManager get() = ConversationManager.getInstance(project)

    private var leadConversationId: String? = null
    var isStreaming: Boolean = false
        private set

    private var agents: List<AgentDefinition> = emptyList()
    private val activeSubagents = ConcurrentHashMap<String, WorkerSession>()

    private var currentJob: Job? = null
    private var currentWorkDoneToken: String? = null
    private lateinit var toolRouter: ToolRouter

    companion object {
        fun getInstance(project: Project): AgentService =
            project.getService(AgentService::class.java)
    }

    /**
     * Send a message to the lead agent. Creates a new conversation on first call,
     * continues on subsequent calls.
     */
    fun sendMessage(text: String, model: String? = null) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                conversationManager.ensureInitialized()
                toolRouter = ToolRouter(project)

                // Load agent definitions
                agents = AgentRegistry.loadAll(project.basePath)

                // Register lead agent tools (standard + delegate_task + team tools)
                registerLeadAgentTools()

                isStreaming = true
                val useModel = model ?: "gpt-4.1"
                val workDoneToken = "agent-lead-${UUID.randomUUID().toString().take(8)}"
                currentWorkDoneToken = workDoneToken
                val replyParts = Collections.synchronizedList(mutableListOf<String>())

                lspClient.registerProgressListener(workDoneToken) { value ->
                    scope.launch { handleLeadProgress(value, replyParts) }
                }

                try {
                    val rootUri = project.basePath?.let { "file://$it" } ?: "file:///tmp"

                    if (leadConversationId == null) {
                        val params = buildJsonObject {
                            put("workDoneToken", workDoneToken)
                            putJsonArray("turns") {
                                addJsonObject { put("request", text) }
                            }
                            putJsonObject("capabilities") {
                                put("allSkills", true)
                            }
                            put("source", "panel")
                            put("chatMode", "Agent")
                            put("needToolCallConfirmation", true)
                            if (useModel.isNotBlank()) put("model", useModel)
                            put("workspaceFolder", rootUri)
                            putJsonArray("workspaceFolders") {
                                addJsonObject {
                                    put("uri", rootUri)
                                    put("name", project.name)
                                }
                            }
                        }

                        log.info("AgentService: creating lead conversation")
                        val resp = lspClient.sendRequest("conversation/create", params, timeoutMs = 300_000)
                        val result = resp["result"]
                        leadConversationId = when (result) {
                            is JsonArray -> result.firstOrNull()?.jsonObject?.get("conversationId")?.jsonPrimitive?.contentOrNull
                            is JsonObject -> result["conversationId"]?.jsonPrimitive?.contentOrNull
                            else -> null
                        }
                        log.info("AgentService: lead conversationId=$leadConversationId")
                    } else {
                        val params = buildJsonObject {
                            put("workDoneToken", workDoneToken)
                            put("conversationId", leadConversationId!!)
                            put("message", text)
                            put("source", "panel")
                            put("chatMode", "Agent")
                            put("needToolCallConfirmation", true)
                            if (useModel.isNotBlank()) put("model", useModel)
                            put("workspaceFolder", rootUri)
                            putJsonArray("workspaceFolders") {
                                addJsonObject {
                                    put("uri", rootUri)
                                    put("name", project.name)
                                }
                            }
                        }

                        log.info("AgentService: continuing lead conversation $leadConversationId")
                        lspClient.sendRequest("conversation/turn", params, timeoutMs = 300_000)
                    }

                    // Wait for streaming to complete
                    val startTime = System.currentTimeMillis()
                    while (isStreaming && System.currentTimeMillis() - startTime < 300_000) {
                        delay(100)
                    }
                } finally {
                    lspClient.removeProgressListener(workDoneToken)
                }

                currentWorkDoneToken = null
                isStreaming = false
                val fullReply = synchronized(replyParts) { replyParts.joinToString("") }
                _events.emit(AgentEvent.LeadDone(fullReply))

            } catch (e: CancellationException) {
                isStreaming = false
                throw e
            } catch (e: Exception) {
                log.error("Error in AgentService.sendMessage", e)
                isStreaming = false
                _events.emit(AgentEvent.LeadError(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Register all tools for the lead agent: standard tools + delegate_task + team tools.
     */
    private suspend fun registerLeadAgentTools() {
        val schemas = toolRouter.getToolSchemas().toMutableList()

        // Add delegate_task tool
        schemas.add(AgentRegistry.buildDelegateTaskSchema(agents))

        // Add team tools
        schemas.addAll(TeamService.teamToolSchemas())

        if (schemas.isEmpty()) return

        val params = buildJsonObject {
            putJsonArray("tools") {
                for (schema in schemas) {
                    add(json.parseToJsonElement(schema))
                }
            }
        }
        lspClient.sendRequest("conversation/registerTools", params)
        log.info("AgentService: registered ${schemas.size} lead agent tools")
    }

    /**
     * Handle a tool call from the LSP server for a conversation owned by this service.
     */
    suspend fun handleToolCall(id: Int, name: String, input: JsonObject, conversationId: String?) {
        when (name) {
            "delegate_task" -> handleDelegateTask(id, input)
            "create_team", "send_message", "delete_team" -> {
                val teamService = TeamService.getInstance(project)
                val result = teamService.handleToolCall(name, input)
                lspClient.sendResponse(id, result)
            }
            else -> {
                // Standard tool — route through ToolRouter
                val isChatConversation = conversationId == leadConversationId
                if (isChatConversation) {
                    _events.emit(AgentEvent.LeadToolCall(name, input))
                }

                val result = toolRouter.executeTool(name, input)
                lspClient.sendResponse(id, result)

                if (isChatConversation) {
                    val outputText = result.jsonArray.firstOrNull()?.jsonObject
                        ?.get("content")?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("value")?.jsonPrimitive?.contentOrNull ?: ""
                    _events.emit(AgentEvent.LeadToolResult(name, outputText.take(200)))
                }
            }
        }
    }

    /**
     * Handle the delegate_task tool call — the core of subagent spawning.
     * Parse input, find agent definition, create WorkerSession, execute, return result.
     */
    private suspend fun handleDelegateTask(id: Int, input: JsonObject) {
        val description = input["description"]?.jsonPrimitive?.contentOrNull ?: "subtask"
        val prompt = input["prompt"]?.jsonPrimitive?.contentOrNull ?: ""
        val subagentType = input["subagent_type"]?.jsonPrimitive?.contentOrNull ?: "general-purpose"
        val modelOverride = input["model"]?.jsonPrimitive?.contentOrNull
        val maxTurns = input["max_turns"]?.jsonPrimitive?.intOrNull

        val agentDef = AgentRegistry.findByType(subagentType, agents)
        if (agentDef == null) {
            log.warn("AgentService: unknown subagent type '$subagentType', falling back to general-purpose")
        }
        val effectiveDef = agentDef ?: agents.find { it.agentType == "general-purpose" } ?: agents.last()

        val agentId = "subagent-${UUID.randomUUID().toString().take(8)}"
        val resolvedModel = if (modelOverride != null) modelOverride
            else effectiveDef.model.resolveModelId("gpt-4.1")

        // Build tool restrictions — exclude disallowed tools
        val effectiveTools = effectiveDef.tools

        log.info("AgentService: spawning subagent [$agentId] type=$subagentType model=$resolvedModel")
        _events.emit(AgentEvent.SubagentSpawned(agentId, effectiveDef.agentType, description))

        val session = WorkerSession(
            workerId = agentId,
            role = effectiveDef.agentType,
            systemPrompt = effectiveDef.systemPrompt,
            model = resolvedModel,
            agentMode = true,
            toolsEnabled = effectiveTools,
            projectName = project.name,
            workspaceRoot = project.basePath ?: "/tmp",
        )

        session.onEvent = { event ->
            scope.launch {
                when (event) {
                    is WorkerEvent.Delta -> _events.emit(AgentEvent.SubagentDelta(agentId, event.text))
                    is WorkerEvent.ToolCall -> _events.emit(AgentEvent.SubagentToolCall(agentId, event.toolName))
                    is WorkerEvent.Done -> {} // handled after executeTask returns
                    is WorkerEvent.Error -> _events.emit(AgentEvent.SubagentCompleted(agentId, event.message, "error"))
                }
            }
        }

        activeSubagents[agentId] = session

        try {
            val result = session.executeTask(prompt)
            _events.emit(AgentEvent.SubagentCompleted(agentId, result.take(500), "success"))

            // Return result to the lead conversation
            val toolResult = buildJsonArray {
                addJsonObject {
                    putJsonArray("content") {
                        addJsonObject { put("value", result) }
                    }
                    put("status", "success")
                }
                add(JsonNull)
            }
            lspClient.sendResponse(id, toolResult)

        } catch (e: Exception) {
            log.error("AgentService: subagent $agentId failed", e)
            _events.emit(AgentEvent.SubagentCompleted(agentId, e.message ?: "Error", "error"))

            val toolResult = buildJsonArray {
                addJsonObject {
                    putJsonArray("content") {
                        addJsonObject { put("value", "Subagent error: ${e.message}") }
                    }
                    put("status", "error")
                }
                add(JsonNull)
            }
            lspClient.sendResponse(id, toolResult)
        } finally {
            activeSubagents.remove(agentId)
        }
    }

    /**
     * Handle progress events from the lead agent's conversation.
     */
    private suspend fun handleLeadProgress(value: JsonObject, replyParts: MutableList<String>) {
        val kind = value["kind"]?.jsonPrimitive?.contentOrNull

        if (kind == "end") {
            isStreaming = false
            return
        }

        val reply = value["reply"]?.jsonPrimitive?.contentOrNull
        if (reply != null) {
            replyParts.add(reply)
            _events.emit(AgentEvent.LeadDelta(reply))
        }

        val delta = value["delta"]?.jsonPrimitive?.contentOrNull
        if (delta != null) {
            replyParts.add(delta)
            _events.emit(AgentEvent.LeadDelta(delta))
        }

        val message = value["message"]?.jsonPrimitive?.contentOrNull
        if (message != null && kind != "begin") {
            replyParts.add(message)
            _events.emit(AgentEvent.LeadDelta(message))
        }

        // Agent rounds
        val rounds = value["editAgentRounds"]?.jsonArray
        rounds?.forEach { roundEl ->
            val round = roundEl.jsonObject
            val roundReply = round["reply"]?.jsonPrimitive?.contentOrNull ?: ""
            if (roundReply.isNotEmpty()) {
                replyParts.add(roundReply)
                _events.emit(AgentEvent.LeadDelta(roundReply))
            }

            val toolCalls = round["toolCalls"]?.jsonArray
            toolCalls?.forEach { toolCallEl ->
                val tc = toolCallEl.jsonObject
                val name = tc["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val tcInput = tc["input"]?.jsonObject ?: JsonObject(emptyMap())
                val status = tc["status"]?.jsonPrimitive?.contentOrNull ?: ""

                // Skip delegate_task tool calls in the lead progress — shown as subagent events instead
                if (name != "delegate_task") {
                    _events.emit(AgentEvent.LeadToolCall(name, tcInput))
                    if (status == "completed" || status == "error") {
                        val resultData = tc["result"]?.jsonArray
                        val resultText = resultData?.firstOrNull()?.jsonObject
                            ?.get("content")?.jsonPrimitive?.contentOrNull
                            ?: status
                        _events.emit(AgentEvent.LeadToolResult(name, resultText.take(200)))
                    }
                }
            }
        }
    }

    /**
     * Check if this service owns the given conversationId
     * (either lead conversation or an active subagent).
     */
    fun ownsConversation(conversationId: String?): Boolean {
        if (conversationId == null) return false
        if (conversationId == leadConversationId) return true
        // Check active subagent sessions — they route tool calls through standard ToolRouter
        // but we still need to handle their invokeClientTool callbacks
        return activeSubagents.values.any { it.workerId == conversationId }
    }

    /** Cancel the current streaming response and all subagents. */
    fun cancel() {
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
        activeSubagents.values.forEach { it.cancel() }
        activeSubagents.clear()
        isStreaming = false

        scope.launch { _events.emit(AgentEvent.LeadDone()) }
    }

    /** Start a fresh conversation. */
    fun newConversation() {
        cancel()
        leadConversationId = null
        agents = emptyList()
    }

    override fun dispose() {
        cancel()
        scope.cancel()
    }
}
