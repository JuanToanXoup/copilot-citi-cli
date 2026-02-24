package com.citigroup.copilotchat.tools.psi

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.psi.codeStyle.MinusculeMatcher
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.util.indexing.FindSymbolParameters
import java.util.concurrent.ConcurrentHashMap

// ── Handler interfaces ──

interface LanguageHandler<T> {
    val languageId: String
    fun canHandle(element: PsiElement): Boolean
    fun isAvailable(): Boolean
}

interface TypeHierarchyHandler : LanguageHandler<TypeHierarchyData> {
    fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyData?
}

interface ImplementationsHandler : LanguageHandler<List<ImplementationData>> {
    fun findImplementations(element: PsiElement, project: Project): List<ImplementationData>?
}

interface CallHierarchyHandler : LanguageHandler<CallHierarchyData> {
    fun getCallHierarchy(element: PsiElement, project: Project, direction: String, depth: Int): CallHierarchyData?
}

interface SymbolSearchHandler : LanguageHandler<List<SymbolData>> {
    fun searchSymbols(project: Project, pattern: String, includeLibraries: Boolean, limit: Int): List<SymbolData>
}

interface SuperMethodsHandler : LanguageHandler<SuperMethodsData> {
    fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData?
}

interface StructureHandler : LanguageHandler<List<StructureNode>> {
    fun getFileStructure(file: PsiFile, project: Project): List<StructureNode>
}

// ── Language Handler Registry ──

