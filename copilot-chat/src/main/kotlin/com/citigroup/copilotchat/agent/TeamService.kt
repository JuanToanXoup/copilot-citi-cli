package com.citigroup.copilotchat.agent

import com.citigroup.copilotchat.config.StoragePaths
import com.citigroup.copilotchat.conversation.ConversationManager
import com.citigroup.copilotchat.lsp.LspClientPool
import com.citigroup.copilotchat.lsp.ToolSetKey
import com.citigroup.copilotchat.orchestrator.WorkerSession
import com.citigroup.copilotchat.orchestrator.WorkerEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages team lifecycle: creating teams, spawning persistent teammate agents,
 * routing messages via file-based mailboxes, and team disbanding.
 */
@Service(Service.Level.PROJECT)
class TeamService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(TeamService::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    // Default: non-blocking team coordination; switches to IO for mailbox file I/O
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var activeTeam: TeamConfig? = null
    private val teammateJobs = ConcurrentHashMap<String, Job>()
    private val teammateAbortFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val teammateSessions = ConcurrentHashMap<String, WorkerSession>()

    companion object {
        fun getInstance(project: Project): TeamService =
            project.getService(TeamService::class.java)
    }

    /**
     * Route a team tool call and return the JSON-RPC result.
     */
    suspend fun handleToolCall(name: String, input: JsonObject): JsonElement {
        return when (name) {
            "create_team" -> handleCreateTeam(input)
            "send_message" -> handleSendMessage(input)
            "delete_team" -> handleDeleteTeam(input)
            else -> wrapResult("Unknown team tool: $name", isError = true)
        }
    }

    private suspend fun handleCreateTeam(input: JsonObject): JsonElement {
        val teamName = input["name"]?.jsonPrimitive?.contentOrNull ?: return wrapResult("Missing team name", isError = true)
        val description = input["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val leadAgentId = input["leadAgentId"]?.jsonPrimitive?.contentOrNull ?: "default-lead"
        val membersArray = input["members"]?.jsonArray ?: return wrapResult("Missing members", isError = true)

        val eventBus: AgentEventBus = AgentService.getInstance(project)
        val agents = AgentRegistry.loadAll(project.basePath)

        val config = TeamConfig(
            name = teamName,
            description = description,
            leadAgentId = leadAgentId,
        )
        activeTeam = config

        val spawnedNames = mutableListOf<String>()

        for (memberEl in membersArray) {
            val memberObj = memberEl.jsonObject
            val name = memberObj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val agentType = memberObj["agentType"]?.jsonPrimitive?.contentOrNull ?: "general-purpose"
            val initialPrompt = memberObj["initialPrompt"]?.jsonPrimitive?.contentOrNull ?: continue
            val modelOverride = memberObj["model"]?.jsonPrimitive?.contentOrNull

            val agentDef = AgentRegistry.findByType(agentType, agents)
                ?: agents.find { it.agentType == "general-purpose" }
                ?: continue

            val resolvedModel = modelOverride ?: agentDef.model.resolveModelId("gpt-4.1")

            val member = TeamMember(
                agentId = "teammate-$name",
                name = name,
                agentType = agentDef.agentType,
                model = resolvedModel,
            )
            config.members.add(member)
            spawnTeammate(teamName, name, agentDef, initialPrompt, resolvedModel)
            spawnedNames.add(name)

            scope.launch {
                eventBus.emit(TeamEvent.MemberJoined(name, agentDef.agentType))
            }
        }

        // M7: Persist full team config (including members) so it survives plugin restart.
        // Teammates are NOT auto-respawned on restart — only the config is restored.
        persistTeamConfig(config)

        scope.launch {
            eventBus.emit(TeamEvent.Created(teamName))
        }

        return wrapResult("Team '$teamName' created with ${spawnedNames.size} members: ${spawnedNames.joinToString(", ")}")
    }

    private fun spawnTeammate(
        teamName: String,
        name: String,
        agentDef: AgentDefinition,
        initialPrompt: String,
        model: String,
    ) {
        val abortFlag = AtomicBoolean(false)
        teammateAbortFlags[name] = abortFlag

        val pool = LspClientPool.getInstance(project)
        val allowedTools = if (agentDef.hasUnrestrictedTools) emptySet() else agentDef.tools.toSet()
        val teammateClient = if (allowedTools.isEmpty()) {
            pool.default
        } else {
            pool.acquireClient(allowedTools)
        }

        // Initialize pool client for teammate's tool set
        val toolSetKey = ToolSetKey.of(allowedTools)
        if (toolSetKey != ToolSetKey.ALL) {
            val cachedAuth = ConversationManager.getInstance(project).cachedAuth
            scope.launch {
                try {
                    pool.ensureInitialized(toolSetKey, cachedAuth)
                } catch (e: Exception) {
                    log.warn("TeamService: pool client init failed for teammate '$name'", e)
                }
            }
        }

        val session = WorkerSession(
            workerId = "teammate-$name",
            role = name,
            systemPrompt = agentDef.systemPromptTemplate + "\n\nYou are a teammate named '$name' in team '$teamName'. " +
                "After completing your task, you will go idle and wait for new messages in your mailbox.",
            model = model,
            agentMode = true,
            toolsEnabled = agentDef.tools,
            projectName = project.name,
            workspaceRoot = project.basePath ?: "/tmp",
            lspClient = teammateClient,
        )

        // Register tool filter when conversationId is captured (mirrors SubagentManager pattern).
        // Without this, the agent's tools field is never enforced at runtime.
        if (!agentDef.hasUnrestrictedTools) {
            val agentService = AgentService.getInstance(project)
            session.onConversationId = { convId ->
                agentService.registerTeammateToolFilter(convId, agentDef.tools.toSet())
                log.info("TeamService: registered tool filter for teammate '$name' (convId=$convId, allowed=${agentDef.tools.size} tools)")
            }
        }

        teammateSessions[name] = session

        val job = scope.launch {
            teammateMainLoop(teamName, name, session, initialPrompt, abortFlag)
        }
        teammateJobs[name] = job
    }

    /**
     * Persistent teammate loop:
     * 1. Execute initial task
     * 2. Go idle, notify lead via mailbox
     * 3. Poll own mailbox every 500ms
     * 4. Resume with new prompt when message arrives
     */
    private suspend fun teammateMainLoop(
        teamName: String,
        name: String,
        session: WorkerSession,
        initialPrompt: String,
        abortFlag: AtomicBoolean,
    ) {
        val eventBus: AgentEventBus = AgentService.getInstance(project)
        val mailbox = Mailbox(teamName, name)
        val leadMailbox = Mailbox(teamName, "team-lead")
        var currentPrompt = initialPrompt

        while (!abortFlag.get()) {
            try {
                // Phase 1: Execute task
                session.onEvent = { event ->
                    scope.launch {
                        when (event) {
                            is WorkerEvent.Delta -> {} // Could emit teammate events if needed
                            is WorkerEvent.ToolCall -> {}
                            is WorkerEvent.Done -> {}
                            is WorkerEvent.Error -> {}
                        }
                    }
                }

                val result = session.executeTask(currentPrompt)

                if (abortFlag.get()) break

                // Phase 2: Go idle, notify lead
                withContext(Dispatchers.IO) {
                    leadMailbox.write(MailboxMessage(
                        from = name,
                        text = "Task completed. Result summary: ${result.take(300)}",
                        summary = "idle_notification",
                    ))
                }

                scope.launch {
                    eventBus.emit(TeamEvent.MemberIdle(name))
                    eventBus.emit(TeamEvent.MailboxMessage(name, "team-lead", "Task completed"))
                }

                // Phase 3: Poll mailbox every 500ms
                var newPrompt: String? = null
                while (!abortFlag.get() && newPrompt == null) {
                    delay(500)
                    val unread = withContext(Dispatchers.IO) { mailbox.readUnread() }
                    if (unread.isNotEmpty()) {
                        // Prioritize team-lead messages
                        val leadMsg = unread.find { it.from == "team-lead" }
                        val msg = leadMsg ?: unread.first()
                        newPrompt = msg.text

                        // Mark all as read
                        withContext(Dispatchers.IO) {
                            mailbox.markRead(unread.map { it.timestamp }.toSet())
                        }

                        scope.launch {
                            eventBus.emit(TeamEvent.MemberResumed(name))
                            eventBus.emit(TeamEvent.MailboxMessage(msg.from, name, msg.text.take(50)))
                        }
                    }
                }

                if (abortFlag.get()) break

                // Phase 4: Resume with new prompt
                currentPrompt = newPrompt ?: break

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("TeamService: teammate '$name' error", e)
                withContext(Dispatchers.IO) {
                    leadMailbox.write(MailboxMessage(
                        from = name,
                        text = "Error: ${e.message}",
                        summary = "error",
                    ))
                }
                break
            }
        }

        log.info("TeamService: teammate '$name' loop ended")
    }

    /** Persist the full team config (with members) to config.json. */
    private fun persistTeamConfig(config: TeamConfig) {
        val teamDir = StoragePaths.teams(config.name)
        teamDir.mkdirs()
        val configJson = buildJsonObject {
            put("name", config.name)
            put("description", config.description)
            put("createdAt", config.createdAt)
            put("leadAgentId", config.leadAgentId)
            putJsonArray("members") {
                for (m in config.members) {
                    addJsonObject {
                        put("agentId", m.agentId)
                        put("name", m.name)
                        put("agentType", m.agentType)
                        put("model", m.model)
                        put("joinedAt", m.joinedAt)
                    }
                }
            }
        }
        File(teamDir, "config.json").writeText(json.encodeToString(JsonObject.serializer(), configJson))
        log.info("TeamService: persisted config for team '${config.name}' (${config.members.size} members)")
    }

    /**
     * Restore team config from disk on service init.
     * Scans all team directories for config.json files and loads the most recent one.
     * Teammates are NOT respawned — only the config is restored so send_message/delete work.
     */
    internal fun restoreFromDisk() {
        try {
            val teamsRoot = StoragePaths.teams("").parentFile ?: return
            if (!teamsRoot.isDirectory) return
            val teamDirs = teamsRoot.listFiles { f -> f.isDirectory } ?: return
            // Find the most recently created team config
            var latest: TeamConfig? = null
            for (dir in teamDirs) {
                val configFile = File(dir, "config.json")
                if (!configFile.exists()) continue
                try {
                    val obj = json.parseToJsonElement(configFile.readText()).jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L
                    val config = TeamConfig(
                        name = name,
                        description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                        createdAt = createdAt,
                        leadAgentId = obj["leadAgentId"]?.jsonPrimitive?.contentOrNull ?: "",
                    )
                    obj["members"]?.jsonArray?.forEach { el ->
                        val m = el.jsonObject
                        config.members.add(TeamMember(
                            agentId = m["agentId"]?.jsonPrimitive?.contentOrNull ?: "",
                            name = m["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            agentType = m["agentType"]?.jsonPrimitive?.contentOrNull ?: "",
                            model = m["model"]?.jsonPrimitive?.contentOrNull ?: "",
                            joinedAt = m["joinedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                        ))
                    }
                    if (latest == null || createdAt > latest.createdAt) {
                        latest = config
                    }
                } catch (e: Exception) {
                    log.warn("TeamService: failed to read config from ${dir.name}: ${e.message}")
                }
            }
            if (latest != null) {
                activeTeam = latest
                log.info("TeamService: restored team '${latest.name}' from disk (${latest.members.size} members, no agents spawned)")
            }
        } catch (e: Exception) {
            log.warn("TeamService: failed to restore from disk: ${e.message}")
        }
    }

    private suspend fun handleSendMessage(input: JsonObject): JsonElement {
        val to = input["to"]?.jsonPrimitive?.contentOrNull ?: return wrapResult("Missing 'to' field", isError = true)
        val text = input["text"]?.jsonPrimitive?.contentOrNull ?: return wrapResult("Missing 'text' field", isError = true)
        val summary = input["summary"]?.jsonPrimitive?.contentOrNull

        val team = activeTeam ?: return wrapResult("No active team", isError = true)

        val mailbox = Mailbox(team.name, to)
        withContext(Dispatchers.IO) {
            mailbox.write(MailboxMessage(
                from = "team-lead",
                text = text,
                summary = summary,
            ))
        }

        val eventBus: AgentEventBus = AgentService.getInstance(project)
        scope.launch {
            eventBus.emit(TeamEvent.MailboxMessage("team-lead", to, text.take(50)))
        }

        return wrapResult("Message sent to '$to'")
    }

    private suspend fun handleDeleteTeam(input: JsonObject): JsonElement {
        val teamName = input["name"]?.jsonPrimitive?.contentOrNull
            ?: activeTeam?.name
            ?: return wrapResult("No team to delete", isError = true)

        // Set all abort flags
        teammateAbortFlags.values.forEach { it.set(true) }

        // Cancel all jobs
        teammateJobs.values.forEach { it.cancel() }
        teammateJobs.clear()
        teammateAbortFlags.clear()
        teammateSessions.clear()

        // Cleanup files
        Mailbox.deleteTeamMailboxes(teamName)

        val eventBus: AgentEventBus = AgentService.getInstance(project)
        scope.launch {
            eventBus.emit(TeamEvent.Disbanded(teamName))
        }

        activeTeam = null
        return wrapResult("Team '$teamName' disbanded")
    }

    private fun wrapResult(text: String, isError: Boolean = false): JsonElement {
        return buildJsonArray {
            addJsonObject {
                putJsonArray("content") {
                    addJsonObject { put("value", text) }
                }
                put("status", if (isError) "error" else "success")
            }
            add(JsonNull)
        }
    }

    override fun dispose() {
        teammateAbortFlags.values.forEach { it.set(true) }
        teammateJobs.values.forEach { it.cancel() }
        scope.cancel()
    }
}
