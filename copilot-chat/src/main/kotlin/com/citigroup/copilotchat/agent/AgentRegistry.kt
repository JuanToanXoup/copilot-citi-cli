package com.citigroup.copilotchat.agent

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Registry of agent definitions loaded from `.agent.md` files.
 * Built-in agents are bundled as classpath resources under `/agents/`.
 * Custom agents are loaded from project and user directories.
 */
object AgentRegistry {

    private val log = Logger.getInstance(AgentRegistry::class.java)

    /** Names of built-in agent resource files (without path prefix). */
    private val BUILT_IN_RESOURCES = listOf(
        "explore.agent.md",
        "plan.agent.md",
        "bash.agent.md",
        "general-purpose.agent.md",
        "default-lead.agent.md",
    )

    /** Default system prompt template for the built-in lead agent (used as fallback by AgentService). */
    const val DEFAULT_LEAD_TEMPLATE = """You are a lead agent that coordinates sub-agents via the delegate_task tool.

CRITICAL: The delegate_task tool IS available to you. You MUST use it.
Do NOT say delegate_task is unavailable. Do NOT perform subtasks directly.
Always delegate work to specialized agents using delegate_task.

All delegate_task calls within a single round run IN PARALLEL.
You can delegate in multiple rounds when tasks have dependencies:
- Round 1: Fire all independent subtasks at once (they run concurrently)
- Round 2+: After receiving results, fire dependent subtasks that needed earlier output
Only use multiple rounds when a subtask genuinely needs the output of another.
Maximize parallelism — if tasks are independent, fire them all in one round.

Available agent types:
{{AGENT_LIST}}

Workflow:
1. Analyze the user's request and break it into subtasks
2. Identify dependencies — which subtasks need results from others?
3. Call delegate_task for all independent subtasks (they run concurrently)
4. You will receive all results in a follow-up message
5. If dependent subtasks remain, delegate them in the next round
6. Synthesize and present the final answer

Complete the full task without stopping for confirmation."""

    /**
     * Load all agent definitions: built-ins from classpath resources,
     * plus custom `.agent.md` files from project and user directories.
     */
    fun loadAll(projectBasePath: String?): List<AgentDefinition> {
        val agents = loadBuiltIn().toMutableList()

        // Project-level custom agents
        if (projectBasePath != null) {
            val projectAgentDir = File(projectBasePath, ".copilot-chat/agents")
            if (projectAgentDir.isDirectory) {
                projectAgentDir.listFiles { f -> isAgentFile(f) }?.forEach { file ->
                    try {
                        val agent = parseAgentFile(file, AgentSource.CUSTOM_PROJECT)
                        if (agent != null) agents.add(agent)
                    } catch (e: Exception) {
                        log.warn("Failed to parse agent file ${file.name}: ${e.message}")
                    }
                }
            }
        }

        // User-level custom agents
        val userAgentDir = File(System.getProperty("user.home"), ".copilot-chat/agents")
        if (userAgentDir.isDirectory) {
            userAgentDir.listFiles { f -> isAgentFile(f) }?.forEach { file ->
                try {
                    val agent = parseAgentFile(file, AgentSource.CUSTOM_USER)
                    if (agent != null) agents.add(agent)
                } catch (e: Exception) {
                    log.warn("Failed to parse user agent file ${file.name}: ${e.message}")
                }
            }
        }

        val builtInCount = agents.count { it.source == AgentSource.BUILT_IN }
        log.info("Loaded ${agents.size} agents ($builtInCount built-in, ${agents.size - builtInCount} custom)")
        return agents
    }

    /** Load built-in agents from classpath resources under /agents/. */
    private fun loadBuiltIn(): List<AgentDefinition> {
        val agents = mutableListOf<AgentDefinition>()
        for (resourceName in BUILT_IN_RESOURCES) {
            try {
                val content = AgentRegistry::class.java.getResourceAsStream("/agents/$resourceName")
                    ?.bufferedReader()?.readText()
                if (content == null) {
                    log.warn("Built-in agent resource not found: /agents/$resourceName")
                    continue
                }
                val name = if (resourceName.endsWith(".agent.md"))
                    resourceName.removeSuffix(".agent.md") else resourceName.removeSuffix(".md")
                val agent = parseAgentContent(content, name, AgentSource.BUILT_IN)
                if (agent != null) agents.add(agent)
            } catch (e: Exception) {
                log.warn("Failed to parse built-in agent $resourceName: ${e.message}")
            }
        }
        return agents
    }

