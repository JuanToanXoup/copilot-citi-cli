package com.github.hechtcarmel.jetbrainsindexmcpplugin.startup

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Headless entry point for running the MCP server without the IDE GUI.

 * Usage:
 *   idea.sh mcp-stdio /path/to/project
 *
 * This starts IntelliJ headlessly, opens the specified project, waits for
 * indexing to complete, then starts the stdio MCP transport. The SSE/HTTP
 * transport also starts (via McpServerService init) so both are available.
 *
 * The process stays alive until stdin is closed (parent process exits)
 * or a SIGTERM is received.
 */
class HeadlessMcpStarter : ApplicationStarter {

    companion object {
        private val LOG = logger<HeadlessMcpStarter>()
    }

    override val commandName: String = "mcp-stdio"

    override val requiredModality: Int
        get() = ApplicationStarter.NOT_IN_EDT

    override fun main(args: List<String>) {
        if (args.size < 2) {
            System.err.println("Usage: idea mcp-stdio <project-path>")
            System.err.println()
            System.err.println("Starts the Index MCP Server in headless stdio mode.")
            System.err.println("The MCP server reads JSON-RPC from stdin and writes to stdout.")
            System.err.println("The SSE/HTTP transport is also available on the configured port.")
            exitProcess(1)
        }

        val projectPath = args[1]
        LOG.info("Headless MCP starter: opening project at $projectPath")

        try {
            // Open the project
            val project = ProjectManagerEx.getInstanceEx().openProject(
                Path.of(projectPath),
                com.intellij.ide.impl.OpenProjectTask.build()
            )

            if (project == null) {
                System.err.println("ERROR: Failed to open project at $projectPath")
                LOG.error("Failed to open project at $projectPath")
                exitProcess(1)
            }

            LOG.info("Project opened: ${project.name}")
            System.err.println("Project opened: ${project.name}")

            // Wait for indexing to complete
            System.err.println("Waiting for indexing to complete...")
            val indexingLatch = CountDownLatch(1)

            ApplicationManager.getApplication().invokeAndWait {
                DumbService.getInstance(project).runWhenSmart {
                    LOG.info("Indexing complete for ${project.name}")
                    System.err.println("Indexing complete.")
                    indexingLatch.countDown()
                }
            }

            indexingLatch.await()

            // Get the McpServerService (triggers init → SSE server starts)
            val mcpService = McpServerService.getInstance()
            val serverUrl = mcpService.getServerUrl()

            if (serverUrl != null) {
                System.err.println("SSE transport available at: $serverUrl")
            } else {
                System.err.println("SSE transport not available (port may be in use). Stdio transport will still work.")
            }

            // Start stdio transport (the primary transport for headless mode)
            mcpService.startStdioTransport()

            System.err.println("MCP stdio transport started. Reading from stdin...")
            System.err.println("Tools registered: ${mcpService.getToolRegistry().getAllTools().size}")

            // Keep the process alive until stdin closes or process is killed
            // The StdioMcpTransport loop handles stdin reading.
            // We just need to block the main thread.
            val shutdownLatch = CountDownLatch(1)

            Runtime.getRuntime().addShutdownHook(Thread {
                LOG.info("Shutdown hook triggered")
                System.err.println("Shutting down...")
                mcpService.stopStdioTransport()
                ProjectManager.getInstance().closeAndDispose(project)
                shutdownLatch.countDown()
            })

            // Block until shutdown
            shutdownLatch.await()

        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.error("Headless MCP starter failed", e)
            System.err.println("ERROR: ${e.message}")
            exitProcess(1)
        }
    }
}
