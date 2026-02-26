package com.citigroup.copilotchat.lsp

import com.citigroup.copilotchat.agent.AgentDefinition
import com.citigroup.copilotchat.agent.McpServerConfig
import com.citigroup.copilotchat.conversation.ConversationManager
import com.citigroup.copilotchat.conversation.LspSession
import com.citigroup.copilotchat.tools.ToolRouter
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Factory for creating fully-initialized [LspClient] instances configured for
 * specific agent definitions.
 *
 * Provides two creation modes:
 * - **Pool-managed**: Acquires a client from [LspClientPool] keyed by tool set.
 *   Multiple agents with the same tool set share a process. Ref-counted.
 * - **Standalone**: Creates an independent client with its own process, request
 *   handler, and MCP configuration. Used for agents that need custom MCP servers
 *   or isolated request handling.
 *
 * Both modes handle the full initialization sequence (binary discovery, auth
 * handshake, tool registration) via [LspSession] and [CachedAuth].
 */
@Service(Service.Level.PROJECT)
class LspClientFactory(private val project: Project) : Disposable {

    private val log = Logger.getInstance(LspClientFactory::class.java)

    private val pool: LspClientPool get() = LspClientPool.getInstance(project)
    private val conversationManager: ConversationManager get() = ConversationManager.getInstance(project)

    /** Standalone clients not managed by the pool. */
    private val standaloneClients = ConcurrentHashMap<String, StandaloneEntry>()

    private data class StandaloneEntry(
        val client: LspClient,
        val session: LspSession,
        val scope: CoroutineScope,
    )

    companion object {
        fun getInstance(project: Project): LspClientFactory =
            project.getService(LspClientFactory::class.java)
    }

    // ── Pool-managed clients ──────────────────────────────────────────────

    /**
     * Acquire a pool-managed client initialized for the given tool set.
     *
     * Agents with the same tool set share an LSP process. The client is
     * ref-counted — call [releasePoolClient] when the agent finishes.
     *
     * @param tools Tool names this agent needs. Empty = all tools (returns the default client).
     * @return A fully-initialized [LspClient] ready for conversation requests.
     */
    suspend fun acquirePoolClient(tools: Collection<String>): LspClient {
        val client = pool.acquireClient(tools)
        val key = ToolSetKey.of(tools)
        if (key != ToolSetKey.ALL) {
            pool.ensureInitialized(key, conversationManager.cachedAuth)
        }
        return client
    }

    /**
     * Release a pool-managed client acquired via [acquirePoolClient].
     * Disposes the underlying process when the last reference is released.
     */
    fun releasePoolClient(tools: Collection<String>) {
        pool.releaseClient(tools)
    }

    // ── Agent-aware convenience ───────────────────────────────────────────

    /**
     * Acquire a client configured for a specific [AgentDefinition].
     *
     * If the agent has custom MCP servers defined, creates a standalone client
     * with those servers configured. Otherwise acquires from the pool.
     *
     * @return A [ManagedClient] wrapping the client and its release logic.
     */
    suspend fun acquireForAgent(agentDef: AgentDefinition): ManagedClient {
        val tools = if (agentDef.hasUnrestrictedTools) emptySet() else agentDef.tools.toSet()

        // Agents with custom MCP servers need a standalone client
        if (agentDef.mcpServers.isNotEmpty()) {
            val clientId = "agent-${agentDef.agentType}-${System.currentTimeMillis()}"
            val client = createStandaloneClient(
                clientId = clientId,
                tools = tools,
                mcpServers = agentDef.mcpServers,
            )
            return ManagedClient(client, isStandalone = true, clientId = clientId, tools = tools)
        }

        val client = acquirePoolClient(tools)
        return ManagedClient(client, isStandalone = false, clientId = null, tools = tools)
    }

    /**
     * Release a client previously acquired via [acquireForAgent].
     */
    fun release(managed: ManagedClient) {
        if (managed.isStandalone && managed.clientId != null) {
            disposeStandaloneClient(managed.clientId)
        } else {
            releasePoolClient(managed.tools)
        }
    }

    // ── Standalone clients ────────────────────────────────────────────────

