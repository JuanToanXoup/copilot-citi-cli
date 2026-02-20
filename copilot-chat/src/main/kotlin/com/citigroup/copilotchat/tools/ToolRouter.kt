package com.citigroup.copilotchat.tools

import com.citigroup.copilotchat.tools.psi.PsiTools
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*

/**
 * Routes tool calls to PSI tools, ide-index (via reflection), or built-in tools.
 * Priority: PSI tools > ide-index > built-in tools.
 */
class ToolRouter(private val project: Project) {

    private val log = Logger.getInstance(ToolRouter::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val ideIndexToolNames: Set<String> by lazy { IdeIndexBridge.getToolNames() }
    private val psiToolNames: Set<String> by lazy { PsiTools.toolNames }
    private val workspaceRoot: String = project.basePath ?: "/tmp"

    companion object {
        /** BuiltInTools superseded when PSI tools are available. */
        val PSI_SUPERSEDES = setOf(
            "grep_search",              // → ide_search_text
            "list_code_usages",         // → ide_find_references
            "search_workspace_symbols", // → ide_find_symbol + ide_find_class
            "file_search",              // → ide_find_file
            "get_errors",               // → ide_diagnostics
            "get_doc_info",             // → ide_quick_doc
            "find_test_files",          // → ide_find_file (better fuzzy match)
        )

        /** Redirect map: if model requests a superseded name, redirect to PSI equivalent. */
        val TOOL_REDIRECTS = mapOf(
            "grep_search" to "ide_search_text",
            "list_code_usages" to "ide_find_references",
            "search_workspace_symbols" to "ide_find_symbol",
            "file_search" to "ide_find_file",
            "get_errors" to "ide_diagnostics",
            "get_doc_info" to "ide_quick_doc",
        )
    }

    /**
     * Get all tool schemas to register with the server.
     * Combines PSI tools, ide-index tools (if available), and built-in tools.
     */
    fun getToolSchemas(): List<String> {
        val schemas = mutableListOf<String>()
        val registeredNames = mutableSetOf<String>()

        // PSI tools first (direct IDE integration, no reflection needed)
        try {
            for (schema in PsiTools.schemas) {
                val schemaObj = json.parseToJsonElement(schema).jsonObject
                val name = schemaObj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                schemas.add(schema)
                registeredNames.add(name)
            }
        } catch (e: Throwable) {
            log.warn("Failed to load PSI tool schemas, continuing with built-in tools", e)
        }

        // ide-index tools (only add ones not already provided by PSI tools)
        val ideSchemas = IdeIndexBridge.getToolSchemas()
        for (schema in ideSchemas) {
            val schemaObj = json.parseToJsonElement(schema).jsonObject
            val name = schemaObj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            if (name !in registeredNames) {
                schemas.add(schema)
                registeredNames.add(name)
            }
        }

        // Determine which BuiltInTools to suppress
        val suppressedNames = if (psiToolNames.isNotEmpty()) PSI_SUPERSEDES else emptySet()

        // Built-in tools (only add ones not already registered and not superseded)
        for (schema in BuiltInTools.schemas) {
            val schemaObj = json.parseToJsonElement(schema).jsonObject
            val name = schemaObj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            if (name !in registeredNames && name !in suppressedNames) {
                schemas.add(schema)
                registeredNames.add(name)
            }
        }

        log.info("Registered ${schemas.size} tools (${psiToolNames.size} PSI, ${ideSchemas.size} ide-index, rest built-in)")
        return schemas
    }

    /**
     * Execute a tool call and return the result in copilot format:
     * [{"content": [{"value": "..."}], "status": "success"}, null]
     */
    suspend fun executeTool(name: String, input: JsonObject): JsonElement {
        log.info("Tool call: $name")

        // Check for redirect (e.g., grep_search → ide_search_text)
        val effectiveName = if (name in TOOL_REDIRECTS && TOOL_REDIRECTS[name] in psiToolNames) {
            val redirected = TOOL_REDIRECTS[name]!!
            log.info("Redirecting $name → $redirected")
            redirected
        } else name

        // Try PSI tools first
        val psiTool = PsiTools.findTool(effectiveName)
        if (psiTool != null) {
            return try {
                val result = psiTool.execute(project, input)
                val text = result.content.joinToString("\n") { block ->
                    when (block) {
                        is com.citigroup.copilotchat.tools.psi.ContentBlock.Text -> block.text
                    }
                }
                wrapResult(text, isError = result.isError)
            } catch (e: Exception) {
                log.warn("PSI tool $effectiveName failed, falling back", e)
                // Fall through to other providers
                tryFallback(name, input)
            }
        }

        // Try ide-index
        if (effectiveName in ideIndexToolNames) {
            val result = IdeIndexBridge.callTool(effectiveName, input, project.basePath)
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

    private fun tryFallback(name: String, input: JsonObject): JsonElement {
        // If the original name (before redirect) is a built-in tool, use it
        if (name in BuiltInTools.toolNames) {
            val result = BuiltInTools.execute(name, input, workspaceRoot)
            return wrapResult(result)
        }
        return wrapResult("Error: Tool execution failed: $name", isError = true)
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
