package com.citigroup.copilotchat.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import java.io.File

/**
 * Manages a shared Playwright installation at ~/.copilot-chat/playwright/.
 * Both the Recorder tab and the Playwright MCP server use this so they
 * share the same Chromium binary.
 */
object PlaywrightManager {

    private val log = Logger.getInstance(PlaywrightManager::class.java)

    val home: File = File(System.getProperty("user.home"), ".copilot-chat/playwright")
    val nodeModules: File get() = File(home, "node_modules")
    val playwrightCli: File get() = File(nodeModules, "playwright/cli.js")
    val mcpCli: File get() = File(nodeModules, "@playwright/mcp/cli.js")

    val isInstalled: Boolean get() = playwrightCli.exists() && mcpCli.exists()

    /** Listener for install progress updates. Called on arbitrary threads. */
    var onStatus: ((String) -> Unit)? = null

    /**
     * Ensure both playwright and @playwright/mcp are installed in the managed
     * directory. Downloads Chromium if needed. Must be called from a background
     * thread. Returns true on success.
     */
    @Synchronized
    fun ensureInstalled(): Boolean {
        if (isInstalled) return true
        return install()
    }

    private fun install(): Boolean {
        home.mkdirs()

        val packageJson = File(home, "package.json")
        if (!packageJson.exists()) {
            packageJson.writeText("""{"private":true}""")
        }

        val npm = if (SystemInfo.isWindows) "npm.cmd" else "npm"

        // Install both packages in the same node_modules
        onStatus?.invoke("Installing Playwright packages...")
        val installProc = ProcessBuilder(npm, "install", "playwright", "@playwright/mcp@latest")
            .directory(home)
            .redirectErrorStream(true)
            .start()
        val installOutput = installProc.inputStream.bufferedReader().readText()
        installProc.waitFor()
        if (installProc.exitValue() != 0) {
            log.warn("npm install failed:\n$installOutput")
            onStatus?.invoke("Failed to install Playwright.")
            return false
        }

        // Download Chromium browser binary
        onStatus?.invoke("Downloading Chromium browser...")
        val browsersProc = ProcessBuilder("node", playwrightCli.absolutePath, "install", "chromium")
            .directory(home)
            .redirectErrorStream(true)
            .start()
        val browsersOutput = browsersProc.inputStream.bufferedReader().readText()
        browsersProc.waitFor()
        if (browsersProc.exitValue() != 0) {
            log.warn("playwright install chromium failed:\n$browsersOutput")
            onStatus?.invoke("Failed to download Chromium.")
            return false
        }

        onStatus?.invoke(" ")
        return true
    }
}
