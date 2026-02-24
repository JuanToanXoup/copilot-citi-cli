package com.citigroup.copilotchat.tools.psi

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

// ═══════════════════════════════════════════════════════════════
// Navigation Tools
// ═══════════════════════════════════════════════════════════════

class FindUsagesTool : PsiToolBase() {
    override val name = "ide_find_usages"
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
