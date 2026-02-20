package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindSymbolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolMatch
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tool for searching code symbols across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class FindSymbolTool : AbstractMcpTool() {

    companion object {
        private const val DEFAULT_LIMIT = 25
        private const val MAX_LIMIT = 100
    }

    override val name = ToolNames.FIND_SYMBOL

    override val description = """
        Search for symbols (classes, methods, fields, functions) by name across the codebase. Use when you know a symbol name but not its location. Faster and more accurate than grep for code navigation.

        Use ide_find_class instead if you only need classes (faster). Use ide_search_text for raw text matching that is not symbol-aware. Use ide_find_references if you already have the symbol's position and want to find all usages.

        Languages: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust.

        Matching: substring ("Service" → "UserService") and camelCase ("USvc" → "UserService").

        Returns: matching symbols with qualified names, file paths, line numbers, and kind.

        Parameters: query (required), includeLibraries (optional, default: true), limit (optional, default: 25, max: 100).

        Example: {"query": "UserService"} or {"query": "find_user", "includeLibraries": false}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put(SchemaConstants.TYPE, SchemaConstants.TYPE_OBJECT)
        putJsonObject(SchemaConstants.PROPERTIES) {
            putJsonObject(ParamNames.PROJECT_PATH) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_PROJECT_PATH)
            }
            putJsonObject(ParamNames.QUERY) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Search pattern. Supports substring and camelCase matching.")
            }
            putJsonObject(ParamNames.INCLUDE_LIBRARIES) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_BOOLEAN)
                put(SchemaConstants.DESCRIPTION, "Include symbols from library dependencies. Default: true.")
            }
            putJsonObject(ParamNames.LIMIT) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "Maximum results to return. Default: 25, Max: 100.")
            }
        }
        putJsonArray(SchemaConstants.REQUIRED) {
            add(JsonPrimitive(ParamNames.QUERY))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val query = arguments[ParamNames.QUERY]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: ${ParamNames.QUERY}")
        val includeLibraries = arguments[ParamNames.INCLUDE_LIBRARIES]?.jsonPrimitive?.boolean ?: true
        val limit = (arguments[ParamNames.LIMIT]?.jsonPrimitive?.int ?: DEFAULT_LIMIT)
            .coerceIn(1, MAX_LIMIT)

        if (query.isBlank()) {
            return createErrorResult("Query cannot be empty")
        }

        requireSmartMode(project)

        return suspendingReadAction {
            // Aggregate results from ALL available language handlers
            val handlers = LanguageHandlerRegistry.getAllSymbolSearchHandlers()
            if (handlers.isEmpty()) {
                return@suspendingReadAction createErrorResult(
                    "No symbol search handlers available. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForSymbolSearch()}"
                )
            }

            val allMatches = mutableListOf<SymbolMatch>()

            for (handler in handlers) {
                val handlerResults = handler.searchSymbols(project, query, includeLibraries, limit)
                allMatches.addAll(handlerResults.map { symbolData ->
                    SymbolMatch(
                        name = symbolData.name,
                        qualifiedName = symbolData.qualifiedName,
                        kind = symbolData.kind,
                        file = symbolData.file,
                        line = symbolData.line,
                        containerName = symbolData.containerName,
                        language = symbolData.language
                    )
                })
            }

            val sortedMatches = allMatches
                .distinctBy { "${it.file}:${it.line}:${it.name}" }
                .take(limit)

            createJsonResult(FindSymbolResult(
                symbols = sortedMatches,
                totalCount = sortedMatches.size,
                query = query
            ))
        }
    }

}
