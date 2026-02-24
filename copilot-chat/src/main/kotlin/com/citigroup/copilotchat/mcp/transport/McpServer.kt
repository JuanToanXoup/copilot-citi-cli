package com.citigroup.copilotchat.mcp.transport

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * Stdio MCP server — spawns a process, speaks newline-delimited JSON-RPC 2.0.
 *
 * Protocol logic (initialize, listTools, callTool, request correlation) lives
 * in [McpTransportBase]. This class handles only process lifecycle and stdio I/O.
 */
class McpServer(
    name: String,
    private val command: String,
    private val args: List<String> = emptyList(),
    private val env: Map<String, String> = emptyMap(),
    initTimeout: Long = 60_000,
) : McpTransportBase(name, initTimeout) {

    private var process: Process? = null

    override suspend fun start() {
        val processEnv = System.getenv().toMutableMap()
        processEnv.putAll(env)

        val effectivePath = processEnv["PATH"] ?: System.getenv("PATH") ?: ""
        val resolvedCommand = resolveCommand(command, effectivePath)

        val cmdLine = mutableListOf(resolvedCommand)
        cmdLine.addAll(args)

        log.info("MCP $name: spawning ${cmdLine.joinToString(" ")}")

        val pb = ProcessBuilder(cmdLine)
        pb.environment().clear()
        pb.environment().putAll(processEnv)
        pb.redirectErrorStream(false)

        try {
            process = pb.start()
        } catch (e: Exception) {
            throw RuntimeException(
                "MCP server '$name': failed to start command '${cmdLine.joinToString(" ")}'. " +
                "Is '${command}' installed and on PATH? Error: ${e.message}"
            )
        }

        // Verify process didn't exit immediately
        delay(200)
        if (process?.isAlive != true) {
            val exitCode = process?.exitValue()
            throw RuntimeException(
                "MCP server '$name': process exited immediately (code=$exitCode). " +
                "Command: ${cmdLine.joinToString(" ")}"
            )
        }

        // Reader coroutine — reads newline-delimited JSON-RPC from stdout
        scope.launch {
            try {
                process!!.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        try {
                            val msg = json.parseToJsonElement(line).jsonObject
                            dispatchMessage(msg)
                        } catch (e: Exception) {
                            log.debug("MCP $name: failed to parse line: ${line.take(200)}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    log.debug("MCP $name: reader ended: ${e.message}")
                }
            }
        }

        // Drain stderr — log warnings, filter noise
        scope.launch {
            try {
                process!!.errorStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("at ") || trimmed.startsWith("Caused by:") || trimmed.startsWith("...")) continue
                        if ("WARN" in line || "SEVERE" in line) continue
                        if (line.startsWith("Java HotSpot") || line.startsWith("java.") ||
                            line.startsWith("com.intellij") || line.startsWith("org.jetbrains") ||
                            line.startsWith("kotlin.") || line.startsWith("kotlinx.") ||
                            line.startsWith("sun.") || line.startsWith("jdk.")
                        ) continue
                        log.info("MCP $name stderr: $line")
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun stop() {
        scope.coroutineContext.cancelChildren()
        process?.let {
            try {
                it.destroy()
                it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (it.isAlive) it.destroyForcibly()
            } catch (_: Exception) {}
        }
        process = null
        cancelPending()
    }

    override fun sendRaw(msg: JsonObject) {
        try {
            val line = msg.toString() + "\n"
            process?.outputStream?.let {
                it.write(line.toByteArray(Charsets.UTF_8))
                it.flush()
            }
        } catch (_: Exception) {}
    }

    private fun resolveCommand(cmd: String, path: String): String {
        val pathDirs = path.split(java.io.File.pathSeparator)
        for (dir in pathDirs) {
            if (dir.isBlank()) continue
            val file = java.io.File(dir, cmd)
            if (file.canExecute()) {
                log.info("MCP $name: resolved '$cmd' -> ${file.absolutePath}")
                return file.absolutePath
            }
            for (ext in listOf(".cmd", ".bat", ".exe")) {
                val withExt = java.io.File(dir, cmd + ext)
                if (withExt.canExecute()) {
                    log.info("MCP $name: resolved '$cmd' -> ${withExt.absolutePath}")
                    return withExt.absolutePath
                }
            }
        }
        log.warn("MCP $name: '$cmd' not found on PATH, using as-is")
        return cmd
    }
}
