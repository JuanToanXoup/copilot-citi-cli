package com.citigroup.copilotchat.config

import java.io.File

/**
 * Centralizes all storage path resolution for copilot-chat.
 *
 * User-level data lives under [userRoot] (default `~/.copilot-chat/`).
 * Project-level data lives under `<projectBasePath>/.copilot-chat/`.
 *
 * All callers should use this object instead of hardcoding paths.
 */
object StoragePaths {

    private const val DIR_NAME = ".copilot-chat"

    /** Override for testing — when non-null, replaces `~/.copilot-chat`. */
    @Volatile
    var userRootOverride: File? = null

    /** The user-level storage root (`~/.copilot-chat` by default). */
    val userRoot: File
        get() = userRootOverride ?: File(System.getProperty("user.home"), DIR_NAME)

    // ── User-level directories ──

    fun agents(): File = File(userRoot, "agents")
    fun teams(teamName: String): File = File(userRoot, "teams/$teamName")
    fun teamInboxes(teamName: String): File = File(userRoot, "teams/$teamName/inboxes")
    fun vectorStore(): File = File(userRoot, "vector-store")
    fun models(): File = File(userRoot, "models")
    fun nativeLib(dirName: String): File = File(userRoot, "native/$dirName")
    fun memories(): File = File(userRoot, "memories")
    fun playwright(): File = File(userRoot, "playwright")

    // ── Project-level directories ──

    fun projectRoot(projectBasePath: String): File = File(projectBasePath, DIR_NAME)
    fun projectAgents(projectBasePath: String): File = File(projectBasePath, "$DIR_NAME/agents")
    fun projectWorktrees(projectBasePath: String): File = File(projectBasePath, "$DIR_NAME/worktrees")
    fun projectWorktree(projectBasePath: String, agentId: String): File =
        File(projectBasePath, "$DIR_NAME/worktrees/$agentId")
}
