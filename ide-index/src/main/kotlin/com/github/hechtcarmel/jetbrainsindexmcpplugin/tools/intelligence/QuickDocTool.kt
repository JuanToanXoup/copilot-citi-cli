package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.QuickDocResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNamedElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class QuickDocTool : AbstractMcpTool() {

    override val name = ToolNames.QUICK_DOC

    override val description = """
        Get rendered documentation (JavaDoc, KDoc, docstrings, etc.) for a symbol at a position. Works across all languages supported by the IDE.

        Use this when you need to read the documentation for a class, method, field, or any other symbol without navigating to its source.

        Returns: symbol name, rendered documentation text, and containing class (if applicable).

        Parameters: file + line + column (required).

        Example: {"file": "src/main/java/com/example/MyClass.java", "line": 15, "column": 10}
    """.trimIndent()

    override val inputSchema: JsonObject = buildJsonObject {
        put(SchemaConstants.TYPE, SchemaConstants.TYPE_OBJECT)
        putJsonObject(SchemaConstants.PROPERTIES) {
            putJsonObject(ParamNames.PROJECT_PATH) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Absolute path to project root. Only needed when multiple projects are open in IDE.")
            }
            putJsonObject(ParamNames.FILE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                put(SchemaConstants.DESCRIPTION, "Path to file relative to project root (e.g., 'src/main/java/com/example/MyClass.java'). REQUIRED.")
            }
            putJsonObject(ParamNames.LINE) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "1-based line number where the symbol is located. REQUIRED.")
            }
            putJsonObject(ParamNames.COLUMN) {
                put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                put(SchemaConstants.DESCRIPTION, "1-based column number within the line. REQUIRED.")
            }
        }
        putJsonArray(SchemaConstants.REQUIRED) {
            add(JsonPrimitive(ParamNames.FILE))
            add(JsonPrimitive(ParamNames.LINE))
            add(JsonPrimitive(ParamNames.COLUMN))
        }
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments[ParamNames.FILE]?.jsonPrimitive?.content
            ?: return createErrorResult(ErrorMessages.missingRequiredParam(ParamNames.FILE))
        val line = arguments[ParamNames.LINE]?.jsonPrimitive?.int
            ?: return createErrorResult(ErrorMessages.missingRequiredParam(ParamNames.LINE))
        val column = arguments[ParamNames.COLUMN]?.jsonPrimitive?.int
            ?: return createErrorResult(ErrorMessages.missingRequiredParam(ParamNames.COLUMN))

        requireSmartMode(project)

        return suspendingReadAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@suspendingReadAction createErrorResult(ErrorMessages.noElementAtPosition(file, line, column))

            val targetElement = PsiUtils.resolveTargetElement(element)
                ?: return@suspendingReadAction createErrorResult(ErrorMessages.SYMBOL_NOT_RESOLVED)

            // Use DocumentationProvider API for language-agnostic documentation
            val docProviders = DocumentationProvider.EP_NAME.extensionList
            var documentation: String? = null

            for (provider in docProviders) {
                try {
                    val doc = provider.generateDoc(targetElement, element)
                    if (!doc.isNullOrBlank()) {
                        documentation = doc
                        break
                    }
                } catch (_: Exception) {
                    // Provider may not support this element type
                }
            }

            // Strip HTML tags to get readable text
            val readableDoc = documentation?.let { stripHtml(it) }
                ?: return@suspendingReadAction createErrorResult("No documentation found for symbol at $file:$line:$column")

            val symbolName = if (targetElement is PsiNamedElement) {
                targetElement.name ?: "unknown"
            } else {
                targetElement.text.take(50)
            }

            val containingClass = PsiUtils.getContainingClass(targetElement)?.name

            createJsonResult(QuickDocResult(
                symbolName = symbolName,
                documentation = readableDoc,
                containingClass = containingClass
            ))
        }
    }

    private fun stripHtml(html: String): String {
        return html
            // Replace common block elements with newlines
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?div>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<li>", RegexOption.IGNORE_CASE), "\n- ")
            .replace(Regex("</?[hH][1-6]>"), "\n")
            // Preserve code blocks
            .replace(Regex("<code>", RegexOption.IGNORE_CASE), "`")
            .replace(Regex("</code>", RegexOption.IGNORE_CASE), "`")
            .replace(Regex("<pre>", RegexOption.IGNORE_CASE), "\n```\n")
            .replace(Regex("</pre>", RegexOption.IGNORE_CASE), "\n```\n")
            // Strip remaining tags
            .replace(Regex("<[^>]+>"), "")
            // Decode common HTML entities
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&nbsp;", " ")
            .replace("&#39;", "'")
            // Clean up whitespace
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
