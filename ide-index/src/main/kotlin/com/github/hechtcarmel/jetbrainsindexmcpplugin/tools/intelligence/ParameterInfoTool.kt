package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ParameterDetail
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ParameterInfoResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
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

class ParameterInfoTool : AbstractMcpTool() {

    override val name = ToolNames.PARAMETER_INFO

    override val description = """
        Get parameter signatures for a method or function at a call site or declaration. Works across all languages supported by the IDE.

        Use this when you need to know what parameters a method expects, their types, and default values. Point at a method call or method name.

        Returns: method name, containing class, return type, and list of parameters with name, type, and default value.

        Parameters: file + line + column (required).

        Example: {"file": "src/main/java/com/example/MyClass.java", "line": 25, "column": 10}
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
                put(SchemaConstants.DESCRIPTION, "1-based line number where the method call or declaration is. REQUIRED.")
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

            // Resolve to target element (method declaration)
            val targetElement = PsiUtils.resolveTargetElement(element)
                ?: return@suspendingReadAction createErrorResult(ErrorMessages.SYMBOL_NOT_RESOLVED)

            // Try to extract parameter info from the resolved method
            val result = extractParameterInfo(targetElement)
                ?: return@suspendingReadAction createErrorResult("No parameter information found for element at $file:$line:$column. The element may not be a method or function.")

            createJsonResult(result)
        }
    }

    private fun extractParameterInfo(element: PsiElement): ParameterInfoResult? {
        // Try Java PsiMethod
        if (isInstanceOf(element, "com.intellij.psi.PsiMethod")) {
            return extractFromPsiMethod(element)
        }

        // Try Kotlin KtFunction via reflection
        if (isInstanceOf(element, "org.jetbrains.kotlin.psi.KtFunction")) {
            return extractFromKtFunction(element)
        }

        // Try Python PyFunction via reflection
        if (isInstanceOf(element, "com.jetbrains.python.psi.PyFunction")) {
            return extractFromPyFunction(element)
        }

        return null
    }

    private fun extractFromPsiMethod(element: PsiElement): ParameterInfoResult? {
        return try {
            val getName = element.javaClass.getMethod("getName")
            val methodName = getName.invoke(element) as? String ?: "unknown"

            // Get containing class
            val getContainingClass = element.javaClass.getMethod("getContainingClass")
            val containingClass = getContainingClass.invoke(element)
            val containingClassName = if (containingClass != null) {
                try {
                    containingClass.javaClass.getMethod("getQualifiedName").invoke(containingClass) as? String
                        ?: containingClass.javaClass.getMethod("getName").invoke(containingClass) as? String
                } catch (_: Exception) { null }
            } else null

            // Get return type
            val getReturnType = element.javaClass.getMethod("getReturnType")
            val returnType = getReturnType.invoke(element)
            val returnTypeStr = if (returnType != null) {
                try {
                    returnType.javaClass.getMethod("getPresentableText").invoke(returnType) as? String
                } catch (_: Exception) { null }
            } else null

            // Get parameters
            val getParameterList = element.javaClass.getMethod("getParameterList")
            val paramList = getParameterList.invoke(element)
            val getParameters = paramList.javaClass.getMethod("getParameters")
            val parameters = getParameters.invoke(paramList) as Array<*>

            val paramDetails = parameters.mapNotNull { param ->
                if (param == null) return@mapNotNull null
                try {
                    val paramName = param.javaClass.getMethod("getName").invoke(param) as? String ?: "unnamed"
                    val paramType = param.javaClass.getMethod("getType").invoke(param)
                    val paramTypeStr = paramType?.javaClass?.getMethod("getPresentableText")?.invoke(paramType) as? String ?: "unknown"
                    ParameterDetail(name = paramName, type = paramTypeStr, defaultValue = null)
                } catch (_: Exception) { null }
            }

            ParameterInfoResult(
                methodName = methodName,
                containingClass = containingClassName,
                returnType = returnTypeStr,
                parameters = paramDetails
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractFromKtFunction(element: PsiElement): ParameterInfoResult? {
        return try {
            val getName = element.javaClass.getMethod("getName")
            val methodName = getName.invoke(element) as? String ?: "unknown"

            // Get containing class name
            val containingClassName = PsiUtils.getContainingClass(element)?.name

            // Get return type reference
            val returnTypeStr = try {
                val getTypeReference = element.javaClass.getMethod("getTypeReference")
                val typeRef = getTypeReference.invoke(element)
                typeRef?.javaClass?.getMethod("getText")?.invoke(typeRef) as? String
            } catch (_: Exception) { null }

            // Get value parameters
            val getValueParameterList = element.javaClass.getMethod("getValueParameterList")
            val paramList = getValueParameterList.invoke(element) ?: return ParameterInfoResult(
                methodName = methodName,
                containingClass = containingClassName,
                returnType = returnTypeStr,
                parameters = emptyList()
            )

            val getParameters = paramList.javaClass.getMethod("getParameters")
            val parameters = getParameters.invoke(paramList) as List<*>

            val paramDetails = parameters.mapNotNull { param ->
                if (param == null) return@mapNotNull null
                try {
                    val paramName = param.javaClass.getMethod("getName").invoke(param) as? String ?: "unnamed"
                    val paramTypeRef = try {
                        param.javaClass.getMethod("getTypeReference").invoke(param)
                    } catch (_: Exception) { null }
                    val paramTypeStr = paramTypeRef?.javaClass?.getMethod("getText")?.invoke(paramTypeRef) as? String ?: "unknown"
                    val defaultValue = try {
                        val getDefaultValue = param.javaClass.getMethod("getDefaultValue")
                        val defVal = getDefaultValue.invoke(param)
                        defVal?.javaClass?.getMethod("getText")?.invoke(defVal) as? String
                    } catch (_: Exception) { null }
                    ParameterDetail(name = paramName, type = paramTypeStr, defaultValue = defaultValue)
                } catch (_: Exception) { null }
            }

            ParameterInfoResult(
                methodName = methodName,
                containingClass = containingClassName,
                returnType = returnTypeStr,
                parameters = paramDetails
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractFromPyFunction(element: PsiElement): ParameterInfoResult? {
        return try {
            val getName = element.javaClass.getMethod("getName")
            val methodName = getName.invoke(element) as? String ?: "unknown"

            // Get containing class
            val containingClassName = try {
                val parent = element.parent
                if (parent != null && isInstanceOf(parent, "com.jetbrains.python.psi.PyClass")) {
                    parent.javaClass.getMethod("getName").invoke(parent) as? String
                } else null
            } catch (_: Exception) { null }

            // Get parameter list
            val getParameterList = element.javaClass.getMethod("getParameterList")
            val paramList = getParameterList.invoke(element)
            val getParameters = paramList.javaClass.getMethod("getParameters")
            val parameters = getParameters.invoke(paramList) as Array<*>

            val paramDetails = parameters.mapNotNull { param ->
                if (param == null) return@mapNotNull null
                try {
                    val paramName = param.javaClass.getMethod("getName").invoke(param) as? String ?: "unnamed"
                    // Python type annotation
                    val paramTypeStr = try {
                        val getAnnotation = param.javaClass.getMethod("getAnnotation")
                        val annotation = getAnnotation.invoke(param)
                        annotation?.javaClass?.getMethod("getText")?.invoke(annotation) as? String
                    } catch (_: Exception) { null } ?: "Any"
                    val defaultValue = try {
                        val getDefaultValue = param.javaClass.getMethod("getDefaultValue")
                        val defVal = getDefaultValue.invoke(param)
                        defVal?.javaClass?.getMethod("getText")?.invoke(defVal) as? String
                    } catch (_: Exception) { null }
                    ParameterDetail(name = paramName, type = paramTypeStr, defaultValue = defaultValue)
                } catch (_: Exception) { null }
            }

            // Get return type annotation
            val returnTypeStr = try {
                val getAnnotation = element.javaClass.getMethod("getAnnotation")
                val annotation = getAnnotation.invoke(element)
                annotation?.javaClass?.getMethod("getText")?.invoke(annotation) as? String
            } catch (_: Exception) { null }

            ParameterInfoResult(
                methodName = methodName,
                containingClass = containingClassName,
                returnType = returnTypeStr,
                parameters = paramDetails
            )
        } catch (_: Exception) {
            null
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
