package com.speckit.plugin.tools.agents

import com.github.copilot.chat.conversation.agent.rpc.Capabilities
import com.github.copilot.chat.conversation.agent.rpc.Turn
import com.github.copilot.chat.conversation.agent.rpc.command.ConversationCreateCommand
import com.github.copilot.chat.conversation.agent.rpc.command.ConversationDestroyCommand
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelTool
import com.github.copilot.chat.conversation.agent.rpc.command.LanguageModelToolResult
import com.github.copilot.chat.conversation.agent.rpc.message.ConversationProgress
import com.github.copilot.chat.conversation.agent.tool.LanguageModelToolRegistration
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationManager
import com.github.copilot.chat.conversation.agent.tool.ToolInvocationRequest
import com.github.copilot.lang.agent.CopilotAgentProcessService
import com.github.copilot.lang.agent.rpc.JsonRPC
import com.github.copilot.lang.agent.rpc.JsonRpcNotificationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.speckit.plugin.tools.PathSandbox
import com.speckit.plugin.tools.ResourceLoader
import com.speckit.plugin.tools.ScriptRunner
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Base class for speckit agent tools that run as autonomous sub-conversations.
 *
 * When the main Copilot Chat agent calls a speckit tool (e.g., `speckit_plan`),
 * this class spins up a **separate LSP conversation session** using the tool's
 * `.agent.md` file as the system prompt (via `customChatModeId`). The sub-agent
 * runs with its own restricted tool set, does the work autonomously, and returns
 * its output as the tool result.
 *
 * Subclasses override [gatherExtraContext] and [getPromptSuffix] to inject
 * project-specific artifacts (spec.md, templates, etc.) into the user message.
 */
