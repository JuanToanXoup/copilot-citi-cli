package com.citigroup.copilotchat.tools.psi

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

// ═══════════════════════════════════════════════════════════════
// Intelligence Tools
// ═══════════════════════════════════════════════════════════════

class GetDiagnosticsTool : PsiToolBase() {
    override val name = "ide_diagnostics"
    override val description = "Get code problems (errors, warnings) and available quick fixes for a file. Provides full IDE-level analysis including quick-fix suggestions and intention actions."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Path to file relative to project root"},"includeIntentions":{"type":"boolean","description":"Include available quick-fix intentions (default: false)"},"startLine":{"type":"integer","description":"Filter problems to start from this line"},"endLine":{"type":"integer","description":"Filter problems to end at this line"}},"required":["file"]}"""

    companion object {
        private const val DAEMON_ANALYSIS_WAIT_MS = 500L
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val filePath = arguments.str("file") ?: return createErrorResult("file is required")
        val includeIntentions = arguments.bool("includeIntentions") ?: false
        val filterStartLine = arguments.int("startLine")
        val filterEndLine = arguments.int("endLine")

        val virtualFile = suspendingReadAction {
            getPsiFile(project, filePath)?.virtualFile
        } ?: return createErrorResult("File not found: $filePath")

        // Ensure file is open so daemon can analyze it
        val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val wasAlreadyOpen = fileEditorManager.isFileOpen(virtualFile)

        if (!wasAlreadyOpen) {
            withContext(Dispatchers.EDT) {
                fileEditorManager.openFile(virtualFile, false)
            }
            delay(DAEMON_ANALYSIS_WAIT_MS)
        }

        return try {
            suspendingReadAction {
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

                // Collect highlights from DaemonCodeAnalyzer (now available since file is open)
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
        } finally {
            if (!wasAlreadyOpen) {
                withContext(Dispatchers.EDT) {
                    fileEditorManager.closeFile(virtualFile)
                }
            }
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