object LanguageHandlerRegistry {
    private val LOG = logger<LanguageHandlerRegistry>()
    private val typeHierarchyHandlers = ConcurrentHashMap<String, TypeHierarchyHandler>()
    private val implementationsHandlers = ConcurrentHashMap<String, ImplementationsHandler>()
    private val callHierarchyHandlers = ConcurrentHashMap<String, CallHierarchyHandler>()
    private val symbolSearchHandlers = ConcurrentHashMap<String, SymbolSearchHandler>()
    private val superMethodsHandlers = ConcurrentHashMap<String, SuperMethodsHandler>()
    private val structureHandlers = ConcurrentHashMap<String, StructureHandler>()
    private var initialized = false

    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        initialized = true
        LOG.info("Registering language handlers...")
        registerJavaHandlers()
        try { registerPythonHandlers() } catch (e: Exception) { LOG.warn("Failed to register Python handlers: ${e.message}") }
        try { registerJavaScriptHandlers() } catch (e: Exception) { LOG.warn("Failed to register JavaScript handlers: ${e.message}") }
        try { registerGoHandlers() } catch (e: Exception) { LOG.warn("Failed to register Go handlers: ${e.message}") }
        try { registerRustHandlers() } catch (e: Exception) { LOG.warn("Failed to register Rust handlers: ${e.message}") }
        try { registerPhpHandlers() } catch (e: Exception) { LOG.warn("Failed to register PHP handlers: ${e.message}") }
        LOG.info("Language handlers registered")
    }

    private fun registerJavaHandlers() {
        if (!isJavaPluginAvailable()) return
        registerTypeHierarchyHandler(JavaTypeHierarchyHandler())
        registerImplementationsHandler(JavaImplementationsHandler())
        registerCallHierarchyHandler(JavaCallHierarchyHandler())
        registerSymbolSearchHandler(JavaSymbolSearchHandler())
        registerSuperMethodsHandler(JavaSuperMethodsHandler())
        registerStructureHandler(JavaStructureHandler())
        // Kotlin uses its own structure handler and delegates the rest to Java handlers
        registerTypeHierarchyHandler(KotlinTypeHierarchyHandler())
        registerImplementationsHandler(KotlinImplementationsHandler())
        registerCallHierarchyHandler(KotlinCallHierarchyHandler())
        registerSymbolSearchHandler(KotlinSymbolSearchHandler())
        registerSuperMethodsHandler(KotlinSuperMethodsHandler())
        registerStructureHandler(KotlinStructureHandler())
    }

    private fun registerPythonHandlers() {
        if (!isPythonPluginAvailable()) return
        try {
            Class.forName("com.jetbrains.python.psi.PyClass")
            Class.forName("com.jetbrains.python.psi.PyFunction")
            registerTypeHierarchyHandler(PythonTypeHierarchyHandler())
            registerImplementationsHandler(PythonImplementationsHandler())
            registerCallHierarchyHandler(PythonCallHierarchyHandler())
            registerSymbolSearchHandler(PythonSymbolSearchHandler())
            registerSuperMethodsHandler(PythonSuperMethodsHandler())
            registerStructureHandler(PythonStructureHandler())
            LOG.info("Registered Python handlers")
        } catch (e: ClassNotFoundException) {
            LOG.warn("Python PSI classes not found, skipping: ${e.message}")
        }
    }

    private fun registerJavaScriptHandlers() {
        if (!isJavaScriptPluginAvailable()) return
        try {
            Class.forName("com.intellij.lang.javascript.psi.JSFunction")
            registerTypeHierarchyHandler(JavaScriptTypeHierarchyHandler())
            registerImplementationsHandler(JavaScriptImplementationsHandler())
            registerCallHierarchyHandler(JavaScriptCallHierarchyHandler())
            registerSymbolSearchHandler(JavaScriptSymbolSearchHandler())
            registerSuperMethodsHandler(JavaScriptSuperMethodsHandler())
            // TypeScript delegates to JavaScript handlers
            registerTypeHierarchyHandler(TypeScriptTypeHierarchyHandler())
            registerImplementationsHandler(TypeScriptImplementationsHandler())
            registerCallHierarchyHandler(TypeScriptCallHierarchyHandler())
            registerSymbolSearchHandler(TypeScriptSymbolSearchHandler())
            registerSuperMethodsHandler(TypeScriptSuperMethodsHandler())
            LOG.info("Registered JavaScript and TypeScript handlers")
        } catch (e: ClassNotFoundException) {
            LOG.warn("JavaScript PSI classes not found, skipping: ${e.message}")
        }
    }

    private fun registerGoHandlers() {
        if (!isGoPluginAvailable()) return
        try {
            Class.forName("com.goide.psi.GoFile")
            Class.forName("com.goide.psi.GoTypeSpec")
            registerTypeHierarchyHandler(GoTypeHierarchyHandler())
            registerCallHierarchyHandler(GoCallHierarchyHandler())
            registerSymbolSearchHandler(GoSymbolSearchHandler())
            // Note: Go does not get ImplementationsHandler or SuperMethodsHandler because
            // Go uses implicit interfaces (structural typing) and has no classical inheritance.
            LOG.info("Registered Go handlers")
        } catch (e: ClassNotFoundException) {
            LOG.warn("Go PSI classes not found, skipping: ${e.message}")
        }
    }

    private fun registerRustHandlers() {
        if (!isRustPluginAvailable()) return
        try {
            Class.forName("org.rust.lang.core.psi.RsFile")
            Class.forName("org.rust.lang.core.psi.RsFunction")
            registerTypeHierarchyHandler(RustTypeHierarchyHandler())
            registerImplementationsHandler(RustImplementationsHandler())
            registerCallHierarchyHandler(RustCallHierarchyHandler())
            registerSymbolSearchHandler(RustSymbolSearchHandler())
            // Note: Rust does not get SuperMethodsHandler because Rust has no classical inheritance.
            LOG.info("Registered Rust handlers")
        } catch (e: ClassNotFoundException) {
            LOG.warn("Rust PSI classes not found, skipping: ${e.message}")
        }
    }

    private fun registerPhpHandlers() {
        if (!isPhpPluginAvailable()) return
        try {
            Class.forName("com.jetbrains.php.lang.psi.elements.PhpClass")
            Class.forName("com.jetbrains.php.lang.psi.elements.Method")
            registerTypeHierarchyHandler(PhpTypeHierarchyHandler())
            registerImplementationsHandler(PhpImplementationsHandler())
            registerCallHierarchyHandler(PhpCallHierarchyHandler())
            registerSymbolSearchHandler(PhpSymbolSearchHandler())
            registerSuperMethodsHandler(PhpSuperMethodsHandler())
            LOG.info("Registered PHP handlers")
        } catch (e: ClassNotFoundException) {
            LOG.warn("PHP PSI classes not found, skipping: ${e.message}")
        }
    }

    fun registerTypeHierarchyHandler(h: TypeHierarchyHandler) { typeHierarchyHandlers[h.languageId] = h }
    fun registerImplementationsHandler(h: ImplementationsHandler) { implementationsHandlers[h.languageId] = h }
    fun registerCallHierarchyHandler(h: CallHierarchyHandler) { callHierarchyHandlers[h.languageId] = h }
    fun registerSymbolSearchHandler(h: SymbolSearchHandler) { symbolSearchHandlers[h.languageId] = h }
    fun registerSuperMethodsHandler(h: SuperMethodsHandler) { superMethodsHandlers[h.languageId] = h }
    fun registerStructureHandler(h: StructureHandler) { structureHandlers[h.languageId] = h }

    fun getTypeHierarchyHandler(element: PsiElement) = findHandler(element, typeHierarchyHandlers)
    fun getImplementationsHandler(element: PsiElement) = findHandler(element, implementationsHandlers)
    fun getCallHierarchyHandler(element: PsiElement) = findHandler(element, callHierarchyHandlers)
    fun getSuperMethodsHandler(element: PsiElement) = findHandler(element, superMethodsHandlers)
    fun getAllSymbolSearchHandlers(): List<SymbolSearchHandler> = symbolSearchHandlers.values.filter { it.isAvailable() }
    fun getStructureHandler(file: PsiFile): StructureHandler? {
        val langId = file.language.id
        structureHandlers[langId]?.takeIf { it.isAvailable() }?.let { return it }
        structureHandlers.entries.firstOrNull { it.key.equals(langId, ignoreCase = true) && it.value.isAvailable() }?.value?.let { return it }
        file.language.baseLanguage?.let { base ->
            structureHandlers[base.id]?.takeIf { it.isAvailable() }?.let { return it }
        }
        return null
    }

    fun hasTypeHierarchyHandlers() = typeHierarchyHandlers.values.any { it.isAvailable() }
    fun hasSymbolSearchHandlers() = symbolSearchHandlers.values.any { it.isAvailable() }
    fun getSupportedLanguagesForTypeHierarchy() = typeHierarchyHandlers.filter { it.value.isAvailable() }.keys.toList()
    fun getSupportedLanguagesForImplementations() = implementationsHandlers.filter { it.value.isAvailable() }.keys.toList()
    fun getSupportedLanguagesForCallHierarchy() = callHierarchyHandlers.filter { it.value.isAvailable() }.keys.toList()
    fun getSupportedLanguagesForSymbolSearch() = symbolSearchHandlers.filter { it.value.isAvailable() }.keys.toList()
    fun getSupportedLanguagesForSuperMethods() = superMethodsHandlers.filter { it.value.isAvailable() }.keys.toList()
    fun getSupportedLanguagesForStructure() = structureHandlers.filter { it.value.isAvailable() }.keys.toList()

    private fun <T : LanguageHandler<*>> findHandler(element: PsiElement, handlers: Map<String, T>): T? {
        val lang = element.language
        handlers[lang.id]?.takeIf { it.isAvailable() && it.canHandle(element) }?.let { return it }
        lang.baseLanguage?.let { base ->
            handlers[base.id]?.takeIf { it.isAvailable() && it.canHandle(element) }?.let { return it }
        }
        return handlers.values.firstOrNull { it.isAvailable() && it.canHandle(element) }
    }
}

