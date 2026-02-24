package com.citigroup.copilotchat.tools

import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit

/** Shared constants and helpers used across built-in tool groups. */
object BuiltInToolUtils {

    const val OUTPUT_LIMIT = 4000

    fun runCommand(
        cmd: List<String>,
        stdin: String? = null,
        workingDir: String? = null,
        timeout: Long = 30,
    ): String {
        return try {
            val pb = ProcessBuilder(cmd)
            if (workingDir != null) pb.directory(File(workingDir))
            pb.redirectErrorStream(true)
            val process = pb.start()
            if (stdin != null) {
                process.outputStream.write(stdin.toByteArray())
                process.outputStream.close()
            }
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(timeout, TimeUnit.SECONDS)
            val exitCode = if (process.isAlive) { process.destroyForcibly(); -1 } else process.exitValue()
            val result = output.take(OUTPUT_LIMIT)
            if (exitCode != 0 && result.isBlank()) "Exit code: $exitCode" else result
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // JSON helpers
    fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
    fun JsonObject.strArray(key: String): List<String>? =
        this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
}
