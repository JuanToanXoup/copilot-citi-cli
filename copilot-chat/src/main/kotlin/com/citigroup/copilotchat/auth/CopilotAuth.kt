package com.citigroup.copilotchat.auth

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*
import java.io.File

/**
 * Reads the GitHub Copilot OAuth token from ~/.config/github-copilot/apps.json.
 * Port of client.py _read_auth().
 */
object CopilotAuth {

    private val log = Logger.getInstance(CopilotAuth::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    data class AuthInfo(
        val token: String,
        val user: String,
        val appId: String,
    )

    /**
     * Read OAuth token from apps.json.
     * Prefers ghu_ tokens (GitHub user/Copilot tokens) over gho_ (OAuth app tokens).
     */
    fun readAuth(appsJsonPath: String? = null): AuthInfo {
        val path = appsJsonPath ?: defaultAppsJsonPath()
        val file = File(path)
        if (!file.exists()) {
            throw RuntimeException("apps.json not found at $path. Please sign in to GitHub Copilot first.")
        }

        val root = json.parseToJsonElement(file.readText()).jsonObject

        // Prefer ghu_ tokens
        for ((key, value) in root) {
            val obj = value.jsonObject
            val token = obj["oauth_token"]?.jsonPrimitive?.contentOrNull ?: continue
            if (token.startsWith("ghu_")) {
                val appId = if (":" in key) key.substringAfter(":") else ""
                val user = obj["user"]?.jsonPrimitive?.contentOrNull ?: ""
                return AuthInfo(token = token, user = user, appId = appId)
            }
        }

        // Fallback to any token
        for ((key, value) in root) {
            val obj = value.jsonObject
            val token = obj["oauth_token"]?.jsonPrimitive?.contentOrNull ?: continue
            val appId = if (":" in key) key.substringAfter(":") else ""
            val user = obj["user"]?.jsonPrimitive?.contentOrNull ?: ""
            return AuthInfo(token = token, user = user, appId = appId)
        }

        throw RuntimeException("No OAuth token found in apps.json")
    }

    fun defaultAppsJsonPath(): String {
        val os = System.getProperty("os.name").lowercase()
        return if (os.contains("win")) {
            val appdata = System.getenv("APPDATA")
                ?: (System.getProperty("user.home") + "/AppData/Roaming")
            "$appdata/github-copilot/apps.json"
        } else {
            System.getProperty("user.home") + "/.config/github-copilot/apps.json"
        }
    }
}
