package com.citigroup.copilotchat.tools.psi

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

// ── JSON argument helpers ──

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

// ── Tool Registry ──

object PsiTools {
    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(PsiTools::class.java)

    val allTools: List<PsiToolBase> by lazy {
        try {
            LanguageHandlerRegistry.ensureInitialized()
            listOf(
                FindUsagesTool(), FindDefinitionTool(), SearchTextTool(),
                FindClassTool(), FindFileTool(), FindSymbolTool(),
                TypeHierarchyTool(), CallHierarchyTool(), FindImplementationsTool(),
                FindSuperMethodsTool(), FileStructureTool(),
                GetDiagnosticsTool(), QuickDocTool(), TypeInfoTool(),
                ParameterInfoTool(), StructuralSearchTool(),
                RenameSymbolTool(), SafeDeleteTool(),
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
     * The model picks the action (e.g., "find_references") and passes params.
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

// ═══════════════════════════════════════════════════════════════
// Navigation Tools
// ═══════════════════════════════════════════════════════════════

class FindUsagesTool : PsiToolBase() {
    override val name = "ide_find_references"
    override val description = "Find all references/usages of a symbol across the project. Use when you need to understand how a class, method, field, or variable is used before modifying or removing it."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Path to file relative to project root"},"line":{"type":"integer","description":"1-based line number where the symbol is located"},"column":{"type":"integer","description":"1-based column number within the line"},"includeLibraries":{"type":"boolean","description":"Include usages in libraries (default: false)"},"maxResults":{"type":"integer","description":"Maximum number of references to return (default: 100, max: 500)"}},"required":["file","line","column"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)
        val file = arguments.str("file") ?: return createErrorResult("file is required")
        val line = arguments.int("line") ?: return createErrorResult("line is required")
        val column = arguments.int("column") ?: return createErrorResult("column is required")
        val includeLibs = arguments.bool("includeLibraries") ?: false
        val maxResults = (arguments.int("maxResults") ?: 100).coerceIn(1, 500)

        val element = suspendingReadAction { findPsiElement(project, file, line, column) }
            ?: return createErrorResult("No element found at $file:$line:$column")
        val target = suspendingReadAction { PsiUtils.resolveTargetElement(element) }
            ?: return createErrorResult("Cannot resolve symbol at $file:$line:$column")

        val scope = if (includeLibs) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
        val usages = ConcurrentLinkedQueue<UsageLocation>()
        val count = AtomicInteger(0)

        suspendingReadAction {
            ReferencesSearch.search(target, scope).forEach(Processor { ref ->
                checkCanceled()
                if (count.get() >= maxResults) return@Processor false
                val el = ref.element
                val psiFile = el.containingFile ?: return@Processor true
                val vf = psiFile.virtualFile ?: return@Processor true
                val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@Processor true
                val ln = doc.getLineNumber(el.textOffset) + 1
                val col = el.textOffset - doc.getLineStartOffset(ln - 1) + 1
                val ctx = getLineText(doc, ln).trim()
                usages.add(UsageLocation(getRelativePath(project, vf), ln, col, ctx, classifyUsage(el)))
                val slot = count.incrementAndGet()
                slot < maxResults
            })
        }

        val usagesList = usages.toList().sortedWith(compareBy({ it.file }, { it.line }))
        val result = FindUsagesResult(usagesList, usagesList.size)
        return createJsonResult(result)
    }

    private fun classifyUsage(element: PsiElement): String {
        val parentClass = element.parent?.javaClass?.simpleName ?: "Unknown"
        return when {
            parentClass.contains("MethodCall") -> "method_call"
            parentClass.contains("Field") -> "field_access"
            parentClass.contains("Import") -> "import"
            parentClass.contains("Parameter") -> "parameter"
            parentClass.contains("Variable") -> "variable"
            parentClass.contains("Reference") -> "reference"
            else -> "reference"
        }
    }
}

class FindDefinitionTool : PsiToolBase() {
    override val name = "ide_find_definition"
    override val description = "Navigate to where a symbol is defined (Go to Definition). Use when you see a symbol reference and need to find its declaration."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Path to file relative to project root"},"line":{"type":"integer","description":"1-based line number where the symbol reference is located"},"column":{"type":"integer","description":"1-based column number within the line"}},"required":["file","line","column"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)
        val file = arguments.str("file") ?: return createErrorResult("file is required")
        val line = arguments.int("line") ?: return createErrorResult("line is required")
        val column = arguments.int("column") ?: return createErrorResult("column is required")

        return suspendingReadAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@suspendingReadAction createErrorResult("No element found at $file:$line:$column")
            val target = PsiUtils.resolveTargetElement(element)
                ?: return@suspendingReadAction createErrorResult("Cannot resolve symbol at $file:$line:$column")
            val navEl = target.navigationElement ?: target
            val psiFile = navEl.containingFile ?: return@suspendingReadAction createErrorResult("Definition has no file")
            val vf = psiFile.virtualFile ?: return@suspendingReadAction createErrorResult("Definition has no virtual file")
            val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return@suspendingReadAction createErrorResult("No document for definition file")
            val ln = doc.getLineNumber(navEl.textOffset) + 1
            val col = navEl.textOffset - doc.getLineStartOffset(ln - 1) + 1
            val preview = getLineText(doc, ln).trim()
            val symbolName = (navEl as? PsiNamedElement)?.name ?: navEl.text?.take(50) ?: "unknown"
            createJsonResult(DefinitionResult(getRelativePath(project, vf), ln, col, preview, symbolName))
        }
    }
}

class SearchTextTool : PsiToolBase() {
    override val name = "ide_search_text"
    override val description = "Search for exact word matches using the IDE's pre-built word index. Significantly faster than file scanning, but only matches exact words — not regex or patterns. Use for finding identifiers, keywords, or specific strings."
    override val inputSchema = """{"type":"object","properties":{"query":{"type":"string","description":"Exact word to search for (not a pattern/regex)"},"context":{"type":"string","description":"Where to search: \"code\", \"comments\", \"strings\", or \"all\" (default: \"all\")","enum":["code","comments","strings","all"]},"caseSensitive":{"type":"boolean","description":"Case-sensitive search (default: true)"},"maxResults":{"type":"integer","description":"Maximum results to return (default: 100, max: 500)"}},"required":["query"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val query = arguments.str("query") ?: return createErrorResult("query is required")
        if (query.isBlank()) return createErrorResult("query cannot be empty")
        val contextStr = arguments.str("context") ?: "all"
        val caseSensitive = arguments.bool("caseSensitive") ?: true
        val maxResults = (arguments.int("maxResults") ?: 100).coerceIn(1, 500)

        val searchContext = parseSearchContext(contextStr)
        val matches = ConcurrentLinkedQueue<TextMatch>()
        val count = AtomicInteger(0)
        val scope = GlobalSearchScope.projectScope(project)

        suspendingReadAction {
            val searchHelper = PsiSearchHelper.getInstance(project)

            searchHelper.processElementsWithWord(
                { element, _ ->
                    checkCanceled()
                    if (count.get() >= maxResults) return@processElementsWithWord false
                    val psiFile = element.containingFile ?: return@processElementsWithWord true
                    val vf = psiFile.virtualFile ?: return@processElementsWithWord true
                    val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                        ?: return@processElementsWithWord true
                    val offset = element.textOffset
                    val ln = doc.getLineNumber(offset) + 1
                    val col = offset - doc.getLineStartOffset(ln - 1) + 1
                    val ctx = getLineText(doc, ln).trim()
                    val ctxType = determineContextType(element, searchContext)
                    matches.add(TextMatch(getRelativePath(project, vf), ln, col, ctx, ctxType))
                    val slot = count.incrementAndGet()
                    slot < maxResults
                },
                scope,
                query,
                searchContext,
                caseSensitive
            )
        }

        val result = SearchTextResult(
            matches.toList().sortedWith(compareBy({ it.file }, { it.line })),
            matches.size,
            query
        )
        return createJsonResult(result)
    }

    private fun parseSearchContext(contextStr: String): Short = when (contextStr.lowercase()) {
        "code" -> UsageSearchContext.IN_CODE
        "comments" -> UsageSearchContext.IN_COMMENTS
        "strings" -> UsageSearchContext.IN_STRINGS
        else -> UsageSearchContext.ANY
    }

    private fun determineContextType(element: PsiElement, searchContext: Short): String {
        if (searchContext == UsageSearchContext.IN_COMMENTS) return "COMMENT"
        if (searchContext == UsageSearchContext.IN_STRINGS) return "STRING_LITERAL"
        if (searchContext == UsageSearchContext.IN_CODE) return "CODE"
        val elementType = element.node?.elementType?.toString() ?: ""
        return when {
            elementType.contains("COMMENT", ignoreCase = true) -> "COMMENT"
            elementType.contains("STRING", ignoreCase = true) || elementType.contains("LITERAL", ignoreCase = true) -> "STRING_LITERAL"
            else -> "CODE"
        }
    }
}

class FindClassTool : PsiToolBase() {
    override val name = "ide_find_class"
    override val description = "Search for classes and interfaces by name only. Faster than ide_find_symbol when you only need classes. Supports camelCase, substring, and wildcard matching."
    override val inputSchema = """{"type":"object","properties":{"pattern":{"type":"string","description":"Search pattern supporting substring and camelCase matching (e.g. 'HM' matches 'HashMap')"},"includeLibraries":{"type":"boolean","description":"Include library classes (default: false)"},"maxResults":{"type":"integer","description":"Maximum results to return (default: 50, max: 100)"}},"required":["pattern"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)
        val pattern = arguments.str("pattern") ?: return createErrorResult("pattern is required")
        val includeLibs = arguments.bool("includeLibraries") ?: false
        val maxResults = arguments.int("maxResults") ?: 50

        val scope = if (includeLibs) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
        val results = mutableListOf<SymbolMatch>()
        val seen = mutableSetOf<String>()
        val matcher = NameUtil.buildMatcher("*$pattern", NameUtil.MatchingCaseSensitivity.NONE)

        suspendingReadAction {
            for (contributor in ChooseByNameContributor.CLASS_EP_NAME.extensionList) {
                if (results.size >= maxResults) break
                try {
                    if (contributor is ChooseByNameContributorEx) {
                        val names = mutableListOf<String>()
                        contributor.processNames({ name ->
                            if (matcher.matches(name)) names.add(name)
                            names.size < maxResults * 3
                        }, scope, null)
                        for (name in names) {
                            if (results.size >= maxResults) break
                            contributor.processElementsWithName(name, { item ->
                                if (results.size >= maxResults) return@processElementsWithName false
                                convertClassItem(item, project, matcher, seen, results)
                                true
                            }, FindSymbolParameters.wrap(pattern, scope))
                        }
                    } else {
                        val names = contributor.getNames(project, !includeLibs).filter { matcher.matches(it) }
                        for (name in names) {
                            if (results.size >= maxResults) break
                            for (item in contributor.getItemsByName(name, pattern, project, !includeLibs)) {
                                if (results.size >= maxResults) break
                                convertClassItem(item, project, matcher, seen, results)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        val sorted = results.sortedWith(compareBy({ !it.name.equals(pattern, ignoreCase = true) }, { -matcher.matchingDegree(it.name) })).take(maxResults)
        return createJsonResult(FindClassResult(sorted, sorted.size, pattern))
    }

    private fun convertClassItem(item: NavigationItem, project: Project, matcher: MinusculeMatcher, seen: MutableSet<String>, results: MutableList<SymbolMatch>) {
        val element = (item as? PsiElement)?.navigationElement ?: return
        val file = element.containingFile?.virtualFile ?: return
        val name = (element as? PsiNamedElement)?.name ?: return
        val qn = try { element.javaClass.getMethod("getQualifiedName").invoke(element) as? String } catch (_: Exception) { null }
        val key = "${file.path}:$name"
        if (key in seen) return
        seen.add(key)
        val doc = PsiDocumentManager.getInstance(project).getDocument(element.containingFile) ?: return
        val line = doc.getLineNumber(element.textOffset) + 1
        val kind = determineKind(element)
        val basePath = project.basePath ?: ""
        results.add(SymbolMatch(name, qn, kind, file.path.removePrefix(basePath).removePrefix("/"), line, null, element.language.displayName))
    }
}

class FindFileTool : PsiToolBase() {
    override val name = "ide_find_file"
    override val description = "Search for files by name. Very fast file lookup using the IDE's file index. Supports camelCase, substring, and wildcard matching."
    override val inputSchema = """{"type":"object","properties":{"pattern":{"type":"string","description":"File name pattern supporting substring and fuzzy matching"},"maxResults":{"type":"integer","description":"Maximum results to return (default: 50, max: 100)"}},"required":["pattern"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val pattern = arguments.str("pattern") ?: return createErrorResult("pattern is required")
        val maxResults = arguments.int("maxResults") ?: 50

        val scope = GlobalSearchScope.projectScope(project)
        val results = mutableListOf<FileMatch>()
        val seen = mutableSetOf<String>()
        val matcher = NameUtil.buildMatcher("*$pattern", NameUtil.MatchingCaseSensitivity.NONE)

        suspendingReadAction {
            for (contributor in ChooseByNameContributor.FILE_EP_NAME.extensionList) {
                if (results.size >= maxResults) break
                try {
                    if (contributor is ChooseByNameContributorEx) {
                        val names = mutableListOf<String>()
                        contributor.processNames({ name ->
                            if (matcher.matches(name)) names.add(name)
                            names.size < maxResults * 3
                        }, scope, null)
                        for (name in names) {
                            if (results.size >= maxResults) break
                            contributor.processElementsWithName(name, { item ->
                                if (results.size >= maxResults) return@processElementsWithName false
                                convertFileItem(item, project, seen, results)
                                true
                            }, FindSymbolParameters.wrap(pattern, scope))
                        }
                    } else {
                        val names = contributor.getNames(project, true).filter { matcher.matches(it) }
                        for (name in names) {
                            if (results.size >= maxResults) break
                            for (item in contributor.getItemsByName(name, pattern, project, true)) {
                                if (results.size >= maxResults) break
                                convertFileItem(item, project, seen, results)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        val sorted = results.sortedWith(compareBy({ !it.name.equals(pattern, ignoreCase = true) }, { it.path })).take(maxResults)
        return createJsonResult(FindFileResult(sorted, sorted.size, pattern))
    }

    private fun convertFileItem(item: NavigationItem, project: Project, seen: MutableSet<String>, results: MutableList<FileMatch>) {
        val element = (item as? PsiElement) ?: return
        val vf = element.containingFile?.virtualFile ?: (element as? PsiFile)?.virtualFile ?: return
        val basePath = project.basePath ?: ""
        val relPath = vf.path.removePrefix(basePath).removePrefix("/")
        if (relPath in seen) return
        seen.add(relPath)
        results.add(FileMatch(vf.name, relPath, vf.parent?.path?.removePrefix(basePath)?.removePrefix("/") ?: ""))
    }
}

class FindSymbolTool : PsiToolBase() {
    override val name = "ide_find_symbol"
    override val description = "Search for symbols (classes, methods, fields, functions) by name across the codebase. Supports substring and camelCase matching. Use ide_find_class for faster class-only searches."
    override val inputSchema = """{"type":"object","properties":{"pattern":{"type":"string","description":"Search pattern supporting substring and camelCase matching"},"includeLibraries":{"type":"boolean","description":"Include library symbols (default: false)"},"maxResults":{"type":"integer","description":"Maximum results to return (default: 50, max: 100)"}},"required":["pattern"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)
        val pattern = arguments.str("pattern") ?: return createErrorResult("pattern is required")
        val includeLibs = arguments.bool("includeLibraries") ?: false
        val maxResults = arguments.int("maxResults") ?: 50

        val handlers = LanguageHandlerRegistry.getAllSymbolSearchHandlers()
        val allResults = mutableListOf<SymbolData>()

        suspendingReadAction {
            for (handler in handlers) {
                if (allResults.size >= maxResults) break
                try {
                    allResults.addAll(handler.searchSymbols(project, pattern, includeLibs, maxResults - allResults.size))
                } catch (_: Exception) {}
            }
        }

        // If no language-specific handlers, fall back to platform SYMBOL_EP_NAME
        if (allResults.isEmpty()) {
            val scope = if (includeLibs) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
            suspendingReadAction {
                allResults.addAll(OptimizedSymbolSearch.search(project, pattern, scope, maxResults))
            }
        }

        val symbols = allResults.take(maxResults).map {
            SymbolMatch(it.name, it.qualifiedName, it.kind, it.file, it.line, it.containerName, it.language)
        }
        return createJsonResult(FindSymbolResult(symbols, symbols.size, pattern))
    }
}

class TypeHierarchyTool : PsiToolBase() {
    override val name = "ide_type_hierarchy"
    override val description = "Get the complete inheritance hierarchy (parents AND children) for a class or interface. Use to understand class relationships and inheritance chains."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Path to file relative to project root"},"line":{"type":"integer","description":"1-based line number where the class is defined"},"column":{"type":"integer","description":"1-based column number within the line"}},"required":["file","line","column"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)
        val file = arguments.str("file") ?: return createErrorResult("file is required")
        val line = arguments.int("line") ?: return createErrorResult("line is required")
        val column = arguments.int("column") ?: return createErrorResult("column is required")

        return suspendingReadAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@suspendingReadAction createErrorResult("No element found at $file:$line:$column")
            val handler = LanguageHandlerRegistry.getTypeHierarchyHandler(element)
                ?: return@suspendingReadAction createErrorResult("Type hierarchy not supported for ${element.language.displayName}")
            val data = handler.getTypeHierarchy(element, project)
                ?: return@suspendingReadAction createErrorResult("Could not resolve type at $file:$line:$column")

            val result = TypeHierarchyResult(
                element = convertTypeElement(data.element),
                supertypes = data.supertypes.map { convertTypeElement(it) },
                subtypes = data.subtypes.map { convertTypeElement(it) }
            )
            createJsonResult(result)
        }
    }

    private fun convertTypeElement(d: TypeElementData): TypeElement = TypeElement(
        d.name, d.file, d.kind, d.language,
        d.supertypes?.map { convertTypeElement(it) }?.takeIf { it.isNotEmpty() }
    )
}

class CallHierarchyTool : PsiToolBase() {
    override val name = "ide_call_hierarchy"
    override val description = "Build a call hierarchy tree for a method/function. Use to trace execution flow — find what calls this method (callers) or what this method calls (callees)."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Path to file relative to project root"},"line":{"type":"integer","description":"1-based line number"},"column":{"type":"integer","description":"1-based column number"},"direction":{"type":"string","description":"'callers' (methods that call this method) or 'callees' (methods this method calls)","enum":["callers","callees"]},"depth":{"type":"integer","description":"How many levels deep to traverse (default: 3, max: 5)"}},"required":["file","line","column"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)
        val file = arguments.str("file") ?: return createErrorResult("file is required")
        val line = arguments.int("line") ?: return createErrorResult("line is required")
        val column = arguments.int("column") ?: return createErrorResult("column is required")
        val direction = arguments.str("direction") ?: "callers"
        val depth = arguments.int("depth") ?: 3

        return suspendingReadAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@suspendingReadAction createErrorResult("No element found at $file:$line:$column")
            val handler = LanguageHandlerRegistry.getCallHierarchyHandler(element)
                ?: return@suspendingReadAction createErrorResult("Call hierarchy not supported for ${element.language.displayName}")
            val data = handler.getCallHierarchy(element, project, direction, depth)
                ?: return@suspendingReadAction createErrorResult("Could not resolve method at $file:$line:$column")

            val result = CallHierarchyResult(
                element = convertCallElement(data.element),
                calls = data.calls.map { convertCallElement(it) }
            )
            createJsonResult(result)
        }
    }

    private fun convertCallElement(d: CallElementData): CallElement = CallElement(
        d.name, d.file, d.line, d.language,
        d.children?.map { convertCallElement(it) }?.takeIf { it.isNotEmpty() }
    )
}

class FindImplementationsTool : PsiToolBase() {
    override val name = "ide_find_implementations"
    override val description = "Find all implementations of an interface, abstract class, or abstract method. The cursor must be on an abstract type or method declaration."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Path to file relative to project root"},"line":{"type":"integer","description":"1-based line number"},"column":{"type":"integer","description":"1-based column number"}},"required":["file","line","column"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)
        val file = arguments.str("file") ?: return createErrorResult("file is required")
        val line = arguments.int("line") ?: return createErrorResult("line is required")
        val column = arguments.int("column") ?: return createErrorResult("column is required")

        return suspendingReadAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@suspendingReadAction createErrorResult("No element found at $file:$line:$column")
            val handler = LanguageHandlerRegistry.getImplementationsHandler(element)
                ?: return@suspendingReadAction createErrorResult("Find implementations not supported for ${element.language.displayName}")
            val data = handler.findImplementations(element, project)
                ?: return@suspendingReadAction createErrorResult("Could not resolve symbol at $file:$line:$column")

            val impls = data.map { ImplementationLocation(it.name, it.file, it.line, it.kind, it.language) }
            createJsonResult(ImplementationResult(impls, impls.size))
        }
    }
}

class FindSuperMethodsTool : PsiToolBase() {
    override val name = "ide_find_super_methods"
    override val description = "Find parent methods that a method overrides or implements. Use to navigate up the inheritance chain and understand method origins."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Path to file relative to project root"},"line":{"type":"integer","description":"1-based line number (can be any line within the method)"},"column":{"type":"integer","description":"1-based column number (can be any position within the method)"}},"required":["file","line","column"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)
        val file = arguments.str("file") ?: return createErrorResult("file is required")
        val line = arguments.int("line") ?: return createErrorResult("line is required")
        val column = arguments.int("column") ?: return createErrorResult("column is required")

