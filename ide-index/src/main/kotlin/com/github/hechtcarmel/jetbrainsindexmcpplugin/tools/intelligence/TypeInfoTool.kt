package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeInfoResult
import com.intellij.lang.ExpressionTypeProvider
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class TypeInfoTool : AbstractMcpTool() {

    override val name = ToolNames.TYPE_INFO

    override val description = """
        Get the type of an expression, variable, parameter, or field at a position. Works across all languages supported by the IDE.

        Use this when you need to know what type a variable holds, what an expression evaluates to, or what type a parameter expects.

        Returns: symbol name, presentable type, canonical type (if available), and kind (variable/expression/parameter/field).

        Parameters: file + line + column (required).

        Example: {"file": "src/main/java/com/example/MyClass.java", "line": 20, "column": 15}
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
                put(SchemaConstants.DESCRIPTION, "1-based line number where the expression/variable is located. REQUIRED.")
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

            // Try language-agnostic ExpressionTypeProvider first
            val typeProviderResult = tryExpressionTypeProviders(element)
            if (typeProviderResult != null) {
                return@suspendingReadAction createJsonResult(typeProviderResult)
            }

            // Fallback: try Java/Kotlin PSI-specific type extraction
            val psiResult = tryPsiTypeExtraction(element)
            if (psiResult != null) {
                return@suspendingReadAction createJsonResult(psiResult)
            }

            createErrorResult("No type information found for element at $file:$line:$column")
        }
    }

    private fun tryExpressionTypeProviders(element: PsiElement): TypeInfoResult? {
        val epName = ExtensionPointName.create<ExpressionTypeProvider<PsiElement>>("com.intellij.expressionTypeProvider")
        val providers: List<ExpressionTypeProvider<PsiElement>> = epName.extensionList

        // Walk up from the leaf element to find one that providers can handle
        var current: PsiElement? = element
        repeat(5) {
            if (current == null) return null
            for (provider in providers) {
                try {
                    val typeStr = provider.getInformationHint(current!!)
                    if (typeStr.isNotBlank()) {
                        val symbolName = if (current is PsiNamedElement) {
                            (current as PsiNamedElement).name ?: current!!.text.take(50)
                        } else {
                            current!!.text.take(50)
                        }
                        val kind = classifyElement(current!!)
                        // Strip HTML from type hint
                        val cleanType = typeStr.replace(Regex("<[^>]+>"), "").trim()
                        return TypeInfoResult(
                            symbolName = symbolName,
                            type = cleanType,
                            canonicalType = null,
                            kind = kind
                        )
                    }
                } catch (_: Exception) {
                    // Provider doesn't support this element
                }
            }
            current = current?.parent
        }
        return null
    }

    private fun tryPsiTypeExtraction(element: PsiElement): TypeInfoResult? {
        // Walk up from leaf to find typed element
        var current: PsiElement? = element
        repeat(5) {
            if (current == null) return null
            val result = extractTypeViaPsi(current!!)
            if (result != null) return result
            current = current?.parent
        }
        return null
    }

    private fun extractTypeViaPsi(element: PsiElement): TypeInfoResult? {
        val elementClass = element.javaClass.name

        // Java PsiVariable (covers local vars, parameters, fields)
        if (isInstanceOf(element, "com.intellij.psi.PsiVariable")) {
            return extractFromPsiVariable(element)
        }

        // Java PsiExpression
        if (isInstanceOf(element, "com.intellij.psi.PsiExpression")) {
            return extractFromPsiExpression(element)
        }

        return null
    }

    private fun extractFromPsiVariable(element: PsiElement): TypeInfoResult? {
        return try {
            val getTypeMethod = element.javaClass.getMethod("getType")
            val type = getTypeMethod.invoke(element) ?: return null

            val presentableText = type.javaClass.getMethod("getPresentableText").invoke(type) as? String ?: return null
            val canonicalText = try {
                type.javaClass.getMethod("getCanonicalText").invoke(type) as? String
            } catch (_: Exception) { null }

            val name = try {
                (element as? PsiNamedElement)?.name ?: element.javaClass.getMethod("getName").invoke(element) as? String
            } catch (_: Exception) { null } ?: element.text.take(50)

            val kind = classifyElement(element)

            TypeInfoResult(
                symbolName = name,
                type = presentableText,
                canonicalType = canonicalText?.takeIf { it != presentableText },
                kind = kind
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractFromPsiExpression(element: PsiElement): TypeInfoResult? {
        return try {
            val getTypeMethod = element.javaClass.getMethod("getType")
            val type = getTypeMethod.invoke(element) ?: return null

            val presentableText = type.javaClass.getMethod("getPresentableText").invoke(type) as? String ?: return null
            val canonicalText = try {
                type.javaClass.getMethod("getCanonicalText").invoke(type) as? String
            } catch (_: Exception) { null }

            TypeInfoResult(
                symbolName = element.text.take(50),
                type = presentableText,
                canonicalType = canonicalText?.takeIf { it != presentableText },
                kind = "expression"
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun classifyElement(element: PsiElement): String {
        val className = element.javaClass.name
        return when {
            isInstanceOf(element, "com.intellij.psi.PsiParameter") -> "parameter"
            isInstanceOf(element, "com.intellij.psi.PsiField") -> "field"
            isInstanceOf(element, "com.intellij.psi.PsiLocalVariable") -> "variable"
            isInstanceOf(element, "com.intellij.psi.PsiVariable") -> "variable"
            isInstanceOf(element, "com.intellij.psi.PsiExpression") -> "expression"
            else -> "expression"
        }
    }

    private fun isInstanceOf(element: PsiElement, className: String): Boolean {
        return try {
            val clazz = Class.forName(className)
            clazz.isInstance(element)
        } catch (_: ClassNotFoundException) {
            false
        }
    }
}
