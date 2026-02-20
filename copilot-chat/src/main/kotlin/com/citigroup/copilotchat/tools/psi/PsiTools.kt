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
    override val description = "Find all references/usages of the symbol at the given position. Returns file locations, line numbers, and context for each usage."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Relative file path"},"line":{"type":"integer","description":"Line number (1-based)"},"column":{"type":"integer","description":"Column number (1-based)"},"includeLibraries":{"type":"boolean","description":"Include usages in libraries. Default: false"}},"required":["file","line","column"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)
        val file = arguments.str("file") ?: return createErrorResult("file is required")
        val line = arguments.int("line") ?: return createErrorResult("line is required")
        val column = arguments.int("column") ?: return createErrorResult("column is required")
        val includeLibs = arguments.bool("includeLibraries") ?: false

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
                val el = ref.element
                val psiFile = el.containingFile ?: return@Processor true
                val vf = psiFile.virtualFile ?: return@Processor true
                val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return@Processor true
                val ln = doc.getLineNumber(el.textOffset) + 1
                val col = el.textOffset - doc.getLineStartOffset(ln - 1) + 1
                val ctx = getLineText(doc, ln).trim()
                usages.add(UsageLocation(getRelativePath(project, vf), ln, col, ctx, "reference"))
                count.incrementAndGet() < 200
            })
        }

        val result = FindUsagesResult(usages.toList().sortedWith(compareBy({ it.file }, { it.line })), count.get())
        return createJsonResult(result)
    }
}

