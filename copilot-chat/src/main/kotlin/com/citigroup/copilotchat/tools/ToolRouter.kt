package com.citigroup.copilotchat.tools

import com.citigroup.copilotchat.config.CopilotChatSettings
import com.citigroup.copilotchat.tools.psi.PsiToolBase
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
            "list_code_usages",         // → ide_find_usages
            "search_workspace_symbols", // → ide_find_symbol + ide_find_class
            "file_search",              // → ide_find_file
            "get_errors",               // → ide_diagnostics
            "get_doc_info",             // → ide_quick_doc
            "find_test_files",          // → ide_find_file (better fuzzy match)
        )

        /** Redirect map: if model requests a superseded name, redirect to PSI equivalent. */
        val TOOL_REDIRECTS = mapOf(
            "grep_search" to "ide_search_text",
            "list_code_usages" to "ide_find_usages",
            "search_workspace_symbols" to "ide_find_symbol",
            "file_search" to "ide_find_file",
            "get_errors" to "ide_diagnostics",
            "get_doc_info" to "ide_quick_doc",
        )
    }

    /**
     * Get all tool schemas to register with the server.
     * PSI tools are registered as a single compound "ide" tool.
     * Then ide-index tools (if any), then built-in tools.
     * Respects disabled tools from settings.
     */
    fun getToolSchemas(): List<String> {
        val settings = CopilotChatSettings.getInstance()
        val schemas = mutableListOf<String>()
        val registeredNames = mutableSetOf<String>()

        // PSI tools as a single compound "ide" tool (filter disabled actions)
        try {
            val compoundSchema = PsiTools.buildFilteredCompoundSchema { name ->
                settings.isToolEnabled(name)
            }
            if (compoundSchema != null) {
                schemas.add(compoundSchema)
                registeredNames.add("ide")
                registeredNames.addAll(psiToolNames)
            }
        } catch (e: Throwable) {
            log.warn("Failed to load PSI compound schema, continuing with built-in tools", e)
        }

        // ide-index tools (only add ones not already provided by PSI tools and not disabled)
        val ideSchemas = IdeIndexBridge.getToolSchemas()
        for (schema in ideSchemas) {
            val schemaObj = json.parseToJsonElement(schema).jsonObject
            val name = schemaObj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            if (name !in registeredNames && settings.isToolEnabled(name)) {
                schemas.add(schema)
                registeredNames.add(name)
            }
        }

        // Determine which BuiltInTools to suppress
        val suppressedNames = if (psiToolNames.isNotEmpty()) PSI_SUPERSEDES else emptySet()

        // Built-in tools (only add ones not already registered, not superseded, not disabled)
        for (schema in BuiltInTools.schemas) {
            val schemaObj = json.parseToJsonElement(schema).jsonObject
            val name = schemaObj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            if (name !in registeredNames && name !in suppressedNames && settings.isToolEnabled(name)) {
                schemas.add(schema)
                registeredNames.add(name)
            }
        }

        val psiCount = if (PsiTools.compoundSchema != null) PsiTools.allTools.size else 0
        log.info("Registered ${schemas.size} tools ($psiCount PSI in compound 'ide', ${ideSchemas.size} ide-index, rest built-in)")
        return schemas
    }

    /**
     * Execute a tool call and return the result in copilot format:
     * [{"content": [{"value": "..."}], "status": "success"}, null]
     */
    suspend fun executeTool(name: String, input: JsonObject): JsonElement {
        log.info("Tool call: $name")

        // Check if tool is disabled
        val settings = CopilotChatSettings.getInstance()
        if (!settings.isToolEnabled(name)) {
            log.info("Tool '$name' is disabled, rejecting call")
            return wrapResult("Error: Tool '$name' is disabled", isError = true)
        }

        // Handle compound "ide" tool — extract action and route to PSI sub-tool
        if (name == "ide") {
            return executeIdeTool(input)
        }

        // Check for redirect (e.g., grep_search → ide_search_text)
        val effectiveName = if (name in TOOL_REDIRECTS && TOOL_REDIRECTS[name] in psiToolNames) {
            val redirected = TOOL_REDIRECTS[name]!!
            log.info("Redirecting $name → $redirected")
            redirected
        } else name

        // Try PSI tools by full name (fallback if model uses individual names)
        val psiTool = PsiTools.findTool(effectiveName)
        if (psiTool != null) {
            return executePsiTool(psiTool, input, name)
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

    private suspend fun executeIdeTool(input: JsonObject): JsonElement {
        val action = input["action"]?.jsonPrimitive?.contentOrNull
            ?: return wrapResult("Error: 'action' parameter is required for the 'ide' tool", isError = true)

        val psiTool = PsiTools.findToolByAction(action)
            ?: return wrapResult(
                "Error: Unknown IDE action '$action'. Available: ${PsiTools.actionToToolName.keys.joinToString(", ")}",
                isError = true
            )

        // Check if this specific PSI tool is disabled
        val settings = CopilotChatSettings.getInstance()
        if (!settings.isToolEnabled(psiTool.name)) {
            log.info("IDE action '$action' (${psiTool.name}) is disabled, rejecting call")
            return wrapResult("Error: IDE action '$action' is disabled", isError = true)
        }

        // Strip "action" from input, pass rest to the tool
        val toolInput = JsonObject(input.filterKeys { it != "action" })
        return executePsiTool(psiTool, toolInput, "ide/$action")
    }

    private suspend fun executePsiTool(tool: PsiToolBase, input: JsonObject, displayName: String): JsonElement {
        return try {
            val result = tool.execute(project, input)
            val text = result.content.joinToString("\n") { block ->
                when (block) {
                    is com.citigroup.copilotchat.tools.psi.ContentBlock.Text -> block.text
                }
            }
            wrapResult(text, isError = result.isError)
        } catch (e: Exception) {
            log.warn("PSI tool $displayName failed, falling back", e)
            tryFallback(displayName, input)
        }
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
