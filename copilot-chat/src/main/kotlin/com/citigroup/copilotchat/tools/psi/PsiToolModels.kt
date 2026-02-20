package com.citigroup.copilotchat.tools.psi

import kotlinx.serialization.Serializable

// ── Tool result wrapper ──

data class ToolCallResult(
    val content: List<ContentBlock>,
    val isError: Boolean
)

sealed class ContentBlock {
    data class Text(val text: String) : ContentBlock()
}

// ── Navigation models ──

@Serializable
data class UsageLocation(val file: String, val line: Int, val column: Int, val context: String, val type: String)

@Serializable
data class FindUsagesResult(val usages: List<UsageLocation>, val totalCount: Int)

@Serializable
data class DefinitionResult(val file: String, val line: Int, val column: Int, val preview: String, val symbolName: String)

@Serializable
data class TypeHierarchyResult(val element: TypeElement, val supertypes: List<TypeElement>, val subtypes: List<TypeElement>)

@Serializable
data class TypeElement(val name: String, val file: String?, val kind: String, val language: String? = null, val supertypes: List<TypeElement>? = null)

@Serializable
data class CallHierarchyResult(val element: CallElement, val calls: List<CallElement>)

@Serializable
data class CallElement(val name: String, val file: String, val line: Int, val language: String? = null, val children: List<CallElement>? = null)

@Serializable
data class ImplementationResult(val implementations: List<ImplementationLocation>, val totalCount: Int, val hint: String? = null)

@Serializable
data class ImplementationLocation(val name: String, val file: String, val line: Int, val kind: String, val language: String? = null)

// ── Search models ──

@Serializable
data class FindSymbolResult(val symbols: List<SymbolMatch>, val totalCount: Int, val query: String)

@Serializable
data class SymbolMatch(val name: String, val qualifiedName: String?, val kind: String, val file: String, val line: Int, val containerName: String?, val language: String? = null)

@Serializable
data class FindClassResult(val classes: List<SymbolMatch>, val totalCount: Int, val query: String)

@Serializable
data class FindFileResult(val files: List<FileMatch>, val totalCount: Int, val query: String)

@Serializable
data class FileMatch(val name: String, val path: String, val directory: String)

@Serializable
data class SearchTextResult(val matches: List<TextMatch>, val totalCount: Int, val query: String)

@Serializable
data class TextMatch(val file: String, val line: Int, val column: Int, val context: String, val contextType: String)

// ── Intelligence models ──

@Serializable
data class DiagnosticsResult(val problems: List<ProblemInfo>, val intentions: List<IntentionInfo>, val problemCount: Int, val intentionCount: Int)

@Serializable
data class ProblemInfo(val message: String, val severity: String, val file: String, val line: Int, val column: Int, val endLine: Int?, val endColumn: Int?)

@Serializable
data class IntentionInfo(val name: String, val description: String?)

@Serializable
data class QuickDocResult(val symbolName: String, val documentation: String, val containingClass: String?)

@Serializable
data class TypeInfoResult(val symbolName: String, val type: String, val canonicalType: String?, val kind: String)

@Serializable
data class ParameterInfoResult(val methodName: String, val containingClass: String?, val returnType: String?, val parameters: List<ParameterDetail>)

@Serializable
data class ParameterDetail(val name: String, val type: String, val defaultValue: String?)

@Serializable
data class StructuralSearchResult(val matches: List<StructuralMatch>, val totalCount: Int, val pattern: String)

@Serializable
data class StructuralMatch(val file: String, val line: Int, val matchedText: String)

// ── Refactoring models ──

@Serializable
data class RefactoringResult(val success: Boolean, val affectedFiles: List<String>, val changesCount: Int, val message: String)

// ── Project models ──

@Serializable
data class IndexStatusResult(val isDumbMode: Boolean, val isIndexing: Boolean, val indexingProgress: Double?)

// ── Super methods models ──

@Serializable
data class SuperMethodsResult(val method: MethodInfo, val hierarchy: List<SuperMethodInfo>, val totalCount: Int)

@Serializable
data class MethodInfo(val name: String, val signature: String, val containingClass: String, val file: String, val line: Int, val language: String? = null)

@Serializable
data class SuperMethodInfo(val name: String, val signature: String, val containingClass: String, val containingClassKind: String, val file: String?, val line: Int?, val isInterface: Boolean, val depth: Int, val language: String? = null)

// ── File structure models ──

@Serializable
data class FileStructureResult(val file: String, val language: String, val structure: String)

@Serializable
data class StructureNode(val name: String, val kind: StructureKind, val modifiers: List<String>, val signature: String?, val line: Int, val children: List<StructureNode> = emptyList())

@Serializable
enum class StructureKind {
    CLASS, INTERFACE, ENUM, ANNOTATION, RECORD, OBJECT, TRAIT,
    METHOD, FIELD, PROPERTY, CONSTRUCTOR,
    FUNCTION, VARIABLE, TYPE_ALIAS,
    NAMESPACE, PACKAGE, MODULE,
    UNKNOWN
}

// ── Language handler data classes (internal, not serialized) ──

data class TypeElementData(val name: String, val qualifiedName: String?, val file: String?, val line: Int?, val kind: String, val language: String, val supertypes: List<TypeElementData>? = null)
data class TypeHierarchyData(val element: TypeElementData, val supertypes: List<TypeElementData>, val subtypes: List<TypeElementData>)
data class ImplementationData(val name: String, val file: String, val line: Int, val kind: String, val language: String)
data class CallElementData(val name: String, val file: String, val line: Int, val language: String, val children: List<CallElementData>? = null)
data class CallHierarchyData(val element: CallElementData, val calls: List<CallElementData>)
data class SymbolData(val name: String, val qualifiedName: String?, val kind: String, val file: String, val line: Int, val containerName: String?, val language: String)
data class SuperMethodsData(val method: MethodData, val hierarchy: List<SuperMethodData>)
data class MethodData(val name: String, val signature: String, val containingClass: String, val file: String, val line: Int, val language: String)
data class SuperMethodData(val name: String, val signature: String, val containingClass: String, val containingClassKind: String, val file: String?, val line: Int?, val isInterface: Boolean, val depth: Int, val language: String)
