package com.citigroup.copilotchat.tools

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*

/**
 * Routes tool calls to either ide-index (via reflection) or built-in tools.
 * Handles conversation/invokeClientTool server requests.
 */
class ToolRouter(private val project: Project) {

    private val log = Logger.getInstance(ToolRouter::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val ideIndexToolNames: Set<String> by lazy { IdeIndexBridge.getToolNames() }
    private val workspaceRoot: String = project.basePath ?: "/tmp"

    /**
     * Get all tool schemas to register with the server.
     * Combines ide-index tools (if available) with built-in tools.
     */
    fun getToolSchemas(): List<String> {
        val schemas = mutableListOf<String>()

        // ide-index tools first (they provide richer IDE intelligence)
        val ideSchemas = IdeIndexBridge.getToolSchemas()
        schemas.addAll(ideSchemas)

        // Built-in tools (only add ones not already provided by ide-index)
        val ideNames = ideIndexToolNames
        for (schema in BuiltInTools.schemas) {
            val schemaObj = json.parseToJsonElement(schema).jsonObject
            val name = schemaObj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            if (name !in ideNames) {
                schemas.add(schema)
            }
        }

        return schemas
    }

    /**
     * Execute a tool call and return the result in copilot format:
     * [{"content": [{"value": "..."}], "status": "success"}, null]
     */
    suspend fun executeTool(name: String, input: JsonObject): JsonElement {
        log.info("Tool call: $name")

        // Try ide-index first
        if (name in ideIndexToolNames) {
            val result = IdeIndexBridge.callTool(name, input, project.basePath)
            if (result != null) {
                return wrapResult(result)
            }
        }

        // Fall back to built-in tools
        if (name in BuiltInTools.toolNames) {
            val result = BuiltInTools.execute(name, input, workspaceRoot)
            return wrapResult(result)
        }

        // Unknown tool
        log.warn("Unknown tool: $name")
        return wrapResult("Error: Unknown tool: $name", isError = true)
    }

    private fun wrapResult(text: String, isError: Boolean = false): JsonElement {
        return buildJsonArray {
            addJsonObject {
                putJsonArray("content") {
                    addJsonObject { put("value", text) }
                }
                put("status", if (isError) "error" else "success")
            }
            add(JsonNull)
        }
    }
}