        return suspendingReadAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@suspendingReadAction createErrorResult("No element found at $file:$line:$column")
            val handler = LanguageHandlerRegistry.getSuperMethodsHandler(element)
                ?: return@suspendingReadAction createErrorResult("Find super methods not supported for ${element.language.displayName}")
            val data = handler.findSuperMethods(element, project)
                ?: return@suspendingReadAction createErrorResult("Could not resolve method at $file:$line:$column")

            val result = SuperMethodsResult(
                method = MethodInfo(data.method.name, data.method.signature, data.method.containingClass, data.method.file, data.method.line, data.method.language),
                hierarchy = data.hierarchy.map { SuperMethodInfo(it.name, it.signature, it.containingClass, it.containingClassKind, it.file, it.line, it.isInterface, it.depth, it.language) },
                totalCount = data.hierarchy.size
            )
            createJsonResult(result)
        }
    }
}

class FileStructureTool : PsiToolBase() {
    override val name = "ide_file_structure"
    override val description = "Get the hierarchical structure of a source file (similar to IDE's Structure view). Shows all classes, methods, fields with their nesting and line numbers."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Path to file relative to project root (e.g. 'src/main/java/com/example/MyClass.java')"}},"required":["file"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val filePath = arguments.str("file") ?: return createErrorResult("file is required")