    /** Accept both `.agent.md` (preferred) and `.md` (backward compat). */
    private fun isAgentFile(f: File): Boolean =
        f.name.endsWith(".agent.md") || f.extension == "md"

    /** Derive the agent name from the file: strip `.agent.md` first, then `.md`. */
    private fun agentNameFromFile(file: File): String =
        if (file.name.endsWith(".agent.md")) file.name.removeSuffix(".agent.md")
        else file.nameWithoutExtension

    private const val MAX_PROMPT_CHARS = 30_000

    /**
     * Parse an `.agent.md` (or `.md`) agent file with optional YAML frontmatter.
     * Delegates to [parseAgentContent] after reading the file.
     */
    internal fun parseAgentFile(file: File, source: AgentSource): AgentDefinition? {
        val content = file.readText()
        val name = agentNameFromFile(file)
        return parseAgentContent(content, name, source, filePath = file.absolutePath)
    }

    /**
     * Parse agent definition from raw `.agent.md` content string.
     *
     * Supported frontmatter fields (Copilot .agent.md spec):
     * ```yaml
     * ---
     * name: my-agent
     * description: Does something cool
     * tools: [read_file, grep_search]
     * model: gpt-4.1
     * maxTurns: 10
     * disable-model-invocation: true
     * handoffs: [explore, plan]
     * metadata:
     *   owner: my-team
     * mcp-servers:
     *   my-server:
     *     type: local
     *     command: npx
     *     args: [-y, my-mcp-server]
     * ---
     * System prompt body here...
     * ```
     */
    internal fun parseAgentContent(
        content: String,
        defaultName: String,
        source: AgentSource,
        filePath: String? = null,
    ): AgentDefinition? {
        val frontmatterRegex = Regex("^---\\n(.*?)\\n---\\n", RegexOption.DOT_MATCHES_ALL)
        val match = frontmatterRegex.find(content)

        val frontmatter = match?.groupValues?.get(1) ?: ""
        var body = if (match != null) content.substring(match.range.last + 1).trim() else content.trim()

        if (body.isBlank() && frontmatter.isBlank()) return null

        // Truncate overly long prompts
        if (body.length > MAX_PROMPT_CHARS) {
            log.warn("Agent '$defaultName': prompt truncated from ${body.length} to $MAX_PROMPT_CHARS chars")
            body = body.take(MAX_PROMPT_CHARS)
        }

        // Parse frontmatter — simple key: value for top-level scalars,
        // multi-line block parsing for nested structures (mcp-servers, metadata).
        val props = mutableMapOf<String, String>()
        val blocks = mutableMapOf<String, MutableList<String>>()
        var currentBlock: String? = null

        for (line in frontmatter.lines()) {
            // Detect block start: top-level key with no value, followed by indented lines
            if (!line.startsWith(" ") && !line.startsWith("\t") && line.endsWith(":") && !line.contains(": ")) {
                currentBlock = line.removeSuffix(":").trim()
                blocks[currentBlock] = mutableListOf()
                continue
            }
            // Indented line belongs to current block
            if (currentBlock != null && (line.startsWith("  ") || line.startsWith("\t"))) {
                blocks[currentBlock]!!.add(line)
                continue
            }
            // Top-level scalar key: value
            currentBlock = null
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                props[key] = value
            }
        }

