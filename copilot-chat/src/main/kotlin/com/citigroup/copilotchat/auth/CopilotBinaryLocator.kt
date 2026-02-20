package com.citigroup.copilotchat.auth

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Auto-discovers the copilot-language-server binary from JetBrains plugin installs.
 * Port of platform_utils.py discover_copilot_binary().
 */
object CopilotBinaryLocator {

    private val log = Logger.getInstance(CopilotBinaryLocator::class.java)

    fun discover(): String? {
        val candidates = mutableListOf<File>()
        for (pattern in searchGlobs()) {
            candidates.addAll(expandGlob(pattern))
        }
        if (candidates.isEmpty()) return null
        // Pick newest by modification time
        candidates.sortByDescending { it.lastModified() }
        return candidates.first().absolutePath
    }

    private fun searchGlobs(): List<String> {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")

        return when {
            os.contains("win") -> {
                val local = System.getenv("LOCALAPPDATA")
                    ?: "$home/AppData/Local"
                listOf(
                    "$local/JetBrains/*/plugins/github-copilot-intellij/copilot-agent/native/win32-x64/copilot-language-server.exe",
                    "$home/.vscode/extensions/github.copilot-*/dist/copilot-language-server.exe",
                )
            }
            os.contains("linux") -> listOf(
                "$home/.local/share/JetBrains/*/plugins/github-copilot-intellij/copilot-agent/native/linux-x64/copilot-language-server",
                "$home/.vscode/extensions/github.copilot-*/dist/copilot-language-server",
            )
            else -> {
                // macOS
                val arch = if (System.getProperty("os.arch") == "aarch64") "darwin-arm64" else "darwin-x64"
                val appSupport = "$home/Library/Application Support"
                listOf(
                    "$appSupport/JetBrains/*/plugins/github-copilot-intellij/copilot-agent/native/$arch/copilot-language-server",
                    "$home/.vscode/extensions/github.copilot-*/dist/copilot-language-server",
                )
            }
        }
    }

    private fun expandGlob(pattern: String): List<File> {
        // Find the first glob character and split into base dir + glob part
        val firstGlob = pattern.indexOfFirst { it == '*' || it == '?' }
        if (firstGlob == -1) {
            val f = File(pattern)
            return if (f.exists()) listOf(f) else emptyList()
        }

        val lastSepBeforeGlob = pattern.lastIndexOf(File.separatorChar, firstGlob)
        val baseDir = if (lastSepBeforeGlob >= 0) pattern.substring(0, lastSepBeforeGlob) else "."
        val globPart = if (lastSepBeforeGlob >= 0) pattern.substring(lastSepBeforeGlob + 1) else pattern

        val basePath = Paths.get(baseDir)
        if (!Files.isDirectory(basePath)) return emptyList()

        val matcher = FileSystems.getDefault().getPathMatcher("glob:$globPart")
        val results = mutableListOf<File>()
        try {
            Files.walk(basePath, globPart.count { it == '*' || it == File.separatorChar } + 2).use { stream ->
                stream.forEach { path ->
                    val relative = basePath.relativize(path)
                    if (matcher.matches(relative) && Files.isRegularFile(path)) {
                        results.add(path.toFile())
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("Glob expansion failed for $pattern: ${e.message}")
        }
        return results
    }
}