        return suspendingReadAction {
            val psiFile = getPsiFile(project, filePath)
                ?: return@suspendingReadAction createErrorResult("File not found: $filePath")
            val handler = LanguageHandlerRegistry.getStructureHandler(psiFile)
            if (handler != null) {
                val nodes = handler.getFileStructure(psiFile, project)
                val formatted = TreeFormatter.format(nodes, filePath, psiFile.language.displayName)
                createJsonResult(FileStructureResult(filePath, psiFile.language.displayName, formatted))
            } else {
                // Fallback: basic PSI traversal
                val sb = StringBuilder()
                sb.appendLine("$filePath (${psiFile.language.displayName})")
                psiFile.accept(object : PsiRecursiveElementVisitor() {
                    override fun visitElement(element: PsiElement) {
                        if (element is PsiNamedElement && element.name != null) {
                            val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                            val ln = doc?.let { it.getLineNumber(element.textOffset) + 1 } ?: 0
                            sb.appendLine("  ${element.javaClass.simpleName}: ${element.name}  [line $ln]")
                        }
                        super.visitElement(element)
                    }
                })
                createJsonResult(FileStructureResult(filePath, psiFile.language.displayName, sb.toString().trimEnd()))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Intelligence Tools
// ═══════════════════════════════════════════════════════════════

class GetDiagnosticsTool : PsiToolBase() {
    override val name = "ide_diagnostics"
    override val description = "Get code problems (errors, warnings) and available quick fixes for a file. Provides full IDE-level analysis including quick-fix suggestions and intention actions."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Path to file relative to project root"},"includeIntentions":{"type":"boolean","description":"Include available quick-fix intentions (default: false)"},"startLine":{"type":"integer","description":"Filter problems to start from this line"},"endLine":{"type":"integer","description":"Filter problems to end at this line"}},"required":["file"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val filePath = arguments.str("file") ?: return createErrorResult("file is required")
        val includeIntentions = arguments.bool("includeIntentions") ?: false
        val filterStartLine = arguments.int("startLine")
        val filterEndLine = arguments.int("endLine")

        return suspendingReadAction {
            val psiFile = getPsiFile(project, filePath)
                ?: return@suspendingReadAction createErrorResult("File not found: $filePath")
            val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return@suspendingReadAction createErrorResult("No document for file: $filePath")

            val problems = mutableListOf<ProblemInfo>()

            // Collect syntax errors from PSI tree
            psiFile.accept(object : PsiRecursiveElementVisitor() {
                override fun visitErrorElement(element: PsiErrorElement) {
                    val ln = doc.getLineNumber(element.textOffset) + 1
                    if (filterStartLine != null && ln < filterStartLine) return
                    if (filterEndLine != null && ln > filterEndLine) return
                    val col = element.textOffset - doc.getLineStartOffset(ln - 1) + 1
                    problems.add(ProblemInfo(element.errorDescription, "ERROR", getRelativePath(project, psiFile.virtualFile), ln, col, null, null))
                }
            })

            // Collect cached highlights from DaemonCodeAnalyzer if the file has been analyzed
            try {
                DaemonCodeAnalyzerEx.processHighlights(
                    doc, project, HighlightSeverity.WARNING,
                    0, doc.textLength
                ) { h ->
                    if (h.description != null) {
                        val startLine = doc.getLineNumber(h.startOffset) + 1
                        if (filterStartLine != null && startLine < filterStartLine) return@processHighlights true
                        if (filterEndLine != null && startLine > filterEndLine) return@processHighlights true
                        val startCol = h.startOffset - doc.getLineStartOffset(startLine - 1) + 1
                        val endLine = doc.getLineNumber(h.endOffset) + 1
                        val endCol = h.endOffset - doc.getLineStartOffset(endLine - 1) + 1
                        problems.add(ProblemInfo(h.description, h.severity.displayName, getRelativePath(project, psiFile.virtualFile), startLine, startCol, endLine, endCol))
                    }
                    problems.size < 200
                }
            } catch (_: Exception) {}

            val intentions = if (includeIntentions) {
                try {
                    val manager = IntentionManager.getInstance()
                    manager.availableIntentions.take(20).map { IntentionInfo(it.familyName, it.text.takeIf { t -> t != it.familyName }) }
                } catch (_: Exception) { emptyList() }
            } else emptyList()

            createJsonResult(DiagnosticsResult(problems, intentions, problems.size, intentions.size))
        }
    }
}

class QuickDocTool : PsiToolBase() {
    override val name = "ide_quick_doc"
    override val description = "Get rendered documentation (JavaDoc, KDoc, docstrings, etc.) for a symbol at a position. Works across all languages supported by the IDE."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Path to file relative to project root"},"line":{"type":"integer","description":"1-based line number where the symbol is located"},"column":{"type":"integer","description":"1-based column number within the line"}},"required":["file","line","column"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments.str("file") ?: return createErrorResult("file is required")
        val line = arguments.int("line") ?: return createErrorResult("line is required")
        val column = arguments.int("column") ?: return createErrorResult("column is required")

        return suspendingReadAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@suspendingReadAction createErrorResult("No element found at $file:$line:$column")
            val target = PsiUtils.resolveTargetElement(element) ?: element

            var docText: String? = null
            for (provider in DocumentationProvider.EP_NAME.extensionList) {
                try {
                    val doc = provider.generateDoc(target, element)
                    if (!doc.isNullOrBlank()) {
                        docText = doc.replace(Regex("<[^>]+>"), "").trim() // Strip HTML
                        break
                    }
                } catch (_: Exception) {}
            }

            if (docText == null) {
                return@suspendingReadAction createErrorResult("No documentation available for symbol at $file:$line:$column")
            }

            val symbolName = (target as? PsiNamedElement)?.name ?: "unknown"
            val containingClass = PsiUtils.getContainingClass(target)?.name
            createJsonResult(QuickDocResult(symbolName, docText, containingClass))
        }
    }
}

