package com.citigroup.copilotchat.orchestrator

/**
 * Built-in worker preset catalog for automatic worker generation.
 * The orchestrator planner selects from these presets based on the goal,
 * similar to Claude Code's agent types.
 */
object WorkerPresets {

    data class Preset(
        val role: String,
        val description: String,
        val systemPrompt: String,
        val agentMode: Boolean,
        val toolsEnabled: List<String>?,  // null = all tools
    )

    private val presets = listOf(
        Preset(
            role = "coder",
            description = "Full-stack developer that can read, write, and modify code files",
            systemPrompt = "You are an expert software developer. Write clean, well-structured code. " +
                "Use the available tools to read existing code, understand the codebase, and make changes. " +
                "Always verify your changes compile and are consistent with the project's style.",
            agentMode = true,
            toolsEnabled = null,
        ),
        Preset(
            role = "reviewer",
            description = "Code reviewer that reads code and provides analysis (read-only)",
            systemPrompt = "You are a thorough code reviewer. Analyze code for bugs, security issues, " +
                "performance problems, and style violations. Provide actionable feedback. " +
                "You have read-only access — do not attempt to modify files.",
            agentMode = true,
            toolsEnabled = listOf("read_file", "grep_search", "list_dir", "codebase_search", "file_search"),
        ),
        Preset(
            role = "tester",
            description = "Test engineer that writes and runs tests",
            systemPrompt = "You are a test engineer. Write comprehensive unit tests, integration tests, " +
                "and verify existing tests pass. Use the project's existing test framework and conventions.",
            agentMode = true,
            toolsEnabled = null,
        ),
        Preset(
            role = "researcher",
            description = "Codebase researcher that explores and documents findings (read-only)",
            systemPrompt = "You are a codebase researcher. Explore the project structure, read files, " +
                "search for patterns, and document your findings clearly. " +
                "You have read-only access — do not attempt to modify files.",
            agentMode = true,
            toolsEnabled = listOf("read_file", "grep_search", "list_dir", "codebase_search", "file_search"),
        ),
        Preset(
            role = "documentation_writer",
            description = "Technical writer that creates and updates documentation",
            systemPrompt = "You are a technical documentation writer. Create clear, accurate documentation " +
                "including READMEs, API docs, and inline comments. Follow the project's existing documentation style.",
            agentMode = true,
            toolsEnabled = null,
        ),
    )

    private val presetsByRole = presets.associateBy { it.role }

    /** Look up a preset by role name (case-insensitive). */
    fun findByRole(role: String): Preset? =
        presetsByRole[role.lowercase()]

    /** All available preset roles. */
    val roles: List<String> get() = presets.map { it.role }

    /** Build a text description of the catalog for the planning prompt. */
    fun catalogDescription(): String = presets.joinToString("\n") { p ->
        "- ${p.role}: ${p.description}" +
            if (p.toolsEnabled != null) " [read-only]" else " [full tools]"
    }
}
