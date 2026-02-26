package com.speckit.plugin.installer

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.speckit.plugin.tools.ResourceLoader
import java.io.File

/**
 * Scaffolds bundled `.agent.md` files from the plugin JAR to
 * `.github/agents/` in the project directory.
 *
 * - Idempotent: never overwrites existing files (user overrides preserved)
 * - Creates `.github/agents/` directory if needed
 * - Uses the same VFS WriteCommandAction pattern as SpeckitWriteMemory
 */
object AgentScaffolder {

    private val log = Logger.getInstance(AgentScaffolder::class.java)

    fun scaffold(project: Project) {
        val basePath = project.basePath ?: run {
            log.warn("No base path for project ${project.name} — skipping agent scaffold")
            return
        }

        val agentsDir = File(basePath, ".github/agents")
        val agentNames = ResourceLoader.BUNDLED_AGENTS

        // Quick check: if all files already exist, skip entirely
        val missing = agentNames.filter { !File(agentsDir, it).exists() }
        if (missing.isEmpty()) {
            log.info("All ${agentNames.size} agent files already present — skipping scaffold")
            return
        }

        log.info("Scaffolding ${missing.size} agent file(s) to ${agentsDir.path}")

        runInEdt {
            if (project.isDisposed) return@runInEdt
            WriteCommandAction.runWriteCommandAction(project) {
                try {
                    // Ensure .github/agents/ directory exists
                    var parentVFile = LocalFileSystem.getInstance()
                        .refreshAndFindFileByIoFile(agentsDir)
                    if (parentVFile == null) {
                        parentVFile = VfsUtil.createDirectories(agentsDir.path)
                    }
                    if (parentVFile == null) {
                        log.warn("Failed to create directory: ${agentsDir.path}")
                        return@runWriteCommandAction
                    }
                    parentVFile.refresh(false, false)

                    for (name in missing) {
                        // Double-check under VFS (file may have appeared after initial check)
                        if (parentVFile.findChild(name) != null) continue

                        val content = ResourceLoader::class.java
                            .getResourceAsStream("/speckit/agents/$name")
                            ?.use { it.bufferedReader().readText() }
                        if (content == null) {
                            log.warn("Bundled resource not found: /speckit/agents/$name")
                            continue
                        }

                        val vFile = parentVFile.createChildData(this@AgentScaffolder, name)
                        VfsUtil.saveText(vFile, content)
                        log.info("Scaffolded agent file: ${vFile.path}")
                    }
                } catch (e: Exception) {
                    log.warn("Agent scaffold failed", e)
                }
            }
        }
    }
}