class TypeInfoTool : PsiToolBase() {
    override val name = "ide_type_info"
    override val description = "Get the type of an expression, variable, parameter, or field at a position. Works across all languages supported by the IDE."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Path to file relative to project root"},"line":{"type":"integer","description":"1-based line number where the expression/variable is located"},"column":{"type":"integer","description":"1-based column number within the line"}},"required":["file","line","column"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments.str("file") ?: return createErrorResult("file is required")
        val line = arguments.int("line") ?: return createErrorResult("line is required")
        val column = arguments.int("column") ?: return createErrorResult("column is required")

        return suspendingReadAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@suspendingReadAction createErrorResult("No element found at $file:$line:$column")
            val target = PsiUtils.resolveTargetElement(element) ?: element

            // Try ExpressionTypeProvider EP first (works across all languages)
            val typeFromEP = tryGetTypeViaProvider(element) ?: tryGetTypeViaProvider(target)

            // Fall back to reflection-based approach
            val typeText = typeFromEP ?: tryGetTypeViaReflection(target) ?: tryGetTypeViaReflection(element)
                ?: return@suspendingReadAction createErrorResult("Cannot determine type at $file:$line:$column")

            val symbolName = (target as? PsiNamedElement)?.name ?: target.text?.take(50) ?: "unknown"
            val kind = target.javaClass.simpleName.replace("Psi", "").replace("Impl", "")
            createJsonResult(TypeInfoResult(symbolName, typeText, null, kind))
        }
    }

    private fun tryGetTypeViaProvider(element: PsiElement): String? {
        try {
            val epName = com.intellij.openapi.extensions.ExtensionPointName
                .create<Any>("com.intellij.expressionTypeProvider")
            for (provider in epName.extensionList) {
                try {
                    val getExprs = provider.javaClass.getMethod("getExpressionsAt", PsiElement::class.java)
                    @Suppress("UNCHECKED_CAST")
                    val expressions = getExprs.invoke(provider, element) as? List<*> ?: continue
                    for (expr in expressions) {
                        if (expr == null) continue
                        val getHint = provider.javaClass.getMethod("getInformationHint", PsiElement::class.java)
                        val hint = getHint.invoke(provider, expr) as? String
                        if (!hint.isNullOrBlank()) {
                            return hint.replace(Regex("<[^>]+>"), "").trim()
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return null
    }

    private fun tryGetTypeViaReflection(element: PsiElement): String? {
        // Try getType() -> getPresentableText() (PsiVariable, PsiExpression, etc.)
        try {
            val getType = element.javaClass.getMethod("getType")
            val type = getType.invoke(element) ?: return null
            val presentable = try { type.javaClass.getMethod("getPresentableText").invoke(type) as? String } catch (_: Exception) { null }
            if (presentable != null) return presentable
            val canonical = try { type.javaClass.getMethod("getCanonicalText").invoke(type) as? String } catch (_: Exception) { null }
            if (canonical != null) return canonical
        } catch (_: Exception) {}

        // Walk up to find a typed element
        var current = element.parent
        repeat(5) {
            if (current == null) return null
            try {
                val getType = current!!.javaClass.getMethod("getType")
                val type = getType.invoke(current) ?: return@repeat
                val presentable = type.javaClass.getMethod("getPresentableText")
                return presentable.invoke(type) as? String
            } catch (_: Exception) {}
            current = current?.parent
        }
        return null
    }
}

class ParameterInfoTool : PsiToolBase() {
    override val name = "ide_parameter_info"
    override val description = "Get parameter signatures for a method or function at a call site or declaration. Works across all languages supported by the IDE."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Path to file relative to project root"},"line":{"type":"integer","description":"1-based line number where the method call or declaration is"},"column":{"type":"integer","description":"1-based column number within the line"}},"required":["file","line","column"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments.str("file") ?: return createErrorResult("file is required")
        val line = arguments.int("line") ?: return createErrorResult("line is required")
        val column = arguments.int("column") ?: return createErrorResult("column is required")

        return suspendingReadAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@suspendingReadAction createErrorResult("No element found at $file:$line:$column")
            val target = PsiUtils.resolveTargetElement(element) ?: element

            // Try Java PsiMethod
            val params = tryExtractParams(target)
                ?: return@suspendingReadAction createErrorResult("Cannot extract parameters at $file:$line:$column")

            createJsonResult(params)
        }
    }

    private fun tryExtractParams(element: PsiElement): ParameterInfoResult? {
        // Try Java PsiMethod
        try {
            val psiMethodClass = Class.forName("com.intellij.psi.PsiMethod")
            if (psiMethodClass.isInstance(element)) {
                val getName = psiMethodClass.getMethod("getName")
                val name = getName.invoke(element) as String
                val getParamList = psiMethodClass.getMethod("getParameterList")
                val paramList = getParamList.invoke(element)
                val getParams = paramList.javaClass.getMethod("getParameters")
                @Suppress("UNCHECKED_CAST")
                val params = getParams.invoke(paramList) as Array<Any>
                val details = params.map { p ->
                    val pName = p.javaClass.getMethod("getName").invoke(p) as String
                    val pType = p.javaClass.getMethod("getType").invoke(p)
                    val pTypeText = pType.javaClass.getMethod("getPresentableText").invoke(pType) as String
                    ParameterDetail(pName, pTypeText, null)
                }
                val returnType = try {
                    val getReturnType = psiMethodClass.getMethod("getReturnType")
                    val rt = getReturnType.invoke(element)
                    rt?.javaClass?.getMethod("getPresentableText")?.invoke(rt) as? String
                } catch (_: Exception) { null }
                val containingClass = try {
                    val getCC = psiMethodClass.getMethod("getContainingClass")
                    val cc = getCC.invoke(element)
                    cc?.javaClass?.getMethod("getQualifiedName")?.invoke(cc) as? String
                } catch (_: Exception) { null }
                return ParameterInfoResult(name, containingClass, returnType, details)
            }
        } catch (_: Exception) {}

        // Try Kotlin KtNamedFunction
        try {
            val ktFuncClass = Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction")
            if (ktFuncClass.isInstance(element)) {
                val name = (element.javaClass.getMethod("getName").invoke(element) as? String) ?: "unknown"
                val paramList = element.javaClass.getMethod("getValueParameterList").invoke(element) as? PsiElement
                val details = if (paramList != null) {
                    @Suppress("UNCHECKED_CAST")
                    val parameters = paramList.javaClass.getMethod("getParameters").invoke(paramList) as? List<*> ?: emptyList<Any>()
                    parameters.filterIsInstance<PsiElement>().map { p ->
                        val pName = (p.javaClass.getMethod("getName").invoke(p) as? String) ?: "?"
                        val pType = try { (p.javaClass.getMethod("getTypeReference").invoke(p) as? PsiElement)?.text } catch (_: Exception) { null }
                        ParameterDetail(pName, pType ?: "Any", null)
                    }
                } else emptyList()
                val returnType = try { (element.javaClass.getMethod("getTypeReference").invoke(element) as? PsiElement)?.text } catch (_: Exception) { null }
                val containingClass = try {
                    val ktClsClass = Class.forName("org.jetbrains.kotlin.psi.KtClassOrObject")
                    @Suppress("UNCHECKED_CAST")
                    val cc = com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, ktClsClass as Class<out PsiElement>)
                    cc?.let { it.javaClass.getMethod("getName").invoke(it) as? String }
                } catch (_: Exception) { null }
                return ParameterInfoResult(name, containingClass, returnType, details)
            }
        } catch (_: Exception) {}

        // Try Python PyFunction
        try {
            val pyFuncClass = Class.forName("com.jetbrains.python.psi.PyFunction")
            if (pyFuncClass.isInstance(element)) {
                val name = (element.javaClass.getMethod("getName").invoke(element) as? String) ?: "unknown"
                val paramList = element.javaClass.getMethod("getParameterList").invoke(element)
                @Suppress("UNCHECKED_CAST")
                val parameters = paramList.javaClass.getMethod("getParameters").invoke(paramList) as? Array<*> ?: emptyArray<Any>()
                val details = parameters.filterIsInstance<PsiElement>().map { p ->
                    val pName = (p.javaClass.getMethod("getName").invoke(p) as? String) ?: "?"
                    val pType = try { p.javaClass.getMethod("getTypeText").invoke(p) as? String } catch (_: Exception) { null }
                    ParameterDetail(pName, pType ?: "Any", null)
                }
                val returnType = try {
                    val annotation = element.javaClass.getMethod("getAnnotation").invoke(element) as? PsiElement
                    annotation?.text
                } catch (_: Exception) { null }
                val containingClass = try {
                    val pyClsClass = Class.forName("com.jetbrains.python.psi.PyClass")
                    @Suppress("UNCHECKED_CAST")
                    val cc = com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, pyClsClass as Class<out PsiElement>)
                    cc?.let { it.javaClass.getMethod("getName").invoke(it) as? String }
                } catch (_: Exception) { null }
                return ParameterInfoResult(name, containingClass, returnType, details)
            }
        } catch (_: Exception) {}

        // Try JavaScript/TypeScript JSFunction
        try {
            val jsFuncClass = Class.forName("com.intellij.lang.javascript.psi.JSFunction")
            if (jsFuncClass.isInstance(element)) {
                val name = (element.javaClass.getMethod("getName").invoke(element) as? String) ?: "unknown"
                val paramList = element.javaClass.getMethod("getParameterList").invoke(element)
                @Suppress("UNCHECKED_CAST")
                val parameters = paramList.javaClass.getMethod("getParameters").invoke(paramList) as? Array<*> ?: emptyArray<Any>()
                val details = parameters.filterIsInstance<PsiElement>().map { p ->
                    val pName = (p.javaClass.getMethod("getName").invoke(p) as? String) ?: "?"
                    val pType = try { p.javaClass.getMethod("getType").invoke(p)?.toString() } catch (_: Exception) { null }
                    ParameterDetail(pName, pType ?: "any", null)
                }
                val returnType = try { element.javaClass.getMethod("getReturnType").invoke(element)?.toString() } catch (_: Exception) { null }
                val containingClass = try {
                    val jsClsClass = Class.forName("com.intellij.lang.javascript.psi.ecmal4.JSClass")
                    @Suppress("UNCHECKED_CAST")
                    val cc = com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, jsClsClass as Class<out PsiElement>)
                    cc?.let { it.javaClass.getMethod("getName").invoke(it) as? String }
                } catch (_: Exception) { null }
                return ParameterInfoResult(name, containingClass, returnType, details)
            }
        } catch (_: Exception) {}

        // Walk up to find a method-like element
        var current = element.parent
        repeat(10) {
            if (current == null) return null
            val result = tryExtractParams(current!!)
            if (result != null) return result
            current = current?.parent
        }
        return null
    }
}

