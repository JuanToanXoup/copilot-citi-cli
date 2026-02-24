package com.citigroup.copilotchat.lsp

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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Canonical tool set identifier. Agents with the same ToolSetKey share a process;
 * agents with different keys get separate processes.
 */
@JvmInline
value class ToolSetKey(val value: String) {
    companion object {
        /** All tools — default client. */
        val ALL = ToolSetKey("*")

        fun of(tools: Collection<String>): ToolSetKey {
            if (tools.isEmpty()) return ALL
            return ToolSetKey(tools.sorted().distinct().joinToString(","))
        }
    }
}

/**
 * Project-level service managing one or more [LspClient] instances keyed by [ToolSetKey].
 *
 * The default client (ToolSetKey.ALL) is used by the Chat tab and lead agent.
 * Partitioned clients are created on demand for subagents/teammates with restricted tool sets.
 */
@Service(Service.Level.PROJECT)
class LspClientPool(private val project: Project) : Disposable {

    private val log = Logger.getInstance(LspClientPool::class.java)

    private data class PoolEntry(
        val client: LspClient,
        var session: LspSession? = null,
        val refCount: AtomicInteger = AtomicInteger(0),
    )

    private val clients = ConcurrentHashMap<ToolSetKey, PoolEntry>()

    init {
        // Pre-create the default entry
        clients[ToolSetKey.ALL] = PoolEntry(LspClient("default"))
    }

    /** The default client — used by the Chat tab and lead agent. */
    val default: LspClient get() = clients[ToolSetKey.ALL]!!.client

    companion object {
        fun getInstance(project: Project): LspClientPool =
            project.getService(LspClientPool::class.java)
    }

    /**
     * Get or create a client for the given tool set.
     * Does NOT initialize the LSP process — call [ensureInitialized] separately.
     */
    fun getClient(tools: Collection<String> = emptyList()): LspClient {
        val key = ToolSetKey.of(tools)
        return clients.computeIfAbsent(key) {
            log.info("LspClientPool: creating new client for key='${it.value}'")
            PoolEntry(LspClient(it.value))
        }.client
    }

    /**
     * Acquire a client for the given tool set with reference counting.
     * Call [releaseClient] when done.
     */
    fun acquireClient(tools: Collection<String>): LspClient {
        val key = ToolSetKey.of(tools)
        val entry = clients.computeIfAbsent(key) {
            log.info("LspClientPool: creating new client for key='${it.value}'")
            PoolEntry(LspClient(it.value))
        }
        val refs = entry.refCount.incrementAndGet()
        log.info("LspClientPool: acquired client '${key.value}' (refs=$refs)")
        return entry.client
    }

    /**
     * Release a client acquired via [acquireClient]. Disposes the client when
     * the reference count reaches zero (unless it's the default client).
     */
    fun releaseClient(tools: Collection<String>) {
        val key = ToolSetKey.of(tools)
        if (key == ToolSetKey.ALL) return // default never released

        val entry = clients[key] ?: return
        val refs = entry.refCount.decrementAndGet()
        log.info("LspClientPool: released client '${key.value}' (refs=$refs)")

        if (refs <= 0) {
            clients.remove(key)
            entry.session?.dispose()
            entry.client.dispose()
            log.info("LspClientPool: disposed client '${key.value}'")
        }
    }

    /**
     * Ensure the pool client for [key] is initialized: LSP process started,
     * auth complete, tools registered (filtered to the key's tool set).
     *
     * Uses [CachedAuth] from the [ConversationManager] to skip binary discovery
     * and auth on subsequent clients.
     */
    suspend fun ensureInitialized(key: ToolSetKey, cachedAuth: CachedAuth) {
        val entry = clients[key] ?: return
        if (entry.session?.initialized == true && entry.client.isRunning) return

        val toolFilter = if (key == ToolSetKey.ALL) emptySet() else key.value.split(",").toSet()

        val session = LspSession(
            project = project,
            lspClient = entry.client,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            onServerRequest = { method, id, params ->
                handlePoolServerRequest(entry, method, id, params)
            },
            onMcpError = { msg -> log.warn("LspClientPool[${key.value}] MCP error: $msg") },
            cachedAuth = cachedAuth,
            toolFilter = toolFilter,
        )
        entry.session = session
        session.ensureInitialized()
    }

    /**
     * Simplified server request handler for non-default pool clients.
     * Tool calls go straight to execution — no AgentService routing, no Chat tab events.
     */
    private suspend fun handlePoolServerRequest(
        entry: PoolEntry,
        method: String,
        id: Int,
        params: JsonObject,
    ) {
        val client = entry.client
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
                    ConversationManager.getInstance(project).getWorkspaceOverride(convId)
                } else null

                // Check client-side MCP tools first (e.g. Playwright)
                val mcpManager = entry.session?.clientMcpManager
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
                    client.sendResponse(id, result)
                } else {
                    val toolRouter = ToolRouter(project)
                    val result = toolRouter.executeTool(toolName, toolInput, wsOverride)
                    client.sendResponse(id, result)
                }
            }
            else -> {
                client.sendResponse(id, JsonNull)
            }
        }
    }

    /** Number of active (non-default) clients. */
    val activeCount: Int get() = clients.size

    /** Debug dump of all pool entries. */
    fun debugDump(): String {
        return clients.entries.joinToString("\n") { (key, entry) ->
            "  ${key.value}: refs=${entry.refCount.get()}, running=${entry.client.isRunning}"
        }
    }

    override fun dispose() {
        for ((_, entry) in clients) {
            entry.session?.dispose()
            entry.client.dispose()
        }
        clients.clear()
    }
}
