package com.speckit.plugin.service

import com.intellij.openapi.application.ApplicationManager
import java.io.File
import java.util.concurrent.TimeUnit

object GitHelper {

    fun currentBranch(basePath: String, fallback: String = "main"): String {
        return try {
            val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0) output else fallback
        } catch (_: Exception) { fallback }
    }

    fun gitAdd(basePath: String, relativePath: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ProcessBuilder("git", "add", relativePath)
                    .directory(File(basePath))
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS)
            } catch (_: Exception) {
                // Best-effort — don't block the UI if git isn't available
            }
        }
    }
}
