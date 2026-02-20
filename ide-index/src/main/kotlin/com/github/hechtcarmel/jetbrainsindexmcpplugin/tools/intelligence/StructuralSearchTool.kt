package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructuralMatch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructuralSearchResult
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.structuralsearch.MatchOptions
import com.intellij.structuralsearch.MatchResult
import com.intellij.structuralsearch.Matcher
import com.intellij.structuralsearch.MatchResultSink
import com.intellij.structuralsearch.MatchingProcess
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.psi.PsiFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class StructuralSearchTool : AbstractMcpTool() {

    companion object {
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 200
        private const val DEFAULT_FILE_TYPE = "Java"
    }

    override val name = ToolNames.STRUCTURAL_SEARCH

    override val description = """
        Search for code patterns using IntelliJ's structural search engine. Finds code that matches a structural pattern, not just text.

        Use this for pattern-based code searches like "find all if-statements that throw exceptions", "find all method calls with 3 arguments", or "find all singleton patterns". Much more powerful than text search for code patterns.

        Pattern syntax uses ${'$'}var${'$'} for capture variables: e.g., "if (${'$'}cond${'$'}) { throw ${'$'}expr${'$'}; }" matches any if-throw pattern.

        Returns: list of matches with file, line, and matched text.

        Parameters: pattern (required), fileType (optional, default: "Java"), scope (optional: "project"/"file"), file (optional, for file scope), limit (optional, default: 50).

        Example: {"pattern": "${'$'}Instance${'$'}.${'$'}MethodCall${'$'}(${'$'}Parameter${'$'})", "fileType": "Java"}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put(SchemaConstants.TYPE, SchemaConstants.TYPE_OBJECT)
        putJsonObject(SchemaConstants.PROPERTIES) {
            putJsonObject(ParamNames.PROJECT_PATH) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Absolute path to project root. Only needed when multiple projects are open in IDE.")
            }
            putJsonObject("pattern") {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Structural search pattern. Use \$var\$ syntax for capture variables. REQUIRED.")
            }
            putJsonObject("fileType") {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "File type to search in: 'Java', 'Kotlin', 'Python', 'JavaScript', 'XML', etc. Optional, defaults to 'Java'.")
            }
            putJsonObject("scope") {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Search scope: 'project' (default) or 'file' (requires file parameter).")
            }
            putJsonObject(ParamNames.FILE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Path to file relative to project root. Only used when scope is 'file'.")
            }
            putJsonObject(ParamNames.LIMIT) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "Maximum number of results to return. Optional, defaults to 50, max 200.")
            }
        }
        putJsonArray(SchemaConstants.REQUIRED) {
            add(JsonPrimitive("pattern"))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val pattern = arguments["pattern"]?.jsonPrimitive?.content
            ?: return createErrorResult(ErrorMessages.missingRequiredParam("pattern"))
        val fileType = arguments["fileType"]?.jsonPrimitive?.content ?: DEFAULT_FILE_TYPE
        val scope = arguments["scope"]?.jsonPrimitive?.content ?: "project"
        val file = arguments[ParamNames.FILE]?.jsonPrimitive?.content
        val limit = (arguments[ParamNames.LIMIT]?.jsonPrimitive?.int ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        if (scope == "file" && file == null) {
            return createErrorResult("When scope is 'file', the 'file' parameter is required.")
        }

        requireSmartMode(project)

        return suspendingReadAction {
            try {
                val languageFileType = resolveLanguageFileType(fileType)
                    ?: return@suspendingReadAction createErrorResult("Unsupported file type: $fileType. Only language-based file types (Java, Kotlin, Python, etc.) are supported.")

                val matchOptions = MatchOptions()
                matchOptions.searchPattern = pattern
                matchOptions.setFileType(languageFileType)
                matchOptions.isRecursiveSearch = true

                // Set search scope
                val searchScope = when (scope) {
                    "file" -> {
                        val virtualFile = resolveFile(project, file!!)
                            ?: return@suspendingReadAction createErrorResult(ErrorMessages.fileNotFound(file))
                        GlobalSearchScope.fileScope(project, virtualFile)
                    }
                    else -> GlobalSearchScope.projectScope(project)
                }
                matchOptions.scope = searchScope

                // Collect results using MatchResultSink
                val collectedResults = mutableListOf<MatchResult>()
                val matcher = Matcher(project, matchOptions)
                matcher.findMatches(object : MatchResultSink {
                    override fun newMatch(result: MatchResult) {
                        collectedResults.add(result)
                    }

                    override fun processFile(file: PsiFile) {
                        // No-op
                    }

                    override fun setMatchingProcess(matchingProcess: MatchingProcess) {
                        // No-op
                    }

                    override fun matchingFinished() {
                        // No-op
                    }

                    override fun getProgressIndicator(): ProgressIndicator = EmptyProgressIndicator()
                })

                val matches = collectedResults.take(limit).mapNotNull { matchResult ->
                    matchResultToStructuralMatch(project, matchResult)
                }

                createJsonResult(StructuralSearchResult(
                    matches = matches,
                    totalCount = collectedResults.size,
                    pattern = pattern
                ))
            } catch (e: Exception) {
                createErrorResult("Structural search failed: ${e.message}")
            }
        }
    }

    private fun matchResultToStructuralMatch(project: Project, matchResult: MatchResult): StructuralMatch? {
        val matchElement = matchResult.match ?: return null
        val containingFile = matchElement.containingFile ?: return null
        val virtualFile = containingFile.virtualFile ?: return null

        val document = PsiDocumentManager.getInstance(project).getDocument(containingFile) ?: return null
        val lineNumber = document.getLineNumber(matchElement.textOffset) + 1

        val matchedText = matchElement.text.take(200)

        return StructuralMatch(
            file = getRelativePath(project, virtualFile),
            line = lineNumber,
            matchedText = matchedText
        )
    }

    private fun resolveLanguageFileType(fileTypeName: String): LanguageFileType? {
        val registry = FileTypeManager.getInstance()

        // Try exact match by name among LanguageFileType instances
        val allTypes = registry.registeredFileTypes
        for (ft in allTypes) {
            if (ft is LanguageFileType && ft.name.equals(fileTypeName, ignoreCase = true)) {
                return ft
            }
        }

        // Fallback: try by extension
        val extensionMap = mapOf(
            "java" to "java",
            "kotlin" to "kt",
            "python" to "py",
            "javascript" to "js",
            "typescript" to "ts",
            "xml" to "xml",
            "html" to "html",
            "css" to "css",
            "json" to "json",
            "yaml" to "yaml",
            "go" to "go",
            "rust" to "rs",
            "php" to "php"
        )
        val extension = extensionMap[fileTypeName.lowercase()] ?: fileTypeName.lowercase()
        val ft = registry.getFileTypeByExtension(extension)
        return ft as? LanguageFileType
    }
}