    /**
     * Create a standalone fully-initialized client with custom configuration.
     *
     * The client runs its own LSP process, independent of the pool. Use for
     * agents that need custom MCP servers or isolated request handling.
     *
     * Call [disposeStandaloneClient] when done.
     */
    suspend fun createStandaloneClient(
        clientId: String,
        tools: Set<String> = emptySet(),
        mcpServers: Map<String, McpServerConfig> = emptyMap(),
        onServerRequest: (suspend (method: String, id: Int, params: JsonObject) -> Unit)? = null,
    ): LspClient {
        val client = LspClient(clientId)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val requestHandler: suspend (String, Int, JsonObject) -> Unit = onServerRequest
            ?: { method, id, params -> defaultServerRequestHandler(client, method, id, params) }

        val session = LspSession(
            project = project,
            lspClient = client,
            scope = scope,
            onServerRequest = requestHandler,
            onMcpError = { msg -> log.warn("LspClientFactory[$clientId] MCP error: $msg") },
            cachedAuth = conversationManager.cachedAuth,
            toolFilter = tools,
        )

        session.ensureInitialized()

        // Configure agent-specific MCP servers
        if (mcpServers.isNotEmpty()) {
            val config = mcpServers.mapValues { (_, cfg) ->
                buildMap<String, Any> {
                    if (cfg.url.isNotBlank()) {
                        put("url", cfg.url)
                    } else {
                        put("command", cfg.command)
                        if (cfg.args.isNotEmpty()) put("args", cfg.args)
                    }
                    if (cfg.env.isNotEmpty()) put("env", cfg.env)
                }
            }
            session.configureMcp(config)
        }

        standaloneClients[clientId] = StandaloneEntry(client, session, scope)
        log.info("LspClientFactory: created standalone client '$clientId' (tools=${tools.size}, mcp=${mcpServers.size})")
        return client
    }

    /**
     * Dispose a standalone client and its LSP process.
     */
    fun disposeStandaloneClient(clientId: String) {
        val entry = standaloneClients.remove(clientId) ?: return
        entry.session.dispose()
        entry.client.dispose()
        entry.scope.cancel()
        log.info("LspClientFactory: disposed standalone client '$clientId'")
    }

    /** Get the [LspSession] for a standalone client, if it exists. */
    fun getStandaloneSession(clientId: String): LspSession? =
        standaloneClients[clientId]?.session

    /** Whether a standalone client with [clientId] exists and is running. */
    fun isStandaloneRunning(clientId: String): Boolean =
        standaloneClients[clientId]?.client?.isRunning == true

    /** Number of active standalone clients. */
    val standaloneCount: Int get() = standaloneClients.size

    // ── Default request handler ───────────────────────────────────────────

    /**
     * Default server request handler for standalone clients.
     * Auto-accepts tool confirmations and routes tool calls to [ToolRouter].
     */
    private suspend fun defaultServerRequestHandler(
        client: LspClient,
        method: String,
        id: Int,
        params: JsonObject,
    ) {
        when (method) {
            "conversation/invokeClientToolConfirmation" -> {
                val result = buildJsonArray {
                    addJsonObject { put("result", "accept") }
                    add(JsonNull)
                }
                client.sendResponse(id, result)
            }
            "conversation/invokeClientTool" -> {
                val toolName = params["name"]?.jsonPrimitive?.contentOrNull
                    ?: params["toolName"]?.jsonPrimitive?.contentOrNull
                    ?: "unknown"
                val toolInput = params["input"]?.jsonObject
                    ?: params["arguments"]?.jsonObject
                    ?: JsonObject(emptyMap())

                val convId = params["conversationId"]?.jsonPrimitive?.contentOrNull
                val wsOverride = if (convId != null) {
                    conversationManager.getWorkspaceOverride(convId)
                } else null

                val toolRouter = ToolRouter(project)
                val result = toolRouter.executeTool(toolName, toolInput, wsOverride, convId)
                client.sendResponse(id, result)
            }
            else -> {
                client.sendResponse(id, JsonNull)
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun dispose() {
        for ((clientId, entry) in standaloneClients) {
            log.info("LspClientFactory: disposing standalone client '$clientId'")
            entry.session.dispose()
            entry.client.dispose()
            entry.scope.cancel()
        }
        standaloneClients.clear()
    }
}

/**
 * A client handle returned by [LspClientFactory.acquireForAgent] that
 * tracks whether the client is pool-managed or standalone, so the caller
 * can release it correctly via [LspClientFactory.release].
 */
data class ManagedClient(
    val client: LspClient,
    val isStandalone: Boolean,
    val clientId: String?,
    val tools: Collection<String>,
)
