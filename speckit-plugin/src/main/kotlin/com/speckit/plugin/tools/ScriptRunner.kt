package com.speckit.plugin.tools

import java.io.File
import java.util.concurrent.TimeUnit

object ScriptRunner {

    fun exec(
        command: List<String>,
        workingDir: String,
        timeoutSeconds: Long = 120
    ): Result {
        val process = ProcessBuilder(command)
            .directory(File(workingDir))
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

        if (!exited) {
            process.destroyForcibly()
            return Result(1, "$output\n[TIMEOUT after ${timeoutSeconds}s]")
        }

        return Result(process.exitValue(), output)
    }

    fun execScript(
        scriptPath: String,
        args: List<String> = emptyList(),
        workingDir: String,
        timeoutSeconds: Long = 120
    ): Result {
        val command = listOf("bash", scriptPath) + args
        return exec(command, workingDir, timeoutSeconds)
    }

    data class Result(val exitCode: Int, val output: String) {
        val success get() = exitCode == 0
    }
}
