package com.citigroup.copilotchat.browser

import com.citigroup.copilotchat.config.CopilotChatSettings
import com.citigroup.copilotchat.config.StoragePaths
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

    val home: File = StoragePaths.playwright()
    val nodeModules: File get() = File(home, "node_modules")
    val playwrightCli: File get() = File(nodeModules, "playwright/cli.js")
    val mcpCli: File get() = File(nodeModules, "@playwright/mcp/cli.js")

    val isInstalled: Boolean get() = playwrightCli.exists() && mcpCli.exists()

    /** Listener for install progress updates. Called on arbitrary threads. */
    var onStatus: ((String) -> Unit)? = null

    /**
     * Build environment map with proxy and augmented PATH for spawning
     * npm/node processes from inside IntelliJ (which has a minimal PATH).
     */
    fun buildProcessEnv(): MutableMap<String, String> {
        val env = System.getenv().toMutableMap()

        // Inject proxy so npm can reach registries behind corporate proxies
        val proxyUrl = CopilotChatSettings.getInstance().proxyUrl
        if (proxyUrl.isNotBlank()) {
            env["HTTP_PROXY"] = proxyUrl
            env["HTTPS_PROXY"] = proxyUrl
            env["http_proxy"] = proxyUrl
            env["https_proxy"] = proxyUrl
        }

        // Augment PATH — IntelliJ on macOS often has a minimal PATH
        val extraPaths = listOf(
            "/usr/local/bin",
            "/opt/homebrew/bin",
            "/opt/homebrew/sbin",
            "${System.getProperty("user.home")}/.volta/bin",
            "${System.getProperty("user.home")}/.local/bin",
        )
        val currentPath = env["PATH"] ?: ""
        val currentDirs = currentPath.split(File.pathSeparator).toSet()

        // Also scan for nvm node versions
        val nvmDir = File(System.getProperty("user.home"), ".nvm/versions/node")
        val nvmBins = if (nvmDir.isDirectory) {
            nvmDir.listFiles()
                ?.filter { it.isDirectory }
                ?.map { File(it, "bin").absolutePath }
                ?.filter { File(it).isDirectory }
                ?: emptyList()
        } else emptyList()

        val toAdd = (extraPaths + nvmBins).filter { it !in currentDirs && File(it).isDirectory }
        if (toAdd.isNotEmpty()) {
            env["PATH"] = (toAdd + currentPath).joinToString(File.pathSeparator)
        }

        return env
    }

    /**
     * Resolve a command name (like "npm" or "node") using the augmented PATH.
     */
    fun resolveCommand(cmd: String, env: Map<String, String> = buildProcessEnv()): String {
        val path = env["PATH"] ?: System.getenv("PATH") ?: return cmd
        for (dir in path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            val file = File(dir, cmd)
            if (file.canExecute()) return file.absolutePath
            if (SystemInfo.isWindows) {
                for (ext in listOf(".cmd", ".bat", ".exe")) {
                    val withExt = File(dir, cmd + ext)
                    if (withExt.canExecute()) return withExt.absolutePath
                }
            }
        }
        return cmd
    }

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

        val env = buildProcessEnv()
        val npm = resolveCommand(if (SystemInfo.isWindows) "npm.cmd" else "npm", env)
        val node = resolveCommand("node", env)

        log.info("PlaywrightManager: npm=$npm, node=$node")

        // Install both packages in the same node_modules
        onStatus?.invoke("Installing Playwright packages...")
        val installProc = ProcessBuilder(npm, "install", "playwright", "@playwright/mcp@latest")
            .directory(home)
            .redirectErrorStream(true)
            .apply { environment().putAll(env) }
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
        val browsersProc = ProcessBuilder(node, playwrightCli.absolutePath, "install", "chromium")
            .directory(home)
            .redirectErrorStream(true)
            .apply { environment().putAll(env) }
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
