package com.citigroup.copilotchat.tools.psi

import kotlinx.serialization.json.*

// ── JSON argument helpers (used by all tool files) ──

internal fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
internal fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

// ── Tool Registry ──

object PsiTools {
    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(PsiTools::class.java)

    val allTools: List<PsiToolBase> by lazy {
        try {
            LanguageHandlerRegistry.ensureInitialized()
            listOf(
                // Navigation
                FindUsagesTool(), FindDefinitionTool(), SearchTextTool(),
                FindClassTool(), FindFileTool(), FindSymbolTool(),
                TypeHierarchyTool(), CallHierarchyTool(), FindImplementationsTool(),
                FindSuperMethodsTool(), FileStructureTool(),
                // Intelligence
                GetDiagnosticsTool(), QuickDocTool(), TypeInfoTool(),
                ParameterInfoTool(), StructuralSearchTool(),
                // Refactoring
                RenameSymbolTool(), SafeDeleteTool(),
                // Project
                GetIndexStatusTool(),
            )
        } catch (e: Throwable) {
            log.warn("Failed to initialize PSI tools (Java plugin may not be available)", e)
            emptyList()
        }
    }

    val toolNames: Set<String> by lazy { allTools.map { it.name }.toSet() }
    val schemas: List<String> by lazy { allTools.mapNotNull { buildSchemaJson(it) } }

    /** Action name (without ide_ prefix) → tool name mapping. */
    val actionToToolName: Map<String, String> by lazy {
        allTools.associate { it.name.removePrefix("ide_") to it.name }
    }

    /**
     * Single compound schema that bundles all PSI tools into one "ide" tool.
     * The model picks the action (e.g., "find_usages") and passes params.
     */
    val compoundSchema: String? by lazy {
        try {
            buildCompoundSchema()
        } catch (e: Throwable) {
            log.warn("Failed to build compound PSI schema", e)
            null
        }
    }

    fun findTool(name: String): PsiToolBase? = allTools.find { it.name == name }

    /** Find a tool by action name (without ide_ prefix). */
    fun findToolByAction(action: String): PsiToolBase? =
        actionToToolName[action]?.let { findTool(it) }

    /**
     * Build a compound schema that only includes tools passing the filter.
     * Used to exclude disabled tools from registration.
     */
    fun buildFilteredCompoundSchema(isEnabled: (String) -> Boolean): String? {
        val enabledTools = allTools.filter { isEnabled(it.name) }
        if (enabledTools.isEmpty()) return null
        return try {
            buildCompoundSchemaFrom(enabledTools)
        } catch (e: Throwable) {
            log.warn("Failed to build filtered compound PSI schema", e)
            null
        }
    }

    private fun buildSchemaJson(tool: PsiToolBase): String? = try {
        buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("inputSchema", Json.parseToJsonElement(tool.inputSchema))
        }.toString()
    } catch (e: Exception) {
        log.warn("Failed to build schema for PSI tool: ${tool.name}", e)
        null
    }

    private fun buildCompoundSchema(): String? = buildCompoundSchemaFrom(allTools)

    private fun buildCompoundSchemaFrom(tools: List<PsiToolBase>): String? {
        if (tools.isEmpty()) return null

        val actionNames = mutableListOf<String>()
        val actionDocs = StringBuilder()
        val allProperties = mutableMapOf<String, JsonElement>()

        for (tool in tools) {
            val action = tool.name.removePrefix("ide_")
            actionNames.add(action)

            // Build doc line: "- action: description. Params: {p1, p2}"
            val inputSchema = try { Json.parseToJsonElement(tool.inputSchema).jsonObject } catch (_: Exception) { null }
            val paramNames = inputSchema?.get("properties")?.jsonObject?.keys ?: emptySet()
            val paramsHint = if (paramNames.isNotEmpty()) " Params: {${paramNames.joinToString(", ")}}" else ""
            actionDocs.appendLine("- $action: ${tool.description}$paramsHint")

            // Merge properties into flat set
            if (inputSchema != null) {
                val props = inputSchema["properties"]?.jsonObject
                if (props != null) {
                    for ((propName, propValue) in props) {
                        if (propName !in allProperties) {
                            allProperties[propName] = propValue
                        }
                    }
                }
            }
        }

        val compoundInputSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "The IDE action to perform")
                    putJsonArray("enum") { actionNames.forEach { add(it) } }
                }
                for ((propName, propSchema) in allProperties) {
                    put(propName, propSchema)
                }
            }
            putJsonArray("required") { add("action") }
        }

        val desc = buildString {
            append("IDE code intelligence tools powered by IntelliJ PSI. Use 'action' to pick the operation.\n\nActions:\n")
            append(actionDocs.toString().trimEnd())
        }

        return buildJsonObject {
            put("name", "ide")
            put("description", desc)
            put("inputSchema", compoundInputSchema)
        }.toString()
    }
}
