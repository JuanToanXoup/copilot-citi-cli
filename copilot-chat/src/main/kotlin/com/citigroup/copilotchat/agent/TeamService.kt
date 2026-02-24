package com.citigroup.copilotchat.agent

import com.citigroup.copilotchat.lsp.LspClient
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
        val membersArray = input["members"]?.jsonArray ?: return wrapResult("Missing members", isError = true)

        val agentService = AgentService.getInstance(project)
        val agents = AgentRegistry.loadAll(project.basePath)

        val config = TeamConfig(
            name = teamName,
            description = description,
        )
        activeTeam = config

        // Save config.json
        val teamDir = File(System.getProperty("user.home"), ".copilot-chat/teams/$teamName")
        teamDir.mkdirs()
        File(teamDir, "config.json").writeText(json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("name", teamName)
            put("description", description)
            put("createdAt", config.createdAt)
        }))

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
                agentService._events.emit(AgentEvent.TeammateJoined(name, agentDef.agentType))
            }
        }

        scope.launch {
            agentService._events.emit(AgentEvent.TeamCreated(teamName))
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
            lspClient = LspClient.getInstance(project),
        )
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
        val agentService = AgentService.getInstance(project)
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
                    agentService._events.emit(AgentEvent.TeammateIdle(name))
                    agentService._events.emit(AgentEvent.MailboxMessageEvent(name, "team-lead", "Task completed"))
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
                            agentService._events.emit(AgentEvent.TeammateResumed(name))
                            agentService._events.emit(AgentEvent.MailboxMessageEvent(msg.from, name, msg.text.take(50)))
                        }
                    }
                }

                if (abortFlag.get()) break

                // Phase 4: Resume with new prompt
                currentPrompt = newPrompt ?: break

            } catch (e: CancellationException) {
                break
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

        val agentService = AgentService.getInstance(project)
        scope.launch {
            agentService._events.emit(AgentEvent.MailboxMessageEvent("team-lead", to, text.take(50)))
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

        val agentService = AgentService.getInstance(project)
        scope.launch {
            agentService._events.emit(AgentEvent.TeamDisbanded(teamName))
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
