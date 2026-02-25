package com.citigroup.copilotchat.agent

import com.citigroup.copilotchat.config.StoragePaths
import com.intellij.openapi.diagnostic.Logger
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Registry of agent definitions loaded from `.agent.md` files.
 * Built-in agents are bundled as classpath resources under `/agents/`.
 * Custom agents are loaded from project and user directories.
 */
object AgentRegistry : AgentConfigRepository {

    private val log = Logger.getInstance(AgentRegistry::class.java)

    /** Frontmatter keys that are parsed into [AgentDefinition] fields. Anything else → extraFrontmatter. */
    private val KNOWN_FRONTMATTER_KEYS = setOf(
        "name", "description", "tools", "model", "forkContext", "background",
        "maxTurns", "disable-model-invocation", "handoffs", "metadata",
        "mcp-servers", "mcpServers", "subagents", "target",
    )

    /** Valid MCP server transport types. */
    private val VALID_MCP_TYPES = setOf("local", "sse")

    /** Names of built-in agent resource files (without path prefix). */
    private val BUILT_IN_RESOURCES = listOf(
        "explore.agent.md",
        "plan.agent.md",
        "bash.agent.md",
        "general-purpose.agent.md",
        "default-lead.agent.md",
        "strict-lead.agent.md",
        "strict-lead-v2.agent.md",
        "speckit-lead.agent.md",
        "speckit-coverage-lead.agent.md",
    )

    /** Default system prompt template for the built-in lead agent (used as fallback by AgentService). */
    const val DEFAULT_LEAD_TEMPLATE = """You are a lead agent coordinating sub-agents via delegate_task.

For simple questions you can answer directly without delegating.
For complex tasks, delegate to the best-fit agent.

Available agent types:
{{AGENT_LIST}}

## Guidelines
- Break complex requests into subtasks and pick the best agent for each
- Use sequential delegation (wait_for_result) when the next step depends
  on a prior result; use parallel for independent subtasks
- Write prompts that are direct and specific — reference concrete file
  paths, function names, and line numbers instead of abstract descriptions.
  Bad: "find the authentication logic"
  Good: "read src/auth/LoginService.kt and list all public methods"
- If a subagent returns a blank or empty result, re-delegate the same
  task and ask it to provide its findings explicitly
- If a subagent returns an error or times out, retry with a different
  agent or adjust the prompt before giving up
- Synthesize all results into a clear, complete answer — reconcile any
  conflicts and flag uncertainties

Complete the full task without stopping for confirmation."""

