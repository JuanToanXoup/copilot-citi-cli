package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream

/**
 * MCP stdio transport layer.
 *
 * Reads JSON-RPC messages from stdin and writes responses to stdout.
 * Follows the MCP stdio transport spec: one JSON-RPC message per line,
 * newline-delimited. IntelliJ platform logging is redirected to stderr
 * so stdout remains a clean JSON-RPC channel.
 *
 * This transport runs alongside the existing SSE/HTTP transport —
 * both share the same [JsonRpcHandler] instance.
 */
class StdioMcpTransport(
    private val jsonRpcHandler: JsonRpcHandler,
    private val coroutineScope: CoroutineScope
) {
    private var running = false
    private var job: Job? = null

    // Capture original stdout before any redirection
    private val originalStdout: PrintStream = System.out

    companion object {
        private val LOG = logger<StdioMcpTransport>()
    }

    /**
     * Starts the stdio transport loop.
     *
     * Redirects System.out to stderr so that IntelliJ's internal logging
     * doesn't corrupt the JSON-RPC channel. All MCP responses are written
     * to the captured original stdout.
     */
    fun start() {
        if (running) {
            LOG.warn("Stdio transport already running")
            return
        }

        running = true

        // Redirect System.out to stderr so platform log noise doesn't hit stdout
        System.setOut(System.err)

        LOG.info("Starting MCP stdio transport")

        job = coroutineScope.launch(Dispatchers.IO) {
            val reader = BufferedReader(InputStreamReader(System.`in`))

            try {
                while (running && isActive) {
                    val line = reader.readLine()

                    if (line == null) {
                        // stdin closed — parent process exited
                        LOG.info("Stdin closed, shutting down stdio transport")
                        break
                    }

                    if (line.isBlank()) continue

                    try {
                        val response = jsonRpcHandler.handleRequest(line)
                        if (response != null) {
                            // Write to the original stdout (not the redirected one)
                            synchronized(originalStdout) {
                                originalStdout.println(response)
                                originalStdout.flush()
                            }
                        }
                        // null response = notification (no reply needed per JSON-RPC)
                    } catch (e: Exception) {
                        LOG.error("Error processing stdio request", e)
                        // Write error response to stdout
                        val errorResponse = """{"jsonrpc":"2.0","error":{"code":-32603,"message":"${e.message?.replace("\"", "\\\"") ?: "Internal error"}"}}"""
                        synchronized(originalStdout) {
                            originalStdout.println(errorResponse)
                            originalStdout.flush()
                        }
                    }
                }
            } catch (e: CancellationException) {
                LOG.info("Stdio transport cancelled")
            } catch (e: Exception) {
                LOG.error("Stdio transport error", e)
            } finally {
                running = false
                LOG.info("Stdio transport stopped")
            }
        }
    }

    /**
     * Stops the stdio transport.
     */
    fun stop() {
        LOG.info("Stopping stdio transport")
        running = false
        job?.cancel()
        job = null
    }

    /**
     * Returns whether the stdio transport is currently running.
     */
    fun isRunning(): Boolean = running
}