// ── Utility ──

fun isJavaPluginAvailable(): Boolean {
    return try { Class.forName("com.intellij.psi.PsiClass"); true } catch (_: ClassNotFoundException) { false }
}

fun isPythonPluginAvailable(): Boolean {
    return try { Class.forName("com.jetbrains.python.psi.PyClass"); true } catch (_: ClassNotFoundException) { false }
}

fun isJavaScriptPluginAvailable(): Boolean {
    return try { Class.forName("com.intellij.lang.javascript.psi.JSFunction"); true } catch (_: ClassNotFoundException) { false }
}

fun isGoPluginAvailable(): Boolean {
    return try { Class.forName("com.goide.psi.GoFile"); true } catch (_: ClassNotFoundException) { false }
}

fun isRustPluginAvailable(): Boolean {
    return try { Class.forName("org.rust.lang.core.psi.RsFile"); true } catch (_: ClassNotFoundException) { false }
}

fun isPhpPluginAvailable(): Boolean {
    return try { Class.forName("com.jetbrains.php.lang.psi.elements.PhpClass"); true } catch (_: ClassNotFoundException) { false }
}

internal val ktClassOrObjectClass: Class<*>? by lazy {
    try { Class.forName("org.jetbrains.kotlin.psi.KtClassOrObject") } catch (_: ClassNotFoundException) { null }
}
internal val ktNamedFunctionClass: Class<*>? by lazy {
    try { Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction") } catch (_: ClassNotFoundException) { null }
}
internal val lightClassExtensionsClass: Class<*>? by lazy {
    try { Class.forName("org.jetbrains.kotlin.asJava.LightClassUtilsKt") } catch (_: ClassNotFoundException) { null }
}

// ── Optimized Symbol Search ──

object OptimizedSymbolSearch {
    fun search(project: Project, pattern: String, scope: GlobalSearchScope, limit: Int, languageFilter: Set<String>? = null): List<SymbolData> {
        if (pattern.isBlank()) return emptyList()
        val results = mutableListOf<SymbolData>()
        val seen = mutableSetOf<String>()
        val matcher = NameUtil.buildMatcher("*$pattern", NameUtil.MatchingCaseSensitivity.NONE)
        try {
            for (contributor in ChooseByNameContributor.SYMBOL_EP_NAME.extensionList) {
                if (results.size >= limit) break
                try { processContributor(contributor, project, pattern, scope, limit, languageFilter, matcher, results, seen) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return results.sortedWith(compareBy({ !it.name.equals(pattern, ignoreCase = true) }, { -matcher.matchingDegree(it.name) })).take(limit)
    }

    private fun processContributor(contributor: ChooseByNameContributor, project: Project, pattern: String, scope: GlobalSearchScope, limit: Int, languageFilter: Set<String>?, matcher: MinusculeMatcher, results: MutableList<SymbolData>, seen: MutableSet<String>) {
        if (contributor is ChooseByNameContributorEx) {
            val names = mutableListOf<String>()
            contributor.processNames({ name -> if (matcher.matches(name)) names.add(name); names.size < limit * 3 }, scope, null)
            for (name in names) {
                if (results.size >= limit) break
                contributor.processElementsWithName(name, { item -> if (results.size >= limit) return@processElementsWithName false; convertToSymbolData(item, project, languageFilter)?.let { sd -> val key = "${sd.file}:${sd.line}:${sd.name}"; if (key !in seen) { seen.add(key); results.add(sd) } }; true }, FindSymbolParameters.wrap(pattern, scope))
            }
        } else {
            val names = contributor.getNames(project, true).filter { matcher.matches(it) }
            for (name in names) {
                if (results.size >= limit) break
                for (item in contributor.getItemsByName(name, pattern, project, true)) {
                    if (results.size >= limit) break
                    convertToSymbolData(item, project, languageFilter)?.let { sd -> val key = "${sd.file}:${sd.line}:${sd.name}"; if (key !in seen) { seen.add(key); results.add(sd) } }
                }
            }
        }
    }

    private fun convertToSymbolData(item: NavigationItem, project: Project, languageFilter: Set<String>?): SymbolData? {
        val element = when (item) { is PsiElement -> item; else -> try { item.javaClass.getMethod("getElement").invoke(item) as? PsiElement } catch (_: Exception) { null } } ?: return null
        val target = element.navigationElement ?: element
        val lang = getLanguageName(target)
        if (languageFilter != null && lang !in languageFilter) return null
        val file = target.containingFile?.virtualFile ?: return null
        val basePath = project.basePath ?: ""
        val name = (target as? PsiNamedElement)?.name ?: try { target.javaClass.getMethod("getName").invoke(target) as? String } catch (_: Exception) { null } ?: return null
        val qn = try { target.javaClass.getMethod("getQualifiedName").invoke(target) as? String } catch (_: Exception) { null }
        val line = target.containingFile?.let { pf -> PsiDocumentManager.getInstance(project).getDocument(pf)?.let { it.getLineNumber(target.textOffset) + 1 } } ?: 1
        val kind = determineKind(target)
        val container = getContainerName(target)
        return SymbolData(name, qn, kind, file.path.removePrefix(basePath).removePrefix("/"), line, container, lang)
    }

    private fun getLanguageName(e: PsiElement) = when (e.language.id) { "JAVA" -> "Java"; "kotlin" -> "Kotlin"; "Python" -> "Python"; "JavaScript", "ECMAScript 6", "JSX Harmony" -> "JavaScript"; "TypeScript", "TypeScript JSX" -> "TypeScript"; "go" -> "Go"; "PHP" -> "PHP"; "Rust" -> "Rust"; else -> e.language.displayName }

    private fun determineKind(e: PsiElement): String {
        val cn = e.javaClass.simpleName.lowercase()
        return when { cn.contains("class") -> "CLASS"; cn.contains("interface") -> "INTERFACE"; cn.contains("enum") -> "ENUM"; cn.contains("method") -> "METHOD"; cn.contains("function") -> "FUNCTION"; cn.contains("field") -> "FIELD"; cn.contains("variable") -> "VARIABLE"; cn.contains("property") -> "PROPERTY"; else -> "SYMBOL" }
    }

    private fun getContainerName(e: PsiElement): String? {
        var p = e.parent
        while (p != null) {
            val cn = p.javaClass.simpleName.lowercase()
            if (cn.contains("class") || cn.contains("type")) return try { p.javaClass.getMethod("getName").invoke(p) as? String } catch (_: Exception) { null }
            p = p.parent
        }
        return null
    }
}
