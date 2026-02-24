package com.citigroup.copilotchat.tools

import kotlinx.serialization.json.JsonObject

/**
 * A cohesive group of built-in tools that share a common domain.
 * Each group provides its own schemas and executor functions.
 */
interface ToolGroup {
    val schemas: List<String>
    val executors: Map<String, (JsonObject, String) -> String>
}
