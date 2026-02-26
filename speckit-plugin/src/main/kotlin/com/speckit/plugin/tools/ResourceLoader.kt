package com.speckit.plugin.tools

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import java.io.File

/**
 * Resolves speckit resources with project-first, classpath-fallback semantics.
 *
 * Lookup order:
 *   1. Project filesystem (basePath + relativePath) via IntelliJ VFS
 *   2. Plugin JAR classpath (/speckit/ + mapped path)
 *
 * This allows any project to work out of the box (using bundled defaults)
 * while projects with speckit initialized get their customized versions.
 */
object ResourceLoader {

    /** Bundled agent file names (classpath: /speckit/agents/) */
    private val BUNDLED_AGENTS = listOf(
        "speckit.analyze.agent.md",
        "speckit.checklist.agent.md",
        "speckit.clarify.agent.md",
        "speckit.constitution.agent.md",
        "speckit.implement.agent.md",
        "speckit.plan.agent.md",
        "speckit.specify.agent.md",
        "speckit.tasks.agent.md",
        "speckit.taskstoissues.agent.md",
    )

    /** Bundled template file names (classpath: /speckit/templates/) */
    private val BUNDLED_TEMPLATES = listOf(
        "agent-file-template.md",
        "checklist-template.md",
        "constitution-template.md",
        "plan-template.md",
        "spec-template.md",
        "tasks-template.md",
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
     * Read a template file.
     * Checks .specify/templates/ in the project first, then falls back to bundled.
     */
    fun readTemplate(basePath: String, templateName: String): String? {
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, ".specify/templates/$templateName"))
        if (vFile != null && !vFile.isDirectory) return VfsUtilCore.loadText(vFile)

        return readClasspathResource("/speckit/templates/$templateName")
    }

    // Read any speckit-relative file with project-first, classpath-fallback.
    // Maps known paths to their classpath equivalents:
    //   .github/agents/       -> /speckit/agents/
    //   .specify/templates/   -> /speckit/templates/
    //   .specify/memory/      -> project only (no bundled fallback)
    fun readFile(basePath: String, relativePath: String): String? {
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, relativePath))
        if (vFile != null && !vFile.isDirectory) return VfsUtilCore.loadText(vFile)

        // Map to classpath path for known resource directories
        val classpathPath = mapToClasspath(relativePath) ?: return null
        return readClasspathResource(classpathPath)
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

        val bundled = BUNDLED_AGENTS.toSet()

        return (projectAgents + bundled).sorted()
    }

    /**
     * List all available template file names.
     * Merges project .specify/templates/ with bundled templates.
     */
    fun listTemplates(basePath: String): List<String> {
        val templatesDir = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, ".specify/templates"))
        val projectTemplates = if (templatesDir != null && templatesDir.isDirectory) {
            templatesDir.children
                .filter { !it.isDirectory }
                .map { it.name }
                .toSet()
        } else {
            emptySet()
        }

        val bundled = BUNDLED_TEMPLATES.toSet()

        return (projectTemplates + bundled).sorted()
    }

    /**
     * Check if a script exists in the project's .specify/scripts/bash/ directory.
     */
    fun hasScript(basePath: String, scriptName: String): Boolean {
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, ".specify/scripts/bash/$scriptName"))
        return vFile != null && !vFile.isDirectory
    }

    private fun mapToClasspath(relativePath: String): String? {
        return when {
            relativePath.startsWith(".github/agents/") ->
                "/speckit/agents/" + relativePath.removePrefix(".github/agents/")
            relativePath.startsWith(".specify/templates/") ->
                "/speckit/templates/" + relativePath.removePrefix(".specify/templates/")
            else -> null // No classpath fallback for other paths (memory, scripts, etc.)
        }
    }

    private fun readClasspathResource(path: String): String? {
        return ResourceLoader::class.java.getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
    }
}