abstract class SubagentTool(
    private val toolName: String,
    private val toolDescription: String,
    private val agentFileName: String,
    private val chatModeSlug: String,
    private val inputSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf<String, Any>(),
        "required" to listOf<String>()
    )
) : LanguageModelToolRegistration {

    private val log = Logger.getInstance(SubagentTool::class.java)

    override val toolDefinition = LanguageModelTool(
        toolName,
        toolDescription,
        inputSchema,
        null,
        "function",
        "enabled"
    )

    override suspend fun handleInvocation(
        request: ToolInvocationRequest
    ): LanguageModelToolResult {
        val manager = ApplicationManager.getApplication().getService(ToolInvocationManager::class.java)
        val project = manager.findProjectForInvocation(request.identifier)
            ?: return LanguageModelToolResult.Companion.error("No project found for invocation")
        val basePath = project.basePath
            ?: return LanguageModelToolResult.Companion.error("No project base path")

        // Load agent instructions from .agent.md file
        val agentInstructions = ResourceLoader.readAgent(basePath, agentFileName)
            ?: return LanguageModelToolResult.Companion.error(
                "Agent definition not found: $agentFileName"
            )

        // Assemble the user message: agent instructions (system prompt) + project context
        // We use standard Agent mode and embed the .agent.md content directly
        val userMessage = withContext(Dispatchers.IO) {
            buildString {
                // Agent instructions act as the system prompt
                appendLine("# Agent Instructions")
                appendLine()
                appendLine(agentInstructions)
                appendLine()

                appendLine("# Context")
                appendLine()

                appendLine("## File Discovery Rules")
                appendLine("- **NEVER construct file paths by guessing.** Use `run_in_terminal` with `find` to locate files first.")
                appendLine("- Example: `find $basePath/src -name \"ClassName.java\" -type f`")
                appendLine("- Only pass absolute paths from `find` output to `read_file`.")
                appendLine()

                if (ResourceLoader.hasScript(basePath, "check-prerequisites.sh")) {
                    val scriptPath = PathSandbox.resolve(basePath, ".specify/scripts/bash/check-prerequisites.sh")
                    if (scriptPath != null) {
                        val prereqResult = ScriptRunner.execScript(scriptPath, listOf("--json"), basePath)
                        if (prereqResult.success) {
                            appendLine("## Project Context")
                            appendLine(prereqResult.output)
                            appendLine()
                        }
                    }
                }

                val constitution = LocalFileSystem.getInstance()
                    .findFileByIoFile(File(basePath, ".specify/memory/constitution.md"))
                if (constitution != null && !constitution.isDirectory) {
                    appendLine("## Constitution")
                    appendLine(VfsUtilCore.loadText(constitution))
                    appendLine()
                }

                val extra = gatherExtraContext(request, basePath)
                if (extra.isNotEmpty()) {
                    appendLine(extra)
                }

                val suffix = getPromptSuffix(request, basePath)
                if (suffix.isNotEmpty()) {
                    appendLine()
                    appendLine(suffix)
                }
            }
        }

        // Spin up sub-conversation via LSP
        val lspClient = CopilotAgentProcessService.getInstance()
        val token = "WDT-${UUID.randomUUID()}"

        val replyBuilder = StringBuilder()
        val stepsLog = mutableListOf<String>()
        val completion = CompletableFuture<String>()

        // Register progress listener BEFORE creating conversation to avoid race.
        // We use addNotificationListener directly (the Kt extension functions
        // from CopilotAgentProcessServiceKt aren't importable cross-module).
        val listenerDisposable = Disposer.newDisposable()
        lspClient.addNotificationListener(listenerDisposable,
            JsonRpcNotificationListener { name, message ->
                if (!"$/progress".equals(name.trim(), ignoreCase = true)) return@JsonRpcNotificationListener false
                try {
                    val progress = JsonRPC.parseResponse(message, ConversationProgress::class.java)
                        ?: return@JsonRpcNotificationListener false
                    if (progress.token != token) return@JsonRpcNotificationListener false

                    val value = progress.value ?: return@JsonRpcNotificationListener false
                    if (value.isReport()) {
                        // Capture streaming text reply
                        value.reply?.let { replyBuilder.append(it) }
                        // Capture tool-call steps (agent work done via tools, not text)
                        value.steps?.forEach { step ->
                            val desc = step.description ?: step.title
                            if (desc.isNotBlank() && desc !in stepsLog) {
                                stepsLog.add(desc)
                            }
                        }
                    } else if (value.isEnd()) {
                        val error = value.error
                        if (error != null) {
                            completion.completeExceptionally(
                                RuntimeException("Subagent error: ${error.message}")
                            )
                        } else {
                            // Build response: prefer reply text, fall back to steps summary
                            val reply = replyBuilder.toString()
                            val result = if (reply.isNotBlank()) {
                                reply
                            } else if (stepsLog.isNotEmpty()) {
                                "Subagent $toolName completed. Actions taken:\n" +
                                    stepsLog.joinToString("\n") { "- $it" }
                            } else {
                                "Subagent $toolName completed successfully."
                            }
                            completion.complete(result)
                        }
                    }
                } catch (e: Exception) {
                    log.trace("Error parsing progress notification: ${e.message}")
                }
                false
            }
        )

        val workspaceFolderUri = "file://$basePath"

        val createCmd = ConversationCreateCommand(
            listOf(Turn(userMessage, null, null)),
            token,
            Capabilities(),
            emptyList(),
            null,                   // doc
            null,                   // selection
            null,                   // visibleRanges
            null,                   // ignoredSkills
            "panel",                // source
            workspaceFolderUri,     // workspaceFolder
            null,                   // workspaceFolders
            null,                   // userLanguage
            "gpt-4.1",              // model
            null,                   // modelProviderName
            "Agent",                // chatMode (ChatModeKind enum: Ask, Edit, Agent)
            null,                   // customChatModeId (instructions embedded in message)
            false                   // needToolCallConfirmation
        )

        var conversationId: String? = null
        try {
            log.info("Creating sub-conversation for $toolName (mode: $chatModeSlug)")

            // Await the create command without blocking the thread.
            // CancellablePromise -> suspend via callbacks.
            val createResponse = suspendCancellableCoroutine { cont ->
                val createPromise = lspClient.executeCommand(createCmd)
                createPromise.onSuccess { response ->
                    if (response != null) {
                        cont.resume(response)
                    } else {
                        cont.resumeWithException(RuntimeException("conversation/create returned null"))
                    }
                }
                createPromise.onError { error ->
                    cont.resumeWithException(error ?: RuntimeException("conversation/create failed"))
                }
                cont.invokeOnCancellation {
                    createPromise.cancel()
                }
            }

            conversationId = createResponse.conversationId
            log.info("Sub-conversation created: $conversationId for $toolName")

            // Wait for the sub-agent to finish (progress listener completes the future)
            val response = withTimeout(300_000L) {
                completion.await()
            }

            // Cleanup
            cleanupConversation(lspClient, conversationId, listenerDisposable)

            return LanguageModelToolResult.Companion.success(response)
        } catch (e: Exception) {
            val errorType = e.javaClass.simpleName
            log.warn("Subagent $toolName failed ($errorType): ${e.message}", e)
            cleanupConversation(lspClient, conversationId, listenerDisposable)
            return LanguageModelToolResult.Companion.error(
                "Subagent $toolName failed ($errorType): ${e.message ?: "no details"}"
            )
        }
    }

    private fun cleanupConversation(
        lspClient: CopilotAgentProcessService,
        conversationId: String?,
        listenerDisposable: com.intellij.openapi.Disposable
    ) {
        try { Disposer.dispose(listenerDisposable) } catch (_: Exception) {}
        if (conversationId != null) {
            try {
                lspClient.executeCommand(ConversationDestroyCommand(conversationId))
                log.info("Sub-conversation destroyed: $conversationId")
            } catch (e: Exception) {
                log.warn("Failed to destroy sub-conversation $conversationId: ${e.message}")
            }
        }
    }

    // ── Extension points (same as AgentTool) ────────────────────────────

    protected open fun gatherExtraContext(request: ToolInvocationRequest, basePath: String): String = ""

    protected open fun getPromptSuffix(request: ToolInvocationRequest, basePath: String): String = ""

    // ── Utility methods (same as AgentTool) ─────────────────────────────

    protected fun readFileIfExists(basePath: String, relativePath: String): String? {
        return ResourceLoader.readFile(basePath, relativePath)
    }

    protected fun readFeatureArtifact(basePath: String, featureDir: String, fileName: String): String? {
        if (!PathSandbox.isSafeName(featureDir)) return null
        val file = LocalFileSystem.getInstance()
            .findFileByIoFile(File(basePath, "specs/$featureDir/$fileName"))
        return if (file != null && !file.isDirectory) VfsUtilCore.loadText(file) else null
    }

    protected fun resolveFeatureDir(request: ToolInvocationRequest, basePath: String): String? {
        val explicit = request.input?.get("feature")?.asString
        if (explicit != null) {
            if (!PathSandbox.isSafeName(explicit)) return null
            val dir = LocalFileSystem.getInstance()
                .findFileByIoFile(File(basePath, "specs/$explicit"))
            if (dir != null && dir.isDirectory) return explicit
        }
        return findCurrentFeatureDir(basePath)
    }

    protected fun findCurrentFeatureDir(basePath: String): String? {
        val specsDir = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, "specs"))
        if (specsDir == null || !specsDir.isDirectory) return null

        val result = ScriptRunner.exec(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), basePath)
        if (!result.success) return null

        val branch = result.output.trim()
        val match = Regex("^(\\d{3})-").find(branch) ?: return null
        val prefix = match.groupValues[1]

        return specsDir.children
            .filter { it.isDirectory && it.name.startsWith(prefix) }
            .firstOrNull()?.name
    }
}