    /**
     * Load all agent definitions: built-ins from classpath resources,
     * plus custom `.agent.md` files from project and user directories.
     */
    override fun loadAll(projectBasePath: String?): List<AgentDefinition> {
        val agents = loadBuiltIn().toMutableList()

        // Project-level custom agents
        if (projectBasePath != null) {
            val projectAgentDir = StoragePaths.projectAgents(projectBasePath)
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

        // .github/agents/ (SpecKit and other GitHub-convention agents)
        if (projectBasePath != null) {
            val githubAgentDir = File(projectBasePath, ".github/agents")
            log.info("Scanning .github/agents at: ${githubAgentDir.absolutePath} (exists=${githubAgentDir.isDirectory})")
            if (githubAgentDir.isDirectory) {
                githubAgentDir.listFiles { f -> isAgentFile(f) }?.forEach { file ->
                    try {
                        val agent = parseAgentFile(file, AgentSource.CUSTOM_PROJECT)
                        if (agent != null) {
                            agents.add(agent)
                            log.info("Loaded .github agent: ${agent.agentType} from ${file.name}")
                        }
                    } catch (e: Exception) {
                        log.warn("Failed to parse .github agent file ${file.name}: ${e.message}")
                    }
                }
            }
        }

        // User-level custom agents
        val userAgentDir = StoragePaths.agents()
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

        // Deduplicate: later sources override earlier ones (custom overrides built-in).
        // Use case-insensitive matching since findByType is case-insensitive.
        val seen = mutableMapOf<String, Int>() // lowercase agentType → index in agents list
        val deduplicated = mutableListOf<AgentDefinition>()
        for (agent in agents) {
            val key = agent.agentType.lowercase()
            val existingIdx = seen[key]
            if (existingIdx != null) {
                val existing = deduplicated[existingIdx]
                log.warn("Agent '${agent.agentType}' (${agent.source}) overrides '${existing.agentType}' (${existing.source})")
                deduplicated[existingIdx] = agent
            } else {
                seen[key] = deduplicated.size
                deduplicated.add(agent)
            }
        }

        // L9: Filter agents by target platform. This is an IntelliJ/VS Code plugin,
        // so agents targeting "github-copilot" (the GitHub website) are excluded.
        val filtered = deduplicated.filter { agent ->
            val t = agent.target
            if (t != null && t != "vscode") {
                log.info("Excluding agent '${agent.agentType}' — target '$t' does not match this platform")
                false
            } else true
        }

        val builtInCount = filtered.count { it.source == AgentSource.BUILT_IN }
        log.info("Loaded ${filtered.size} agents ($builtInCount built-in, ${filtered.size - builtInCount} custom)")
        return filtered
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

    /** Only accept `.agent.md` files — prevents non-agent markdown from being loaded as agents. */
    private fun isAgentFile(f: File): Boolean =
        f.name.endsWith(".agent.md")

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

        // Parse frontmatter with SnakeYAML
        @Suppress("UNCHECKED_CAST")
        val yaml = if (frontmatter.isNotBlank()) {
            try {
                Yaml().load<Any>(frontmatter) as? Map<String, Any> ?: emptyMap()
            } catch (e: Exception) {
                log.warn("Failed to parse YAML frontmatter for '$defaultName': ${e.message}")
                emptyMap()
            }
        } else emptyMap()

        val name = yaml["name"]?.toString() ?: defaultName
        val description = yaml["description"]?.toString() ?: "Custom agent: $name"
        val tools = yamlStringList(yaml["tools"]) ?: emptyList()
        val model = parseModelString(yaml["model"]?.toString())
        val forkContext = yaml["forkContext"]?.toString()?.lowercase() == "true"
        val background = yaml["background"]?.toString()?.lowercase() == "true"
        val maxTurns = (yaml["maxTurns"] as? Number)?.toInt() ?: 30

        val disableModelInvocation = yaml["disable-model-invocation"]?.toString()?.lowercase() == "true"
        val handoffs = yamlHandoffs(yaml["handoffs"])
        val metadata = yamlStringMap(yaml["metadata"])
        val mcpServers = yamlMcpServers(yaml["mcp-servers"] ?: yaml["mcpServers"])

        // Supervisor fields
        val subagents = yamlStringList(yaml["subagents"])
        val target = yaml["target"]?.toString()?.trim()?.ifBlank { null }

        // M5: Validate required fields
        if (name.isBlank()) {
            log.warn("Skipping agent with blank name from '$defaultName' (source=$source)")
            return null
        }

        // M2: Preserve unknown frontmatter keys for lossless roundtrip
        val extraFrontmatter = yaml.filterKeys { it !in KNOWN_FRONTMATTER_KEYS }
        if (extraFrontmatter.isNotEmpty()) {
            log.info("Agent '$name': preserving ${extraFrontmatter.size} extra frontmatter key(s): ${extraFrontmatter.keys}")
        }

        return AgentDefinition(
            agentType = name,
            whenToUse = description,
            tools = tools,
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
            extraFrontmatter = extraFrontmatter,
        )
    }

    /** Parse a model string from frontmatter into an [AgentModel]. */
    internal fun parseModelString(raw: String?): AgentModel {
        if (raw == null) return AgentModel.INHERIT
        return when (raw.lowercase().trim()) {
            "inherit" -> AgentModel.INHERIT
            "gpt-4.1", "gpt4.1" -> AgentModel.GPT_4_1
            "claude-sonnet-4", "sonnet-4", "sonnet" -> AgentModel.CLAUDE_SONNET_4
            else -> {
                log.warn("Unrecognized model '$raw' in agent frontmatter — falling back to INHERIT")
                AgentModel.INHERIT
            }
        }
    }

    /** Extract a list of strings from a YAML value (List or bracket-delimited string). */
    private fun yamlStringList(value: Any?): List<String>? {
        if (value == null) return null
        if (value is List<*>) return value.map { it.toString().trim() }.filter { it.isNotEmpty() }
        val str = value.toString().trim()
        if (str.startsWith("[")) {
            return str.removeSurrounding("[", "]").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        return listOf(str).filter { it.isNotEmpty() }
    }

    /** Extract a String->String map from a YAML value. */
    @Suppress("UNCHECKED_CAST")
    private fun yamlStringMap(value: Any?): Map<String, String> {
        val map = value as? Map<String, Any> ?: return emptyMap()
        return map.mapValues { it.value.toString() }
    }

    /** Extract mcp-servers config from a YAML map. */
    @Suppress("UNCHECKED_CAST")
    private fun yamlMcpServers(value: Any?): Map<String, McpServerConfig> {
        val servers = value as? Map<String, Any> ?: return emptyMap()
        return servers.mapValues { (serverName, serverValue) ->
            val cfg = serverValue as? Map<String, Any> ?: return@mapValues McpServerConfig()
            val type = cfg["type"]?.toString() ?: "local"
            if (type !in VALID_MCP_TYPES) {
                log.warn("MCP server '$serverName': unrecognized type '$type' (expected one of $VALID_MCP_TYPES)")
            }
            McpServerConfig(
                type = type,
                command = cfg["command"]?.toString() ?: "",
                args = yamlStringList(cfg["args"]) ?: emptyList(),
                tools = yamlStringList(cfg["tools"]),
                env = yamlStringMap(cfg["env"]),
                url = cfg["url"]?.toString() ?: "",
            )
        }
    }

    /**
     * Parse handoffs from YAML. Supports both:
     * - Structured objects: `[{label, agent, prompt, send}]` (SpecKit / Copilot format)
     * - Flat strings: `[explore, plan]` (legacy format, converted to HandoffDefinition)
     */
    @Suppress("UNCHECKED_CAST")
    private fun yamlHandoffs(value: Any?): List<HandoffDefinition> {
        if (value == null) return emptyList()
        val list = value as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            when (item) {
                is Map<*, *> -> {
                    val agent = item["agent"]?.toString() ?: return@mapNotNull null
                    HandoffDefinition(
                        label = item["label"]?.toString() ?: agent,
                        agent = agent,
                        prompt = item["prompt"]?.toString() ?: "",
                        send = item["send"]?.toString()?.lowercase() == "true",
                    )
                }
                is String -> HandoffDefinition(label = item, agent = item)
                else -> null
            }
        }
    }

    /** Case-insensitive agent lookup by type. */
    fun findByType(agentType: String, agents: List<AgentDefinition>): AgentDefinition? {
        return agents.find { it.agentType.equals(agentType, ignoreCase = true) }
    }

    /** Quote a YAML string value if it contains characters that need quoting. */
    private fun yamlQuote(value: String): String {
        // Characters that require quoting in YAML values
        val needsQuoting = value.contains(':') || value.contains('#') ||
            value.contains('{') || value.contains('}') ||
            value.contains('[') || value.contains(']') ||
            value.contains(',') || value.contains('&') ||
            value.contains('*') || value.contains('!') ||
            value.contains('|') || value.contains('>') ||
            value.contains('\'') || value.contains('"') ||
            value.contains('%') || value.contains('@') ||
            value.startsWith(' ') || value.endsWith(' ')
        if (!needsQuoting) return value
        // Use double quotes, escaping internal double quotes and backslashes
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    /**
     * Serialize an unknown frontmatter key-value pair to YAML.
     * Handles strings, numbers, booleans, lists, and maps.
     */
    private fun yamlSerializeEntry(key: String, value: Any, indent: String = ""): String {
        return when (value) {
            is String -> "$indent$key: ${yamlQuote(value)}"
            is Number, is Boolean -> "$indent$key: $value"
            is List<*> -> {
                val items = value.filterNotNull()
                if (items.all { it is String || it is Number || it is Boolean }) {
                    "$indent$key: [${items.joinToString(", ")}]"
                } else {
                    // Block-style list with nested objects
                    val sb = StringBuilder("$indent$key:")
                    for (item in items) {
                        if (item is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val map = item as Map<String, Any>
                            val entries = map.entries.toList()
                            if (entries.isNotEmpty()) {
                                sb.append("\n$indent  - ${entries.first().key}: ${yamlQuote(entries.first().value.toString())}")
                                for (entry in entries.drop(1)) {
                                    sb.append("\n$indent    ${entry.key}: ${yamlQuote(entry.value.toString())}")
                                }
                            }
                        } else {
                            sb.append("\n$indent  - $item")
                        }
                    }
                    sb.toString()
                }
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as Map<String, Any>
                val sb = StringBuilder("$indent$key:")
                for ((k, v) in map) {
                    sb.append("\n${yamlSerializeEntry(k, v, "$indent  ")}")
                }
                sb.toString()
            }
            else -> "$indent$key: ${yamlQuote(value.toString())}"
        }
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
    override fun saveAgent(agent: AgentDefinition, projectBasePath: String, existingFilePath: String?): AgentDefinition {
        val file = if (existingFilePath != null) File(existingFilePath) else {
            File(StoragePaths.projectAgents(projectBasePath), "${agent.agentType}.agent.md")
        }
        val saved = agent.copy(filePath = file.absolutePath)
        writeAgentFile(saved, file)
        return saved
    }

    /**
     * Delete an agent's file from disk.
     */
    override fun deleteAgentFile(filePath: String) {
        val file = File(filePath)
        if (file.exists()) {
            file.delete()
            log.info("Deleted agent file: $filePath")
        }
    }

    /**
     * Find an agent by name (across all sources) and delete its file.
     */
    override fun deleteAgentByName(name: String, projectBasePath: String?) {
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
        sb.appendLine("name: ${yamlQuote(agent.agentType)}")
        sb.appendLine("description: ${yamlQuote(agent.whenToUse)}")
        if (agent.model != AgentModel.INHERIT) {
            sb.appendLine("model: ${modelToString(agent.model)}")
        }
        if (agent.tools.isNotEmpty()) {
            sb.appendLine("tools: [${agent.tools.joinToString(", ")}]")
        }
        if (agent.maxTurns != 30) {
            sb.appendLine("maxTurns: ${agent.maxTurns}")
        }
        if (agent.subagents != null) {
            sb.appendLine("subagents: [${agent.subagents.joinToString(", ")}]")
        }
        if (agent.target != null) {
            sb.appendLine("target: ${yamlQuote(agent.target)}")
        }
        if (agent.forkContext) sb.appendLine("forkContext: true")
        if (agent.background) sb.appendLine("background: true")
        if (agent.disableModelInvocation) sb.appendLine("disable-model-invocation: true")
        if (agent.handoffs.isNotEmpty()) {
            sb.appendLine("handoffs:")
            for (h in agent.handoffs) {
                sb.appendLine("  - label: ${yamlQuote(h.label)}")
                sb.appendLine("    agent: ${yamlQuote(h.agent)}")
                if (h.prompt.isNotBlank()) sb.appendLine("    prompt: ${yamlQuote(h.prompt)}")
                if (h.send) sb.appendLine("    send: true")
            }
        }
        if (agent.mcpServers.isNotEmpty()) {
            sb.appendLine("mcpServers:")
            for ((name, cfg) in agent.mcpServers) {
                sb.appendLine("  $name:")
                if (cfg.type.isNotBlank() && cfg.type != "local") sb.appendLine("    type: ${yamlQuote(cfg.type)}")
                if (cfg.command.isNotBlank()) sb.appendLine("    command: ${yamlQuote(cfg.command)}")
                if (cfg.args.isNotEmpty()) sb.appendLine("    args: [${cfg.args.joinToString(", ")}]")
                if (cfg.url.isNotBlank()) sb.appendLine("    url: ${yamlQuote(cfg.url)}")
                if (cfg.env.isNotEmpty()) {
                    sb.appendLine("    env:")
                    cfg.env.forEach { (k, v) -> sb.appendLine("      $k: ${yamlQuote(v)}") }
                }
                if (cfg.tools != null) sb.appendLine("    tools: [${cfg.tools.joinToString(", ")}]")
            }
        }
        if (agent.metadata.isNotEmpty()) {
            sb.appendLine("metadata:")
            agent.metadata.forEach { (k, v) -> sb.appendLine("  $k: ${yamlQuote(v)}") }
        }
        // M2: Preserve unknown frontmatter keys for lossless roundtrip
        if (agent.extraFrontmatter.isNotEmpty()) {
            for ((key, value) in agent.extraFrontmatter) {
                sb.appendLine(yamlSerializeEntry(key, value))
            }
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
