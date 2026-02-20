package com.citigroup.copilotchat.config

import com.intellij.openapi.diagnostic.Logger
import org.tomlj.Toml
import java.io.File

/**
 * Agent configuration loaded from TOML files.
 * Supports the same format as the CLI's config.toml.
 */
data class AgentConfig(
    val name: String = "",
    val model: String = "",
    val systemPrompt: String = "",
    val tools: List<String> = emptyList(),
    val copilotBinary: String = "",
    val appsJson: String = "",
) {
    companion object {
        private val log = Logger.getInstance(AgentConfig::class.java)

        fun load(path: String): AgentConfig {
            val file = File(path)
            if (!file.exists()) {
                log.info("Config file not found: $path")
                return AgentConfig()
            }

            return try {
                val result = Toml.parse(file.toPath())
                AgentConfig(
                    name = result.getString("name") ?: "",
                    model = result.getString("model") ?: "",
                    systemPrompt = result.getString("system_prompt") ?: "",
                    tools = result.getArray("tools")?.toList()?.map { it.toString() } ?: emptyList(),
                    copilotBinary = result.getString("copilot_binary") ?: "",
                    appsJson = result.getString("apps_json") ?: "",
                )
            } catch (e: Exception) {
                log.warn("Failed to parse config: $path", e)
                AgentConfig()
            }
        }
    }
}