class StructuralSearchTool : PsiToolBase() {
    override val name = "ide_structural_search"
    override val description = "Search for code patterns using IntelliJ's structural search engine. Finds code that matches a structural pattern, not just text."
    override val inputSchema = """{"type":"object","properties":{"pattern":{"type":"string","description":"Structural search pattern using template variable syntax for capture variables"},"fileType":{"type":"string","description":"File type to search in: 'Java', 'Kotlin', 'Python', 'JavaScript', etc. (default: 'Java')"},"scope":{"type":"string","description":"Search scope: 'project' (default) or 'file' (requires file parameter)"},"file":{"type":"string","description":"Path to file relative to project root. Only used when scope is 'file'."},"maxResults":{"type":"integer","description":"Maximum number of results to return (default: 50, max: 200)"}},"required":["pattern"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val pattern = arguments.str("pattern") ?: return createErrorResult("pattern is required")
        val fileType = arguments.str("fileType") ?: "Java"
        val scopeStr = arguments.str("scope") ?: "project"
        val filePath = arguments.str("file")
        val maxResults = (arguments.int("maxResults") ?: 50).coerceIn(1, 200)

        if (scopeStr == "file" && filePath == null) {
            return createErrorResult("When scope is 'file', the 'file' parameter is required.")
        }

        requireSmartMode(project)

        return suspendingReadAction {
            try {
                val matchOptionsClass = Class.forName("com.intellij.structuralsearch.MatchOptions")
                val matcherClass = Class.forName("com.intellij.structuralsearch.Matcher")
                val matchResultSinkClass = Class.forName("com.intellij.structuralsearch.MatchResultSink")
                val matchResultClass = Class.forName("com.intellij.structuralsearch.MatchResult")

                val matchOptions = matchOptionsClass.getConstructor().newInstance()

                // Set search pattern
                matchOptionsClass.getMethod("setSearchPattern", String::class.java).invoke(matchOptions, pattern)

                // Resolve LanguageFileType
                val resolvedFileType = resolveLanguageFileType(fileType)
                if (resolvedFileType != null) {
                    try {
                        matchOptionsClass.getMethod("setFileType", com.intellij.openapi.fileTypes.FileType::class.java).invoke(matchOptions, resolvedFileType)
                    } catch (_: Exception) {}
                }

                // Set recursive
                try { matchOptionsClass.getMethod("setRecursiveSearch", Boolean::class.javaPrimitiveType).invoke(matchOptions, true) } catch (_: Exception) {}

                // Set scope
                val searchScope = when (scopeStr) {
                    "file" -> {
                        val vf = resolveFile(project, filePath!!)
                            ?: return@suspendingReadAction createErrorResult("File not found: $filePath")
                        GlobalSearchScope.fileScope(project, vf)
                    }
                    else -> GlobalSearchScope.projectScope(project)
                }
                try { matchOptionsClass.getMethod("setScope", SearchScope::class.java).invoke(matchOptions, searchScope) } catch (_: Exception) {}

                val collectedResults = mutableListOf<Any>()
                val matcher = matcherClass.getConstructor(Project::class.java, matchOptionsClass).newInstance(project, matchOptions)

                // Use MatchResultSink callback API
                val sinkProxy = java.lang.reflect.Proxy.newProxyInstance(
                    matchResultSinkClass.classLoader,
                    arrayOf(matchResultSinkClass)
                ) { _, method, args ->
                    when (method.name) {
                        "newMatch" -> {
                            if (args != null && args.isNotEmpty()) collectedResults.add(args[0])
                            null
                        }
                        "getProgressIndicator" -> {
                            try { Class.forName("com.intellij.openapi.progress.EmptyProgressIndicator").getConstructor().newInstance() } catch (_: Exception) { null }
                        }
                        else -> null
                    }
                }

                // Try MatchResultSink-based findMatches first
                val sinkMethod = matcherClass.methods.firstOrNull { it.name == "findMatches" && it.parameterCount == 1 && matchResultSinkClass.isAssignableFrom(it.parameterTypes[0]) }
                if (sinkMethod != null) {
                    sinkMethod.invoke(matcher, sinkProxy)
                } else {
                    // Fallback: findMatches() returning List
                    val listMethod = matcherClass.methods.firstOrNull { it.name == "findMatches" && it.parameterCount == 0 }
                        ?: return@suspendingReadAction createErrorResult("Structural search API not available in this IDE edition")
                    @Suppress("UNCHECKED_CAST")
                    val results = listMethod.invoke(matcher) as? List<Any> ?: emptyList()
                    collectedResults.addAll(results)
                }

                val getMatchMethod = try { matchResultClass.getMethod("getMatch") } catch (_: Exception) { null }
                val matches = collectedResults.take(maxResults).mapNotNull { result ->
                    try {
                        val matchElement = getMatchMethod?.invoke(result) as? PsiElement ?: return@mapNotNull null
                        val psiFile = matchElement.containingFile ?: return@mapNotNull null
                        val vf = psiFile.virtualFile ?: return@mapNotNull null
                        val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@mapNotNull null
                        val ln = doc.getLineNumber(matchElement.textOffset) + 1
                        StructuralMatch(getRelativePath(project, vf), ln, matchElement.text.take(200))
                    } catch (_: Exception) { null }
                }

                createJsonResult(StructuralSearchResult(matches, collectedResults.size, pattern))
            } catch (e: ClassNotFoundException) {
                createErrorResult("Structural search is not available in this IDE edition")
            } catch (e: Exception) {
                createErrorResult("Structural search failed: ${e.message}")
            }
        }
    }

    private fun resolveLanguageFileType(fileTypeName: String): com.intellij.openapi.fileTypes.FileType? {
        val registry = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
        // Try exact match among LanguageFileType instances
        for (ft in registry.registeredFileTypes) {
            if (ft is com.intellij.openapi.fileTypes.LanguageFileType && ft.name.equals(fileTypeName, ignoreCase = true)) return ft
        }
        // Fallback: try by extension
        val extensionMap = mapOf("java" to "java", "kotlin" to "kt", "python" to "py", "javascript" to "js", "typescript" to "ts", "xml" to "xml", "html" to "html", "css" to "css", "json" to "json", "yaml" to "yaml", "go" to "go", "rust" to "rs", "php" to "php")
        val ext = extensionMap[fileTypeName.lowercase()] ?: fileTypeName.lowercase()
        val ft = registry.getFileTypeByExtension(ext)
        return ft as? com.intellij.openapi.fileTypes.LanguageFileType
    }
}

// ═══════════════════════════════════════════════════════════════
// Refactoring Tools
// ═══════════════════════════════════════════════════════════════

class RenameSymbolTool : PsiToolBase() {
    override val name = "ide_rename_symbol"
    override val description = "Rename a symbol and update all references across the project. Use instead of find-and-replace for safe, semantic renaming. Automatically renames related elements: getters/setters, overriding methods, constructor parameters, and test classes."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Path to file relative to project root"},"line":{"type":"integer","description":"1-based line number where the symbol is located"},"column":{"type":"integer","description":"1-based column number"},"newName":{"type":"string","description":"The new name for the symbol"}},"required":["file","line","column","newName"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)
        val file = arguments.str("file") ?: return createErrorResult("file is required")
        val line = arguments.int("line") ?: return createErrorResult("line is required")
        val column = arguments.int("column") ?: return createErrorResult("column is required")
        val newName = arguments.str("newName") ?: return createErrorResult("newName is required")

        val element = suspendingReadAction { findPsiElement(project, file, line, column) }
            ?: return createErrorResult("No element found at $file:$line:$column")
        val target = suspendingReadAction { PsiUtils.resolveTargetElement(element) }
            ?: return createErrorResult("Cannot resolve symbol at $file:$line:$column")

        // Validate the new name against language naming rules
        val isValidName = suspendingReadAction {
            try {
                val lang = target.language
                val validator = com.intellij.lang.LanguageExtension<com.intellij.lang.refactoring.NamesValidator>("com.intellij.lang.namesValidator")
                    .forLanguage(lang)
                validator?.isIdentifier(newName, project) ?: true
            } catch (_: Exception) { true }
        }
        if (!isValidName) {
            return createErrorResult("'$newName' is not a valid identifier for ${suspendingReadAction { target.language.displayName }}")
        }

        return try {
            val processorClass = Class.forName("com.intellij.refactoring.rename.RenameProcessor")
            val affectedFiles = mutableSetOf<String>()

            suspendingWriteAction(project, "Rename to $newName") {
                val processor = processorClass.getConstructor(
                    Project::class.java, PsiElement::class.java,
                    String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
                ).newInstance(project, target, newName, false, false)
                processorClass.getMethod("run").invoke(processor)

                // Collect affected files
                try {
                    val getUsages = processorClass.getMethod("findUsages")
                    @Suppress("UNCHECKED_CAST")
                    val usages = getUsages.invoke(processor) as? Array<Any>
                    usages?.forEach { usage ->
                        try {
                            val vf = usage.javaClass.getMethod("getFile").invoke(usage)
                            if (vf != null) {
                                val path = (vf as com.intellij.openapi.vfs.VirtualFile).path
                                val basePath = project.basePath ?: ""
                                affectedFiles.add(path.removePrefix(basePath).removePrefix("/"))
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }

            createJsonResult(RefactoringResult(true, affectedFiles.toList(), affectedFiles.size, "Renamed to '$newName'"))
        } catch (e: Exception) {
            createErrorResult("Rename failed: ${e.cause?.message ?: e.message}")
        }
    }
}

class SafeDeleteTool : PsiToolBase() {
    override val name = "ide_safe_delete"
    override val description = "Delete a symbol or file safely by first checking for usages. Use when removing code to avoid breaking references. Reports any references that would be broken before deleting."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Path to file relative to project root"},"line":{"type":"integer","description":"1-based line number where the symbol is located (required when targetType is 'symbol')"},"column":{"type":"integer","description":"1-based column number (required when targetType is 'symbol')"},"searchInComments":{"type":"boolean","description":"Also search for references in comments and strings (default: false)"},"targetType":{"type":"string","description":"What to delete: 'symbol' (default) or 'file'","enum":["symbol","file"]},"force":{"type":"boolean","description":"Force delete even if usages exist (default: false)"}},"required":["file"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)
        val file = arguments.str("file") ?: return createErrorResult("file is required")
        val targetType = arguments.str("targetType") ?: "symbol"
        val force = arguments.bool("force") ?: false
        val searchInComments = arguments.bool("searchInComments") ?: false

        if (targetType == "file") {
            return deleteFile(project, file, force, searchInComments)
        }

        // Symbol mode - line and column required
        val line = arguments.int("line") ?: return createErrorResult("line is required for symbol deletion")
        val column = arguments.int("column") ?: return createErrorResult("column is required for symbol deletion")

        val element = suspendingReadAction { findPsiElement(project, file, line, column) }
            ?: return createErrorResult("No element found at $file:$line:$column")
        val target = suspendingReadAction { PsiUtils.resolveTargetElement(element) }
            ?: return createErrorResult("Cannot resolve symbol at $file:$line:$column")

        // Check for usages
        if (!force) {
            val usageCount = suspendingReadAction {
                var count = 0
                ReferencesSearch.search(target, GlobalSearchScope.projectScope(project)).forEach(Processor { count++; count < 100 })
                count
            }
            if (usageCount > 0) {
                return createErrorResult("Cannot safely delete: found $usageCount usage(s). Remove references first or set force=true.")
            }
        }

        return try {
            val processorClass = Class.forName("com.intellij.refactoring.safeDelete.SafeDeleteProcessor")
            val affectedFile = suspendingReadAction { target.containingFile?.virtualFile?.let { getRelativePath(project, it) } ?: file }

            suspendingWriteAction(project, "Safe delete") {
                val create = processorClass.getMethod("createInstance",
                    Project::class.java, Array<PsiElement>::class.java,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                val processor = create.invoke(null, project, arrayOf(target), searchInComments, false, false)
                processorClass.getMethod("run").invoke(processor)
            }

            createJsonResult(RefactoringResult(true, listOf(affectedFile), 1, "Successfully deleted"))
        } catch (e: Exception) {
            createErrorResult("Safe delete failed: ${e.cause?.message ?: e.message}")
        }
    }

    private suspend fun deleteFile(project: Project, filePath: String, force: Boolean, searchInComments: Boolean): ToolCallResult {
        val psiFile = suspendingReadAction { getPsiFile(project, filePath) }
            ?: return createErrorResult("File not found: $filePath")

        if (!force) {
            val usageCount = suspendingReadAction {
                var count = 0
                ReferencesSearch.search(psiFile, GlobalSearchScope.projectScope(project)).forEach(Processor { count++; count < 100 })
                count
            }
            if (usageCount > 0) {
                return createErrorResult("Cannot safely delete file: found $usageCount usage(s) of symbols in this file. Set force=true to delete anyway.")
            }
        }

        return try {
            val processorClass = Class.forName("com.intellij.refactoring.safeDelete.SafeDeleteProcessor")
            suspendingWriteAction(project, "Safe delete file") {
                val create = processorClass.getMethod("createInstance",
                    Project::class.java, Array<PsiElement>::class.java,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                val processor = create.invoke(null, project, arrayOf(psiFile), searchInComments, false, false)
                processorClass.getMethod("run").invoke(processor)
            }
            createJsonResult(RefactoringResult(true, listOf(filePath), 1, "File deleted: $filePath"))
        } catch (e: Exception) {
            createErrorResult("File delete failed: ${e.cause?.message ?: e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Project Tools
// ═══════════════════════════════════════════════════════════════

class GetIndexStatusTool : PsiToolBase() {
    override val name = "ide_get_index_status"
    override val description = "Check if the IDE is ready for code intelligence operations. Use when other tools fail with indexing errors."
    override val inputSchema = """{"type":"object","properties":{},"required":[]}"""
    override val requiresPsiSync = false

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val isDumb = DumbService.isDumb(project)
        return createJsonResult(IndexStatusResult(isDumb, isDumb, null))
    }
}

// ── Shared utilities ──

private fun determineKind(element: PsiElement): String {
    val cn = element.javaClass.simpleName.lowercase()
    return when {
        cn.contains("class") -> "CLASS"
        cn.contains("interface") -> "INTERFACE"
        cn.contains("enum") -> "ENUM"
        cn.contains("method") -> "METHOD"
        cn.contains("function") -> "FUNCTION"
        cn.contains("field") -> "FIELD"
        cn.contains("variable") -> "VARIABLE"
        cn.contains("property") -> "PROPERTY"
        else -> "SYMBOL"
    }
}
