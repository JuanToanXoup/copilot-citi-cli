package com.citigroup.copilotchat.tools

import kotlinx.serialization.json.JsonObject

/**
 * A cohesive group of built-in tools that share a common domain.
 * Each group provides its own schemas and executor functions.
 *
 * Executor signature: (input, workspaceRoot) -> result
 *
 * Tools that need Project access should use [ToolInvocationContext.project()]
 * rather than receiving it as a parameter — matching the reference Copilot
 * plugin's service-lookup pattern (ToolInvocationManager.findProjectForInvocation).
 */
interface ToolGroup {
    val schemas: List<String>
    val executors: Map<String, (JsonObject, String) -> String>
}