class FindDefinitionTool : PsiToolBase() {
    override val name = "ide_find_definition"
    override val description = "Go to the definition of the symbol at the given position. Returns the file, line, and preview of the definition."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Relative file path"},"line":{"type":"integer","description":"Line number (1-based)"},"column":{"type":"integer","description":"Column number (1-based)"}},"required":["file","line","column"]}"""

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
    override val description = "Search for text or regex patterns across project files using the IDE's indexed search. Faster and more accurate than grep."
    override val inputSchema = """{"type":"object","properties":{"query":{"type":"string","description":"Text or regex pattern to search for"},"isRegex":{"type":"boolean","description":"Treat query as regex. Default: false"},"caseSensitive":{"type":"boolean","description":"Case-sensitive search. Default: false"},"maxResults":{"type":"integer","description":"Max results to return. Default: 100"}},"required":["query"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val query = arguments.str("query") ?: return createErrorResult("query is required")
        val caseSensitive = arguments.bool("caseSensitive") ?: false
        val maxResults = arguments.int("maxResults") ?: 100

        val matches = ConcurrentLinkedQueue<TextMatch>()
        val count = AtomicInteger(0)
        val scope = GlobalSearchScope.projectScope(project)

        suspendingReadAction {
            val searchHelper = PsiSearchHelper.getInstance(project)
            val searchContext = UsageSearchContext.IN_CODE.toInt() or
                    UsageSearchContext.IN_STRINGS.toInt() or
                    UsageSearchContext.IN_COMMENTS.toInt()

            searchHelper.processElementsWithWord(
                { element, offsetInElement ->
                    checkCanceled()
                    if (count.get() >= maxResults) return@processElementsWithWord false
                    val psiFile = element.containingFile ?: return@processElementsWithWord true
                    val vf = psiFile.virtualFile ?: return@processElementsWithWord true
                    val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                        ?: return@processElementsWithWord true
                    val offset = element.textOffset + offsetInElement
                    val ln = doc.getLineNumber(offset) + 1
                    val col = offset - doc.getLineStartOffset(ln - 1) + 1
                    val ctx = getLineText(doc, ln).trim()
                    matches.add(TextMatch(getRelativePath(project, vf), ln, col, ctx, "code"))
                    count.incrementAndGet() < maxResults
                },
                scope,
                query,
                searchContext.toShort(),
                caseSensitive
            )
        }

        val result = SearchTextResult(
            matches.toList().sortedWith(compareBy({ it.file }, { it.line })).take(maxResults),
            count.get(),
            query
        )
        return createJsonResult(result)
    }
}

class FindClassTool : PsiToolBase() {
    override val name = "ide_find_class"
    override val description = "Search for classes/interfaces/enums by name pattern. Supports camelCase and fuzzy matching."
    override val inputSchema = """{"type":"object","properties":{"pattern":{"type":"string","description":"Class name pattern (supports camelCase matching, e.g. 'HM' matches 'HashMap')"},"includeLibraries":{"type":"boolean","description":"Include library classes. Default: false"},"maxResults":{"type":"integer","description":"Max results. Default: 50"}},"required":["pattern"]}"""

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
    override val description = "Search for files by name pattern. Supports fuzzy and partial name matching."
    override val inputSchema = """{"type":"object","properties":{"pattern":{"type":"string","description":"File name pattern to search for"},"maxResults":{"type":"integer","description":"Max results. Default: 50"}},"required":["pattern"]}"""

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
    override val description = "Search for symbols (functions, methods, variables, properties) by name pattern. Supports camelCase matching."
    override val inputSchema = """{"type":"object","properties":{"pattern":{"type":"string","description":"Symbol name pattern"},"includeLibraries":{"type":"boolean","description":"Include library symbols. Default: false"},"maxResults":{"type":"integer","description":"Max results. Default: 50"}},"required":["pattern"]}"""

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
    override val description = "Get the type hierarchy (supertypes and subtypes) for a class at the given position."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Relative file path"},"line":{"type":"integer","description":"Line number (1-based)"},"column":{"type":"integer","description":"Column number (1-based)"}},"required":["file","line","column"]}"""

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
    override val description = "Get the call hierarchy (callers or callees) for a method at the given position."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Relative file path"},"line":{"type":"integer","description":"Line number (1-based)"},"column":{"type":"integer","description":"Column number (1-based)"},"direction":{"type":"string","description":"'callers' or 'callees'. Default: 'callers'","enum":["callers","callees"]},"depth":{"type":"integer","description":"Max depth to traverse. Default: 3"}},"required":["file","line","column"]}"""

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
    override val description = "Find implementations of an interface/abstract class/method at the given position."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Relative file path"},"line":{"type":"integer","description":"Line number (1-based)"},"column":{"type":"integer","description":"Column number (1-based)"}},"required":["file","line","column"]}"""

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
    override val description = "Find super method declarations that the method at the given position overrides."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Relative file path"},"line":{"type":"integer","description":"Line number (1-based)"},"column":{"type":"integer","description":"Column number (1-based)"}},"required":["file","line","column"]}"""

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
    override val description = "Get the structure (classes, methods, fields, etc.) of a file in a tree format."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Relative file path"}},"required":["file"]}"""

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
    override val description = "Get compiler errors, warnings, and code inspection results for a file. The file must be open in the editor for full diagnostics."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Relative file path"},"includeIntentions":{"type":"boolean","description":"Include available quick-fix intentions. Default: false"}},"required":["file"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val filePath = arguments.str("file") ?: return createErrorResult("file is required")
        val includeIntentions = arguments.bool("includeIntentions") ?: false

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
                    val col = element.textOffset - doc.getLineStartOffset(ln - 1) + 1
                    problems.add(ProblemInfo(element.errorDescription, "ERROR", getRelativePath(project, psiFile.virtualFile), ln, col, null, null))
                }
            })

            // Collect cached highlights from DaemonCodeAnalyzer if the file has been analyzed
            try {
                val highlights = DaemonCodeAnalyzerEx.processHighlights(
                    doc, project, HighlightSeverity.WARNING,
                    0, doc.textLength
                ) { h ->
                    if (h.description != null) {
                        val startLine = doc.getLineNumber(h.startOffset) + 1
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
    override val description = "Get documentation for the symbol at the given position. Returns Javadoc, KDoc, or other language-specific documentation."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Relative file path"},"line":{"type":"integer","description":"Line number (1-based)"},"column":{"type":"integer","description":"Column number (1-based)"}},"required":["file","line","column"]}"""

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
    override val description = "Get the type information for the expression or variable at the given position."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Relative file path"},"line":{"type":"integer","description":"Line number (1-based)"},"column":{"type":"integer","description":"Column number (1-based)"}},"required":["file","line","column"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = arguments.str("file") ?: return createErrorResult("file is required")
        val line = arguments.int("line") ?: return createErrorResult("line is required")
        val column = arguments.int("column") ?: return createErrorResult("column is required")

        return suspendingReadAction {
            val element = findPsiElement(project, file, line, column)
                ?: return@suspendingReadAction createErrorResult("No element found at $file:$line:$column")
            val target = PsiUtils.resolveTargetElement(element) ?: element

            // Try to get type via reflection (works for Java PsiVariable, PsiExpression, etc.)
            val typeText = tryGetType(target)
                ?: return@suspendingReadAction createErrorResult("Cannot determine type at $file:$line:$column")

            val symbolName = (target as? PsiNamedElement)?.name ?: target.text?.take(50) ?: "unknown"
            val kind = target.javaClass.simpleName.replace("Psi", "").replace("Impl", "")
            createJsonResult(TypeInfoResult(symbolName, typeText, null, kind))
        }
    }

    private fun tryGetType(element: PsiElement): String? {
        // Try PsiVariable.getType()
        try {
            val getType = element.javaClass.getMethod("getType")
            val type = getType.invoke(element) ?: return null
            val presentable = type.javaClass.getMethod("getPresentableText")
            return presentable.invoke(type) as? String
        } catch (_: Exception) {}

        // Try PsiExpression.getType()
        try {
            val getType = element.javaClass.getMethod("getType")
            val type = getType.invoke(element) ?: return null
            val canonical = type.javaClass.getMethod("getCanonicalText")
            return canonical.invoke(type) as? String
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
    override val description = "Get parameter information for the method/function at the given position."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Relative file path"},"line":{"type":"integer","description":"Line number (1-based)"},"column":{"type":"integer","description":"Column number (1-based)"}},"required":["file","line","column"]}"""

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
        // Try PsiMethod via reflection
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
    override val description = "Search for code patterns using IntelliJ's structural search. Matches code structures rather than text patterns."
    override val inputSchema = """{"type":"object","properties":{"pattern":{"type":"string","description":"Structural search pattern using IntelliJ SSR syntax with template variables"},"fileType":{"type":"string","description":"File type hint: 'java', 'kotlin', etc. Default: 'java'"}},"required":["pattern"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val pattern = arguments.str("pattern") ?: return createErrorResult("pattern is required")
        val fileType = arguments.str("fileType") ?: "java"

        return try {
            val matcherClass = Class.forName("com.intellij.structuralsearch.Matcher")
            val optionsClass = Class.forName("com.intellij.structuralsearch.MatchOptions")

            val options = optionsClass.getConstructor().newInstance()
            optionsClass.getMethod("setSearchPattern", String::class.java).invoke(options, pattern)
            optionsClass.getMethod("setScope", SearchScope::class.java)
                .invoke(options, GlobalSearchScope.projectScope(project))

            // Set file type
            try {
                val ftManager = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                val ft = ftManager.getStdFileType(fileType.uppercase())
                optionsClass.getMethod("setFileType", com.intellij.openapi.fileTypes.FileType::class.java).invoke(options, ft)
            } catch (_: Exception) {}

            val matcher = matcherClass.getConstructor(Project::class.java).newInstance(project)

            // Use Matcher.findMatches(MatchOptions) which returns List<MatchResult>
            val findMatchesMethod = matcherClass.methods.firstOrNull {
                it.name == "findMatches" && it.parameterCount == 1
            } ?: return createErrorResult("Structural search API not available")

            val matches = mutableListOf<StructuralMatch>()
            suspendingReadAction {
                @Suppress("UNCHECKED_CAST")
                val results = findMatchesMethod.invoke(matcher, options) as? List<Any> ?: emptyList()
                for (result in results.take(100)) {
                    try {
                        val getMatch = result.javaClass.getMethod("getMatch")
                        val matchElement = getMatch.invoke(result) as? PsiElement ?: continue
                        val psiFile = matchElement.containingFile ?: continue
                        val vf = psiFile.virtualFile ?: continue
                        val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: continue
                        val ln = doc.getLineNumber(matchElement.textOffset) + 1
                        matches.add(StructuralMatch(getRelativePath(project, vf), ln, matchElement.text.take(200)))
                    } catch (_: Exception) {}
                }
            }

            createJsonResult(StructuralSearchResult(matches.take(100), matches.size, pattern))
        } catch (e: ClassNotFoundException) {
            createErrorResult("Structural search is not available in this IDE edition")
        } catch (e: Exception) {
            createErrorResult("Structural search failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Refactoring Tools
// ═══════════════════════════════════════════════════════════════

class RenameSymbolTool : PsiToolBase() {
    override val name = "ide_rename_symbol"
    override val description = "Rename a symbol and all its references across the project. Uses IDE refactoring for safe, accurate renaming."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Relative file path"},"line":{"type":"integer","description":"Line number (1-based)"},"column":{"type":"integer","description":"Column number (1-based)"},"newName":{"type":"string","description":"The new name for the symbol"}},"required":["file","line","column","newName"]}"""

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
    override val description = "Safely delete a symbol, checking for usages first. Reports any references that would be broken."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Relative file path"},"line":{"type":"integer","description":"Line number (1-based)"},"column":{"type":"integer","description":"Column number (1-based)"},"searchInComments":{"type":"boolean","description":"Also search for references in comments and strings. Default: false"}},"required":["file","line","column"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)
        val file = arguments.str("file") ?: return createErrorResult("file is required")
        val line = arguments.int("line") ?: return createErrorResult("line is required")
        val column = arguments.int("column") ?: return createErrorResult("column is required")
        val searchInComments = arguments.bool("searchInComments") ?: false

        val element = suspendingReadAction { findPsiElement(project, file, line, column) }
            ?: return createErrorResult("No element found at $file:$line:$column")
        val target = suspendingReadAction { PsiUtils.resolveTargetElement(element) }
            ?: return createErrorResult("Cannot resolve symbol at $file:$line:$column")

        // First check for usages
        val usageCount = suspendingReadAction {
            var count = 0
            ReferencesSearch.search(target, GlobalSearchScope.projectScope(project)).forEach(Processor { count++; count < 100 })
            count
        }

        if (usageCount > 0) {
            return createErrorResult("Cannot safely delete: found $usageCount usage(s). Remove references first or use force delete.")
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
}

// ═══════════════════════════════════════════════════════════════
// Project Tools
// ═══════════════════════════════════════════════════════════════

class GetIndexStatusTool : PsiToolBase() {
    override val name = "ide_get_index_status"
    override val description = "Check if the IDE is currently indexing. Many PSI tools require indexing to be complete."
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