        val name = props["name"] ?: defaultName
        val description = props["description"] ?: "Custom agent: $name"
        val toolsRaw = props["tools"]
        val tools = if (toolsRaw != null) {
            toolsRaw.removeSurrounding("[", "]").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else null
        val model = parseModelString(props["model"])
        val forkContext = props["forkContext"]?.lowercase() == "true"
        val background = props["background"]?.lowercase() == "true"
        val maxTurns = props["maxTurns"]?.toIntOrNull() ?: 30

        // New Copilot .agent.md fields
        val disableModelInvocation = props["disable-model-invocation"]?.lowercase() == "true"
        val handoffs = props["handoffs"]?.let { raw ->
            raw.removeSurrounding("[", "]").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } ?: emptyList()
        val metadata = parseSimpleMap(blocks["metadata"])
        val mcpServers = parseMcpServers(blocks["mcp-servers"])

        // Supervisor fields
        val subagents = props["subagents"]?.let { raw ->
            raw.removeSurrounding("[", "]").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        val target = props["target"]?.trim()?.ifBlank { null }

        return AgentDefinition(
            agentType = name,
            whenToUse = description,
            tools = tools,
            disallowedTools = if (subagents != null) emptyList()
                else listOf("delegate_task", "create_team", "send_message", "delete_team"),
            source = source,
            model = model,
            systemPromptTemplate = body,
            forkContext = forkContext,
            background = background,
            maxTurns = maxTurns,
            disableModelInvocation = disableModelInvocation,
            handoffs = handoffs,
            mcpServers = mcpServers,
            metadata = metadata,
            filePath = filePath,
            subagents = subagents,
            target = target,
        )
    }

    /** Parse a model string from frontmatter into an [AgentModel]. */
    internal fun parseModelString(raw: String?): AgentModel {
        if (raw == null) return AgentModel.INHERIT
        return when (raw.lowercase().trim()) {
            "gpt-4.1", "gpt4.1" -> AgentModel.GPT_4_1
            "claude-sonnet-4", "sonnet-4", "sonnet" -> AgentModel.CLAUDE_SONNET_4
            else -> AgentModel.INHERIT
        }
    }

    /** Parse indented YAML lines into a simple String->String map. */
    private fun parseSimpleMap(lines: List<String>?): Map<String, String> {
        if (lines.isNullOrEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (line in lines) {
            val trimmed = line.trim()
            val idx = trimmed.indexOf(':')
            if (idx > 0) {
                map[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
            }
        }
        return map
    }

    /**
     * Parse indented YAML blocks for `mcp-servers` into a map of server configs.
     * Expected format:
     * ```
     *   server-name:
     *     type: local
     *     command: npx
     *     args: [-y, my-server]
     *     tools: [tool1, tool2]
     *     env:
     *       KEY: value
     *     url: http://...
     * ```
     */
    private fun parseMcpServers(lines: List<String>?): Map<String, McpServerConfig> {
        if (lines.isNullOrEmpty()) return emptyMap()

        val servers = mutableMapOf<String, McpServerConfig>()
        var currentServer: String? = null
        val serverProps = mutableMapOf<String, String>()
        var envLines = mutableListOf<String>()
        var inEnv = false

        fun flushServer() {
            val sName = currentServer ?: return
            val env = parseSimpleMap(envLines)
            val toolsStr = serverProps["tools"]
            val toolsList = if (toolsStr != null) {
                toolsStr.removeSurrounding("[", "]").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } else null
            val argsList = serverProps["args"]?.let { raw ->
                raw.removeSurrounding("[", "]").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } ?: emptyList()

            servers[sName] = McpServerConfig(
                type = serverProps["type"] ?: "local",
                command = serverProps["command"] ?: "",
                args = argsList,
                tools = toolsList,
                env = env,
                url = serverProps["url"] ?: "",
            )
            serverProps.clear()
            envLines = mutableListOf()
            inEnv = false
        }

        for (line in lines) {
            val indent = line.length - line.trimStart().length
            val trimmed = line.trim()

            // Server name line (2-space indent, ends with colon, no value)
            if (indent == 2 && trimmed.endsWith(":") && !trimmed.contains(": ")) {
                flushServer()
                currentServer = trimmed.removeSuffix(":").trim()
                continue
            }

            // env: block start
            if (indent == 4 && trimmed == "env:") {
                inEnv = true
                continue
            }

            // Lines inside env block (6+ indent)
            if (inEnv && indent >= 6) {
                envLines.add(trimmed)
                continue
            }

            // Regular property at indent 4
            if (indent == 4 && currentServer != null) {
                inEnv = false
                val idx = trimmed.indexOf(':')
                if (idx > 0) {
                    serverProps[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
                }
            }
        }
        flushServer()

        return servers
    }

    /** Case-insensitive agent lookup by type. */
    fun findByType(agentType: String, agents: List<AgentDefinition>): AgentDefinition? {
        return agents.find { it.agentType.equals(agentType, ignoreCase = true) }
    }

    /** Filename validation: only [a-zA-Z0-9._-] allowed (Copilot spec). */
    private val VALID_NAME_REGEX = Regex("^[a-zA-Z0-9._-]+$")

    fun isValidAgentName(name: String): Boolean =
        name.isNotBlank() && VALID_NAME_REGEX.matches(name)

    /** Convert [AgentModel] to its frontmatter string representation. */
    fun modelToString(model: AgentModel): String = when (model) {
        AgentModel.INHERIT -> "inherit"
        AgentModel.GPT_4_1 -> "gpt-4.1"
        AgentModel.CLAUDE_SONNET_4 -> "claude-sonnet-4"
    }

    /**
     * Save an agent definition to disk.
     * Resolves the file path from [existingFilePath] or creates a new one under the project's agents dir.
     * Returns an updated [AgentDefinition] with the resolved [AgentDefinition.filePath].
     */
    fun saveAgent(agent: AgentDefinition, projectBasePath: String, existingFilePath: String? = null): AgentDefinition {
        val file = if (existingFilePath != null) File(existingFilePath) else {
            val agentDir = File(projectBasePath, ".copilot-chat/agents")
            File(agentDir, "${agent.agentType}.agent.md")
        }
        val saved = agent.copy(filePath = file.absolutePath)
        writeAgentFile(saved, file)
        return saved
    }

    /**
     * Delete an agent's file from disk.
     */
    fun deleteAgentFile(filePath: String) {
        val file = File(filePath)
        if (file.exists()) {
            file.delete()
            log.info("Deleted agent file: $filePath")
        }
    }

    /**
     * Find an agent by name (across all sources) and delete its file.
     */
    fun deleteAgentByName(name: String, projectBasePath: String?) {
        val agent = loadAll(projectBasePath).find { it.agentType == name }
        agent?.filePath?.let { deleteAgentFile(it) }
    }

    /**
     * Serialize an [AgentDefinition] to an `.agent.md` file.
     * Writes YAML frontmatter + body (system prompt template).
     */
    fun writeAgentFile(agent: AgentDefinition, file: File) {
        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("name: ${agent.agentType}")
        sb.appendLine("description: ${agent.whenToUse}")
        if (agent.model != AgentModel.INHERIT) {
            sb.appendLine("model: ${modelToString(agent.model)}")
        }
        if (agent.tools != null) {
            sb.appendLine("tools: [${agent.tools.joinToString(", ")}]")
        }
        if (agent.maxTurns != 30) {
            sb.appendLine("maxTurns: ${agent.maxTurns}")
        }
        if (agent.subagents != null) {
            sb.appendLine("subagents: [${agent.subagents.joinToString(", ")}]")
        }
        if (agent.target != null) {
            sb.appendLine("target: ${agent.target}")
        }
        if (agent.forkContext) sb.appendLine("forkContext: true")
        if (agent.background) sb.appendLine("background: true")
        if (agent.disableModelInvocation) sb.appendLine("disable-model-invocation: true")
        if (agent.handoffs.isNotEmpty()) {
            sb.appendLine("handoffs: [${agent.handoffs.joinToString(", ")}]")
        }
        if (agent.mcpServers.isNotEmpty()) {
            sb.appendLine("mcpServers:")
            for ((name, cfg) in agent.mcpServers) {
                sb.appendLine("  $name:")
                if (cfg.type.isNotBlank() && cfg.type != "local") sb.appendLine("    type: ${cfg.type}")
                if (cfg.command.isNotBlank()) sb.appendLine("    command: ${cfg.command}")
                if (cfg.args.isNotEmpty()) sb.appendLine("    args: [${cfg.args.joinToString(", ")}]")
                if (cfg.url.isNotBlank()) sb.appendLine("    url: ${cfg.url}")
                if (cfg.env.isNotEmpty()) {
                    sb.appendLine("    env:")
                    cfg.env.forEach { (k, v) -> sb.appendLine("      $k: $v") }
                }
                if (cfg.tools != null) sb.appendLine("    tools: [${cfg.tools.joinToString(", ")}]")
            }
        }
        if (agent.metadata.isNotEmpty()) {
            sb.appendLine("metadata:")
            agent.metadata.forEach { (k, v) -> sb.appendLine("  $k: $v") }
        }
        sb.appendLine("---")
        if (agent.systemPromptTemplate.isNotBlank()) {
            sb.appendLine(agent.systemPromptTemplate)
        }

        file.parentFile?.mkdirs()
        file.writeText(sb.toString())
        log.info("Wrote agent file: ${file.absolutePath}")
    }
}
