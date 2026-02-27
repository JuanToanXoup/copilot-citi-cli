package com.speckit.plugin.tools

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import java.io.File

/**
 * Resolves agent resources with project-first, classpath-fallback semantics.
 */
object ResourceLoader {

    private val BUNDLED_AGENTS = listOf(
        "speckit.analyze.agent.md",
        "speckit.checklist.agent.md",
        "speckit.clarify.agent.md",
        "speckit.constitution.agent.md",
        "speckit.coverage.agent.md",
        "speckit.implement.agent.md",
        "speckit.plan.agent.md",
        "speckit.specify.agent.md",
        "speckit.tasks.agent.md",
        "speckit.taskstoissues.agent.md",
    )

    /**
     * Read an agent definition file.
     * Checks .github/agents/ in the project first, then falls back to bundled.
     */
    fun readAgent(basePath: String, agentFileName: String): String? {
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, ".github/agents/$agentFileName"))
        if (vFile != null && !vFile.isDirectory) return VfsUtilCore.loadText(vFile)

        return readClasspathResource("/speckit/agents/$agentFileName")
    }

    /**
     * List all available agent file names.
     * Merges project .github/agents/ with bundled agents (project files take priority).
     */
    fun listAgents(basePath: String): List<String> {
        val agentsDir = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, ".github/agents"))
        val projectAgents = if (agentsDir != null && agentsDir.isDirectory) {
            agentsDir.children
                .filter { !it.isDirectory && it.name.endsWith(".agent.md") }
                .map { it.name }
                .toSet()
        } else {
            emptySet()
        }

        return (projectAgents + BUNDLED_AGENTS).sorted()
    }

    // ── Discovery templates ────────────────────────────────────────────────────

    private val BUNDLED_DISCOVERIES = listOf(
        "constitution.discovery.md",
    )

    fun readDiscovery(basePath: String, fileName: String): String? {
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, ".github/discoveries/$fileName"))
        if (vFile != null && !vFile.isDirectory) return VfsUtilCore.loadText(vFile)
        return readClasspathResource("/speckit/discoveries/$fileName")
    }

    fun listDiscoveries(basePath: String): List<String> {
        val discDir = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, ".github/discoveries"))
        val projectDiscoveries = if (discDir != null && discDir.isDirectory) {
            discDir.children
                .filter { !it.isDirectory && it.name.endsWith(".discovery.md") }
                .map { it.name }
                .toSet()
        } else {
            emptySet()
        }
        return (projectDiscoveries + BUNDLED_DISCOVERIES).sorted()
    }

    private fun readClasspathResource(path: String): String? {
        return ResourceLoader::class.java.getResourceAsStream(path)?.use { stream ->
            stream.bufferedReader().readText()
        }
    }
}
