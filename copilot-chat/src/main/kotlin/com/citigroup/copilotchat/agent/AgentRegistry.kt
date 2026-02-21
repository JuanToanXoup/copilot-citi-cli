package com.citigroup.copilotchat.agent

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Registry of agent definitions (built-in + custom from .md files).
 * Provides agent loading and lookup.
 */
object AgentRegistry {

    private val log = Logger.getInstance(AgentRegistry::class.java)

    /** 4 built-in agents matching Claude Code's architecture. */
    private val builtInAgents = listOf(
        AgentDefinition(
            agentType = "Explore",
            whenToUse = "Fast agent for exploring codebases. Use for file searches, keyword searches, and codebase questions. Specify thoroughness: quick, medium, or very thorough.",
            tools = listOf("ide", "read_file", "list_dir", "grep_search", "file_search"),
            disallowedTools = listOf("delegate_task", "create_team", "send_message", "delete_team"),
            model = AgentModel.HAIKU,
            systemPrompt = "You are an Explore agent specialized for fast codebase exploration. Search for files, read code, and answer questions about the codebase. Do NOT modify any files.",
            maxTurns = 15,
        ),
        AgentDefinition(
            agentType = "Plan",
            whenToUse = "Software architect agent for designing implementation plans. Use for planning strategy, identifying critical files, and considering architectural trade-offs.",
            tools = listOf("ide", "read_file", "list_dir", "grep_search", "file_search"),
            disallowedTools = listOf("delegate_task", "create_team", "send_message", "delete_team"),
            model = AgentModel.INHERIT,
            systemPrompt = "You are a Plan agent specialized for designing implementation approaches. Explore the codebase, identify patterns, and return a step-by-step plan. Do NOT modify any files.",
            maxTurns = 20,
        ),
        AgentDefinition(
            agentType = "Bash",
            whenToUse = "Command execution specialist for running bash commands. Use for git operations, builds, tests, and terminal tasks.",
            tools = listOf("run_in_terminal"),
            disallowedTools = listOf("delegate_task", "create_team", "send_message", "delete_team"),
            model = AgentModel.INHERIT,
            systemPrompt = "You are a Bash agent specialized for command execution. Run bash commands to accomplish the requested task. Only use the run_in_terminal tool.",
            maxTurns = 15,
        ),
        AgentDefinition(
            agentType = "general-purpose",
            whenToUse = "General-purpose agent for complex multi-step tasks. Has access to all tools. Use when no specialized agent fits.",
            tools = null, // all tools
            disallowedTools = listOf("delegate_task", "create_team", "send_message", "delete_team"),
            model = AgentModel.INHERIT,
            systemPrompt = "You are a general-purpose agent. Complete the requested task using any tools available to you.",
            maxTurns = 30,
        ),
    )

    /**
     * Load all agent definitions: built-ins plus custom .md files
     * from the project's .claude/agents/ directory and ~/.claude/agents/.
     */
    fun loadAll(projectBasePath: String?): List<AgentDefinition> {
        val agents = builtInAgents.toMutableList()

        // Project-level custom agents
        if (projectBasePath != null) {
            val projectAgentDir = File(projectBasePath, ".claude/agents")
            if (projectAgentDir.isDirectory) {
                projectAgentDir.listFiles { f -> f.extension == "md" }?.forEach { file ->
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
        val userAgentDir = File(System.getProperty("user.home"), ".claude/agents")
        if (userAgentDir.isDirectory) {
            userAgentDir.listFiles { f -> f.extension == "md" }?.forEach { file ->
                try {
                    val agent = parseAgentFile(file, AgentSource.CUSTOM_USER)
                    if (agent != null) agents.add(agent)
                } catch (e: Exception) {
                    log.warn("Failed to parse user agent file ${file.name}: ${e.message}")
                }
            }
        }

        log.info("Loaded ${agents.size} agents (${builtInAgents.size} built-in, ${agents.size - builtInAgents.size} custom)")
        return agents
    }

    /**
     * Parse a .md agent file with optional YAML frontmatter.
     * Format:
     * ```
     * ---
     * name: my-agent
     * description: Does something cool
     * tools: [read_file, grep_search]
     * model: haiku
     * maxTurns: 10
     * ---
     * System prompt body here...
     * ```
     */
    internal fun parseAgentFile(file: File, source: AgentSource): AgentDefinition? {
        val content = file.readText()
        val frontmatterRegex = Regex("^---\\n(.*?)\\n---\\n", RegexOption.DOT_MATCHES_ALL)
        val match = frontmatterRegex.find(content)

        val frontmatter = match?.groupValues?.get(1) ?: ""
        val body = if (match != null) content.substring(match.range.last + 1).trim() else content.trim()

        if (body.isBlank() && frontmatter.isBlank()) return null

        // Parse frontmatter as simple key: value pairs
        val props = mutableMapOf<String, String>()
        for (line in frontmatter.lines()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                props[key] = value
            }
        }

        val name = props["name"] ?: file.nameWithoutExtension
        val description = props["description"] ?: "Custom agent: $name"
        val toolsRaw = props["tools"]
        val tools = if (toolsRaw != null) {
            toolsRaw.removeSurrounding("[", "]").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else null
        val model = when (props["model"]?.lowercase()) {
            "haiku" -> AgentModel.HAIKU
            "sonnet" -> AgentModel.SONNET
            "opus" -> AgentModel.OPUS
            else -> AgentModel.INHERIT
        }
        val forkContext = props["forkContext"]?.lowercase() == "true"
        val background = props["background"]?.lowercase() == "true"
        val maxTurns = props["maxTurns"]?.toIntOrNull() ?: 30

        return AgentDefinition(
            agentType = name,
            whenToUse = description,
            tools = tools,
            disallowedTools = listOf("delegate_task", "create_team", "send_message", "delete_team"),
            source = source,
            model = model,
            systemPrompt = body,
            forkContext = forkContext,
            background = background,
            maxTurns = maxTurns,
        )
    }

    /** Case-insensitive agent lookup by type. */
    fun findByType(agentType: String, agents: List<AgentDefinition>): AgentDefinition? {
        return agents.find { it.agentType.equals(agentType, ignoreCase = true) }
    }
}
