package com.citigroup.copilotchat.tools.psi

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
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

private val ktClassOrObjectClass: Class<*>? by lazy {
    try { Class.forName("org.jetbrains.kotlin.psi.KtClassOrObject") } catch (_: ClassNotFoundException) { null }
}
private val ktNamedFunctionClass: Class<*>? by lazy {
    try { Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction") } catch (_: ClassNotFoundException) { null }
}
private val lightClassExtensionsClass: Class<*>? by lazy {
    try { Class.forName("org.jetbrains.kotlin.asJava.LightClassUtilsKt") } catch (_: ClassNotFoundException) { null }
}

// ── Base Java Handler ──

abstract class BaseJavaHandler<T> : LanguageHandler<T> {
    protected fun getRelativePath(project: Project, file: com.intellij.openapi.vfs.VirtualFile): String {
        val basePath = project.basePath ?: return file.path
        return file.path.removePrefix(basePath).removePrefix("/")
    }

    protected fun getLineNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        return document.getLineNumber(element.textOffset) + 1
    }

    protected fun getClassKind(psiClass: PsiClass): String = when {
        psiClass.isInterface -> "INTERFACE"
        psiClass.isEnum -> "ENUM"
        psiClass.isAnnotationType -> "ANNOTATION"
        psiClass.isRecord -> "RECORD"
        psiClass.hasModifierProperty("abstract") -> "ABSTRACT_CLASS"
        else -> "CLASS"
    }

    protected fun resolveMethod(element: PsiElement): PsiMethod? {
        if (element is PsiMethod) return element
        resolveReference(element)?.let { if (it is PsiMethod) return it }
        return if (element.language.id == "kotlin") resolveKotlinMethod(element)
        else PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
    }

    protected fun resolveClass(element: PsiElement): PsiClass? {
        if (element is PsiClass) return element
        resolveReference(element)?.let { if (it is PsiClass) return it }
        return if (element.language.id == "kotlin") resolveKotlinClass(element)
        else PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
    }

    private fun resolveReference(element: PsiElement): PsiElement? {
        element.reference?.resolve()?.let { return it }
        var current: PsiElement? = element
        repeat(3) { current = current?.parent ?: return null; current?.reference?.resolve()?.let { return it } }
        return null
    }

    private fun resolveKotlinMethod(element: PsiElement): PsiMethod? {
        val ktFunc = ktNamedFunctionClass ?: return null
        val ext = lightClassExtensionsClass ?: return null
        var current: PsiElement? = element
        repeat(50) {
            if (current == null) return null
            if (ktFunc.isInstance(current)) {
                return try {
                    val m = ext.getMethod("toLightMethods", PsiElement::class.java)
                    (m.invoke(null, current) as? List<*>)?.firstOrNull() as? PsiMethod
                } catch (_: Exception) { null }
            }
            current = current?.parent
        }
        return null
    }

    private fun resolveKotlinClass(element: PsiElement): PsiClass? {
        val ktCls = ktClassOrObjectClass ?: return null
        val ext = lightClassExtensionsClass ?: return null
        var current: PsiElement? = element
        repeat(50) {
            if (current == null) return null
            if (ktCls.isInstance(current)) {
                return try {
                    val m = ext.getMethod("toLightClass", ktCls)
                    m.invoke(null, current) as? PsiClass
                } catch (_: Exception) { null }
            }
            current = current?.parent
        }
        return null
    }

    protected fun isJavaOrKotlin(element: PsiElement) = element.language.id == "JAVA" || element.language.id == "kotlin"
}

// ── Java Type Hierarchy Handler ──

class JavaTypeHierarchyHandler : BaseJavaHandler<TypeHierarchyData>(), TypeHierarchyHandler {
    override val languageId = "JAVA"
    override fun canHandle(element: PsiElement) = isAvailable() && isJavaOrKotlin(element)
    override fun isAvailable() = isJavaPluginAvailable()

    override fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyData? {
        val psiClass = resolveClass(element) ?: return null
        return TypeHierarchyData(
            element = TypeElementData(psiClass.qualifiedName ?: psiClass.name ?: "unknown", psiClass.qualifiedName, psiClass.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, psiClass), getClassKind(psiClass), if (psiClass.language.id == "kotlin") "Kotlin" else "Java"),
            supertypes = getSupertypes(project, psiClass),
            subtypes = getSubtypes(project, psiClass)
        )
    }

    private fun getSupertypes(project: Project, psiClass: PsiClass, visited: MutableSet<String> = mutableSetOf(), depth: Int = 0): List<TypeElementData> {
        if (depth > 100) return emptyList()
        val cn = psiClass.qualifiedName ?: psiClass.name ?: return emptyList()
        if (cn in visited) return emptyList()
        visited.add(cn)
        val result = mutableListOf<TypeElementData>()
        psiClass.superClass?.takeIf { it.qualifiedName != "java.lang.Object" }?.let { sc ->
            result.add(TypeElementData(sc.qualifiedName ?: sc.name ?: "unknown", sc.qualifiedName, sc.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, sc), getClassKind(sc), if (sc.language.id == "kotlin") "Kotlin" else "Java", getSupertypes(project, sc, visited, depth + 1).takeIf { it.isNotEmpty() }))
        }
        for (iface in psiClass.interfaces) {
            result.add(TypeElementData(iface.qualifiedName ?: iface.name ?: "unknown", iface.qualifiedName, iface.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, iface), "INTERFACE", if (iface.language.id == "kotlin") "Kotlin" else "Java", getSupertypes(project, iface, visited, depth + 1).takeIf { it.isNotEmpty() }))
        }
        return result
    }

    private fun getSubtypes(project: Project, psiClass: PsiClass): List<TypeElementData> {
        val results = mutableListOf<TypeElementData>()
        try {
            ClassInheritorsSearch.search(psiClass, true).forEach(Processor { sub ->
                results.add(TypeElementData(sub.qualifiedName ?: sub.name ?: "unknown", sub.qualifiedName, sub.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, sub), getClassKind(sub), if (sub.language.id == "kotlin") "Kotlin" else "Java"))
                results.size < 100
            })
        } catch (_: Exception) {}
        return results
    }
}

// ── Java Implementations Handler ──

class JavaImplementationsHandler : BaseJavaHandler<List<ImplementationData>>(), ImplementationsHandler {
    override val languageId = "JAVA"
    override fun canHandle(element: PsiElement) = isAvailable() && isJavaOrKotlin(element)
    override fun isAvailable() = isJavaPluginAvailable()

    override fun findImplementations(element: PsiElement, project: Project): List<ImplementationData>? {
        resolveMethod(element)?.let { return findMethodImpls(project, it) }
        resolveClass(element)?.let { return findClassImpls(project, it) }
        return null
    }

    private fun findMethodImpls(project: Project, method: PsiMethod): List<ImplementationData> {
        val results = mutableListOf<ImplementationData>()
        try {
            OverridingMethodsSearch.search(method).forEach(Processor { m ->
                m.containingFile?.virtualFile?.let { f ->
                    results.add(ImplementationData("${m.containingClass?.name}.${m.name}", getRelativePath(project, f), getLineNumber(project, m) ?: 0, "METHOD", if (m.language.id == "kotlin") "Kotlin" else "Java"))
                }
                results.size < 100
            })
        } catch (_: Exception) {}
        return results
    }

    private fun findClassImpls(project: Project, psiClass: PsiClass): List<ImplementationData> {
        val results = mutableListOf<ImplementationData>()
        try {
            ClassInheritorsSearch.search(psiClass, true).forEach(Processor { sub ->
                sub.containingFile?.virtualFile?.let { f ->
                    results.add(ImplementationData(sub.qualifiedName ?: sub.name ?: "unknown", getRelativePath(project, f), getLineNumber(project, sub) ?: 0, getClassKind(sub), if (sub.language.id == "kotlin") "Kotlin" else "Java"))
                }
                results.size < 100
            })
        } catch (_: Exception) {}
        return results
    }
}

// ── Java Call Hierarchy Handler ──

class JavaCallHierarchyHandler : BaseJavaHandler<CallHierarchyData>(), CallHierarchyHandler {
    override val languageId = "JAVA"
    override fun canHandle(element: PsiElement) = isAvailable() && isJavaOrKotlin(element)
    override fun isAvailable() = isJavaPluginAvailable()

    override fun getCallHierarchy(element: PsiElement, project: Project, direction: String, depth: Int): CallHierarchyData? {
        val method = resolveMethod(element) ?: return null
        val visited = mutableSetOf<String>()
        val calls = if (direction == "callers") findCallers(project, method, depth, visited) else findCallees(project, method, depth, visited)
        return CallHierarchyData(createCallElement(project, method), calls)
    }

    private fun findCallers(project: Project, method: PsiMethod, depth: Int, visited: MutableSet<String>, stackDepth: Int = 0): List<CallElementData> {
        if (stackDepth > 50 || depth <= 0) return emptyList()
        val key = getMethodKey(method)
        if (key in visited) return emptyList()
        visited.add(key)
        return try {
            val methods = mutableSetOf(method)
            methods.addAll(method.findDeepestSuperMethods().take(10))
            val refs = mutableListOf<PsiElement>()
            for (m in methods) {
                if (refs.size >= 40) break
                MethodReferencesSearch.search(m, GlobalSearchScope.projectScope(project), true).forEach(Processor { r -> refs.add(r.element); refs.size < 40 })
            }
            refs.take(20).mapNotNull { ref ->
                val cm = PsiTreeUtil.getParentOfType(ref, PsiMethod::class.java)
                if (cm != null && cm != method && cm !in methods) {
                    val children = if (depth > 1) findCallers(project, cm, depth - 1, visited, stackDepth + 1) else null
                    createCallElement(project, cm, children)
                } else null
            }.distinctBy { it.name + it.file + it.line }
        } catch (_: Exception) { emptyList() }
    }

    private fun findCallees(project: Project, method: PsiMethod, depth: Int, visited: MutableSet<String>, stackDepth: Int = 0): List<CallElementData> {
        if (stackDepth > 50 || depth <= 0) return emptyList()
        val key = getMethodKey(method)
        if (key in visited) return emptyList()
        visited.add(key)
        val callees = mutableListOf<CallElementData>()
        try {
            method.body?.let { body ->
                PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java).take(20).forEach { call ->
                    call.resolveMethod()?.let { calledMethod ->
                        val children = if (depth > 1) findCallees(project, calledMethod, depth - 1, visited, stackDepth + 1) else null
                        val el = createCallElement(project, calledMethod, children)
                        if (callees.none { it.name == el.name && it.file == el.file }) callees.add(el)
                    }
                }
            }
        } catch (_: Exception) {}
        return callees
    }

    private fun getMethodKey(m: PsiMethod): String {
        val cn = m.containingClass?.qualifiedName ?: ""
        val p = m.parameterList.parameters.joinToString(",") { try { it.type.canonicalText } catch (_: Exception) { "?" } }
        return "$cn.${m.name}($p)"
    }

    private fun createCallElement(project: Project, method: PsiMethod, children: List<CallElementData>? = null): CallElementData {
        val name = buildString {
            method.containingClass?.name?.let { append(it).append(".") }
            append(method.name)
            append("(")
            append(method.parameterList.parameters.joinToString(", ") { try { it.type.presentableText } catch (_: Exception) { "?" } })
            append(")")
        }
        return CallElementData(name, method.containingFile?.virtualFile?.let { getRelativePath(project, it) } ?: "unknown", getLineNumber(project, method) ?: 0, if (method.language.id == "kotlin") "Kotlin" else "Java", children?.takeIf { it.isNotEmpty() })
    }
}

// ── Java Symbol Search Handler ──

class JavaSymbolSearchHandler : BaseJavaHandler<List<SymbolData>>(), SymbolSearchHandler {
    override val languageId = "JAVA"
    override fun canHandle(element: PsiElement) = isAvailable()
    override fun isAvailable() = isJavaPluginAvailable()

    override fun searchSymbols(project: Project, pattern: String, includeLibraries: Boolean, limit: Int): List<SymbolData> {
        val scope = if (includeLibraries) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
        return OptimizedSymbolSearch.search(project, pattern, scope, limit, setOf("Java", "Kotlin"))
    }
}

// ── Java Super Methods Handler ──

class JavaSuperMethodsHandler : BaseJavaHandler<SuperMethodsData>(), SuperMethodsHandler {
    override val languageId = "JAVA"
    override fun canHandle(element: PsiElement) = isAvailable() && isJavaOrKotlin(element)
    override fun isAvailable() = isJavaPluginAvailable()

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        val method = resolveMethod(element) ?: return null
        val cc = method.containingClass ?: return null
        val f = method.containingFile?.virtualFile
        val methodData = MethodData(method.name, buildSig(method), cc.qualifiedName ?: cc.name ?: "unknown", f?.let { getRelativePath(project, it) } ?: "unknown", getLineNumber(project, method) ?: 0, if (method.language.id == "kotlin") "Kotlin" else "Java")
        return SuperMethodsData(methodData, buildHierarchy(project, method))
    }

    private fun buildHierarchy(project: Project, method: PsiMethod, visited: MutableSet<String> = mutableSetOf(), depth: Int = 1): List<SuperMethodData> {
        val hierarchy = mutableListOf<SuperMethodData>()
        for (sm in method.findSuperMethods()) {
            val key = "${sm.containingClass?.qualifiedName}.${sm.name}"
            if (key in visited) continue
            visited.add(key)
            val cc = sm.containingClass
            hierarchy.add(SuperMethodData(sm.name, buildSig(sm), cc?.qualifiedName ?: cc?.name ?: "unknown", cc?.let { getClassKind(it) } ?: "UNKNOWN", sm.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, sm), cc?.isInterface == true, depth, if (sm.language.id == "kotlin") "Kotlin" else "Java"))
            hierarchy.addAll(buildHierarchy(project, sm, visited, depth + 1))
        }
        return hierarchy
    }

    private fun buildSig(m: PsiMethod): String {
        val p = m.parameterList.parameters.joinToString(", ") { "${it.type.presentableText} ${it.name}" }
        val r = m.returnType?.presentableText ?: "void"
        return "${m.name}($p): $r"
    }
}

// ── Java Structure Handler ──

class JavaStructureHandler : BaseJavaHandler<List<StructureNode>>(), StructureHandler {
    override val languageId = "JAVA"
    override fun canHandle(element: PsiElement) = isAvailable() && isJavaOrKotlin(element)
    override fun isAvailable() = isJavaPluginAvailable()

    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        if (file !is PsiJavaFile) return emptyList()
        return file.classes.map { extractClass(it, project) }
    }

    private fun extractClass(cls: PsiClass, project: Project): StructureNode {
        val children = mutableListOf<StructureNode>()
        for (f in cls.fields) children.add(StructureNode(f.name, StructureKind.FIELD, extractMods(f.modifierList), f.type.presentableText, getLineNumber(project, f) ?: 0))
        for (c in cls.constructors) children.add(extractMethod(c, project))
        for (m in cls.methods) children.add(extractMethod(m, project))
        for (ic in cls.innerClasses) children.add(extractClass(ic, project))
        return StructureNode(cls.name ?: "anonymous", when { cls.isInterface -> StructureKind.INTERFACE; cls.isEnum -> StructureKind.ENUM; cls.isAnnotationType -> StructureKind.ANNOTATION; cls.isRecord -> StructureKind.RECORD; else -> StructureKind.CLASS }, extractMods(cls.modifierList), "", getLineNumber(project, cls) ?: 0, children.sortedBy { it.line })
    }

    private fun extractMethod(m: PsiMethod, project: Project): StructureNode {
        val params = m.parameterList.parameters.joinToString(", ") { "${it.type.presentableText} ${it.name}" }
        val ret = if (m.isConstructor) "" else "${m.returnType?.presentableText ?: "void"} "
        return StructureNode(m.name, if (m.isConstructor) StructureKind.CONSTRUCTOR else StructureKind.METHOD, extractMods(m.modifierList), "$ret($params)", getLineNumber(project, m) ?: 0)
    }

    private fun extractMods(ml: PsiModifierList?): List<String> {
        if (ml == null) return emptyList()
        val mods = mutableListOf<String>()
        for (mod in listOf("public", "private", "protected")) if (ml.hasExplicitModifier(mod)) mods.add(mod)
        for (mod in listOf("static", "final", "abstract", "synchronized")) if (ml.hasExplicitModifier(mod)) mods.add(mod)
        return mods
    }
}

// ── Kotlin handlers (delegate to Java) ──

class KotlinTypeHierarchyHandler : TypeHierarchyHandler by JavaTypeHierarchyHandler() { override val languageId = "kotlin" }
class KotlinImplementationsHandler : ImplementationsHandler by JavaImplementationsHandler() { override val languageId = "kotlin" }
class KotlinCallHierarchyHandler : BaseJavaHandler<CallHierarchyData>(), CallHierarchyHandler {
    override val languageId = "kotlin"
    override fun canHandle(element: PsiElement) = isAvailable() && (element.language.id == "kotlin" || element.language.id == "JAVA")
    override fun isAvailable() = isJavaPluginAvailable() && ktNamedFunctionClass != null

    private val javaHandler = JavaCallHierarchyHandler()

    private val ktCallExpressionClass: Class<*>? by lazy {
        try { Class.forName("org.jetbrains.kotlin.psi.KtCallExpression") } catch (_: ClassNotFoundException) { null }
    }

    override fun getCallHierarchy(element: PsiElement, project: Project, direction: String, depth: Int): CallHierarchyData? {
        if (direction == "callers") {
            // Callers work fine through Java delegation (reference search is language-agnostic)
            return javaHandler.getCallHierarchy(element, project, direction, depth)
        }
        // For callees, search the Kotlin source directly since light PsiMethod bodies are empty
        val ktFunc = findKtNamedFunction(element)
        if (ktFunc == null) {
            // Fall back to Java handler (element might be a Java method)
            return javaHandler.getCallHierarchy(element, project, direction, depth)
        }
        val visited = mutableSetOf<String>()
        val callees = findKotlinCallees(project, ktFunc, depth, visited)
        return CallHierarchyData(createKtCallElement(project, ktFunc), callees)
    }

    private fun findKtNamedFunction(element: PsiElement): PsiElement? {
        val ktFunc = ktNamedFunctionClass ?: return null
        if (ktFunc.isInstance(element)) return element
        // Walk up to find the containing KtNamedFunction
        var current: PsiElement? = element
        repeat(50) {
            if (current == null) return null
            if (ktFunc.isInstance(current!!)) return current
            current = current?.parent
        }
        return null
    }

    private fun findKotlinCallees(project: Project, func: PsiElement, depth: Int, visited: MutableSet<String>, stackDepth: Int = 0): List<CallElementData> {
        if (stackDepth > 50 || depth <= 0) return emptyList()
        val funcName = try { func.javaClass.getMethod("getName").invoke(func) as? String } catch (_: Exception) { null } ?: ""
        val key = "${func.containingFile?.virtualFile?.path}:${getLineNumber(project, func)}:$funcName"
        if (key in visited) return emptyList()
        visited.add(key)
        val callees = mutableListOf<CallElementData>()
        val ktCallExpr = ktCallExpressionClass ?: return emptyList()
        try {
            @Suppress("UNCHECKED_CAST")
            val calls = PsiTreeUtil.findChildrenOfType(func, ktCallExpr as Class<out PsiElement>)
            calls.take(20).forEach { callExpr ->
                val resolved = resolveKtCallExpr(callExpr)
                if (resolved != null) {
                    val children = if (depth > 1) {
                        val innerKtFunc = findKtNamedFunction(resolved)
                        if (innerKtFunc != null) findKotlinCallees(project, innerKtFunc, depth - 1, visited, stackDepth + 1) else null
                    } else null
                    val el = createResolvedCallElement(project, resolved, children)
                    if (callees.none { it.name == el.name && it.file == el.file }) callees.add(el)
                }
            }
        } catch (_: Exception) {}
        return callees
    }

    private fun resolveKtCallExpr(callExpr: PsiElement): PsiElement? {
        return try {
            val calleeExpr = callExpr.javaClass.getMethod("getCalleeExpression").invoke(callExpr) as? PsiElement ?: return null
            calleeExpr.reference?.resolve()
        } catch (_: Exception) { null }
    }

    private fun createKtCallElement(project: Project, func: PsiElement): CallElementData {
        val name = try { func.javaClass.getMethod("getName").invoke(func) as? String } catch (_: Exception) { null } ?: "unknown"
        val ktCls = ktClassOrObjectClass
        val className = if (ktCls != null) {
            try {
                var current: PsiElement? = func.parent
                while (current != null) {
                    if (ktCls.isInstance(current)) break
                    current = current.parent
                }
                current?.let { it.javaClass.getMethod("getName").invoke(it) as? String }
            } catch (_: Exception) { null }
        } else null
        val displayName = if (className != null) "$className.$name" else name
        return CallElementData(displayName, func.containingFile?.virtualFile?.let { getRelativePath(project, it) } ?: "unknown", getLineNumber(project, func) ?: 0, "Kotlin", null)
    }

    private fun createResolvedCallElement(project: Project, resolved: PsiElement, children: List<CallElementData>?): CallElementData {
        val name = try { resolved.javaClass.getMethod("getName").invoke(resolved) as? String } catch (_: Exception) { null } ?: "unknown"
        val lang = if (resolved.language.id == "kotlin") "Kotlin" else resolved.language.displayName
        return CallElementData(name, resolved.containingFile?.virtualFile?.let { getRelativePath(project, it) } ?: "unknown", getLineNumber(project, resolved) ?: 0, lang, children?.takeIf { it.isNotEmpty() })
    }
}
class KotlinSymbolSearchHandler : SymbolSearchHandler by JavaSymbolSearchHandler() { override val languageId = "kotlin" }
class KotlinSuperMethodsHandler : SuperMethodsHandler by JavaSuperMethodsHandler() { override val languageId = "kotlin" }

class KotlinStructureHandler : BaseJavaHandler<List<StructureNode>>(), StructureHandler {
    override val languageId = "kotlin"
    override fun canHandle(element: PsiElement) = isAvailable() && element.language.id == "kotlin"
    override fun isAvailable() = ktFileClass != null

    // Kotlin PSI classes loaded via reflection to avoid compile-time dependency
    private val ktFileClass: Class<*>? by lazy {
        try { Class.forName("org.jetbrains.kotlin.psi.KtFile") } catch (_: ClassNotFoundException) { null }
    }
    private val ktClassOrObject: Class<*>? by lazy {
        try { Class.forName("org.jetbrains.kotlin.psi.KtClassOrObject") } catch (_: ClassNotFoundException) { null }
    }
    private val ktNamedFunction: Class<*>? by lazy {
        try { Class.forName("org.jetbrains.kotlin.psi.KtNamedFunction") } catch (_: ClassNotFoundException) { null }
    }
    private val ktProperty: Class<*>? by lazy {
        try { Class.forName("org.jetbrains.kotlin.psi.KtProperty") } catch (_: ClassNotFoundException) { null }
    }
    private val ktObjectDeclaration: Class<*>? by lazy {
        try { Class.forName("org.jetbrains.kotlin.psi.KtObjectDeclaration") } catch (_: ClassNotFoundException) { null }
    }
    private val ktTypeAlias: Class<*>? by lazy {
        try { Class.forName("org.jetbrains.kotlin.psi.KtTypeAlias") } catch (_: ClassNotFoundException) { null }
    }

    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        val ktFileCls = ktFileClass ?: return emptyList()
        if (!ktFileCls.isInstance(file)) return emptyList()
        val structure = mutableListOf<StructureNode>()
        try {
            val ktClsCls = ktClassOrObject ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val classes = PsiTreeUtil.findChildrenOfType(file, ktClsCls as Class<PsiElement>)
            classes.forEach { cls ->
                if (isTopLevelInFile(cls, file)) structure.add(extractKtClass(cls, project))
            }
            val ktFuncCls = ktNamedFunction
            if (ktFuncCls != null) {
                @Suppress("UNCHECKED_CAST")
                val functions = PsiTreeUtil.findChildrenOfType(file, ktFuncCls as Class<PsiElement>)
                functions.forEach { func ->
                    if (isTopLevelInFile(func, file)) structure.add(extractKtFunction(func, project))
                }
            }
            val ktPropCls = ktProperty
            if (ktPropCls != null) {
                @Suppress("UNCHECKED_CAST")
                val properties = PsiTreeUtil.findChildrenOfType(file, ktPropCls as Class<PsiElement>)
                properties.forEach { prop ->
                    if (isTopLevelInFile(prop, file)) {
                        val name = getKtName(prop) ?: return@forEach
                        structure.add(StructureNode(name, StructureKind.PROPERTY, getKtModifiers(prop), getKtTypeSignature(prop), getLineNumber(project, prop) ?: 0))
                    }
                }
            }
            val ktTAlias = ktTypeAlias
            if (ktTAlias != null) {
                @Suppress("UNCHECKED_CAST")
                val aliases = PsiTreeUtil.findChildrenOfType(file, ktTAlias as Class<PsiElement>)
                aliases.forEach { alias ->
                    if (isTopLevelInFile(alias, file)) {
                        val name = getKtName(alias) ?: return@forEach
                        structure.add(StructureNode(name, StructureKind.TYPE_ALIAS, getKtModifiers(alias), null, getLineNumber(project, alias) ?: 0))
                    }
                }
            }
        } catch (_: Exception) {}
        return structure.sortedBy { it.line }
    }

    private fun isTopLevelInFile(element: PsiElement, file: PsiFile): Boolean {
        val ktClsCls = ktClassOrObject ?: return element.parent == file
        var current: PsiElement? = element.parent
        while (current != null && current != file) {
            if (ktClsCls.isInstance(current)) return false
            current = current.parent
        }
        return true
    }

    private fun extractKtClass(cls: PsiElement, project: Project): StructureNode {
        val children = mutableListOf<StructureNode>()
        val ktFuncCls = ktNamedFunction
        val ktPropCls = ktProperty
        val ktClsCls = ktClassOrObject
        try {
            if (ktFuncCls != null) {
                @Suppress("UNCHECKED_CAST")
                val methods = PsiTreeUtil.findChildrenOfType(cls, ktFuncCls as Class<PsiElement>)
                methods.filter { it.parent == cls || isDirectChildOfBody(it, cls) }.forEach { func ->
                    children.add(extractKtFunction(func, project))
                }
            }
            if (ktPropCls != null) {
                @Suppress("UNCHECKED_CAST")
                val props = PsiTreeUtil.findChildrenOfType(cls, ktPropCls as Class<PsiElement>)
                props.filter { it.parent == cls || isDirectChildOfBody(it, cls) }.forEach { prop ->
                    val name = getKtName(prop) ?: return@forEach
                    children.add(StructureNode(name, StructureKind.PROPERTY, getKtModifiers(prop), getKtTypeSignature(prop), getLineNumber(project, prop) ?: 0))
                }
            }
            if (ktClsCls != null) {
                @Suppress("UNCHECKED_CAST")
                val nested = PsiTreeUtil.findChildrenOfType(cls, ktClsCls as Class<PsiElement>)
                nested.filter { it != cls && (it.parent == cls || isDirectChildOfBody(it, cls)) }.forEach { inner ->
                    children.add(extractKtClass(inner, project))
                }
            }
        } catch (_: Exception) {}
        val name = getKtName(cls) ?: "anonymous"
        val kind = determineKtClassKind(cls)
        return StructureNode(name, kind, getKtModifiers(cls), buildKtClassSignature(cls), getLineNumber(project, cls) ?: 0, children.sortedBy { it.line })
    }

    private fun isDirectChildOfBody(element: PsiElement, cls: PsiElement): Boolean {
        val parent = element.parent ?: return false
        // KtClassBody sits between KtClassOrObject and its members
        if (parent.javaClass.simpleName == "KtClassBody" && parent.parent == cls) return true
        return parent == cls
    }

    private fun extractKtFunction(func: PsiElement, project: Project): StructureNode {
        val name = getKtName(func) ?: "anonymous"
        val sig = buildKtFunctionSignature(func)
        return StructureNode(name, StructureKind.FUNCTION, getKtModifiers(func), sig, getLineNumber(project, func) ?: 0)
    }

    private fun getKtName(element: PsiElement): String? {
        return try { element.javaClass.getMethod("getName").invoke(element) as? String } catch (_: Exception) { null }
    }

    private fun getKtModifiers(element: PsiElement): List<String> {
        val mods = mutableListOf<String>()
        try {
            val modList = element.javaClass.getMethod("getModifierList").invoke(element) ?: return emptyList()
            for (mod in listOf("public", "private", "protected", "internal", "override", "open", "abstract", "final", "suspend", "inline", "companion")) {
                try {
                    val hasModifier = modList.javaClass.getMethod("hasModifier", Class.forName("org.jetbrains.kotlin.lexer.KtModifierKeywordToken"))
                    // Use text-based check as a simpler fallback
                } catch (_: Exception) {}
            }
            // Simpler: use text of modifier list
            val text = (modList as? PsiElement)?.text ?: ""
            for (mod in listOf("public", "private", "protected", "internal", "override", "open", "abstract", "final", "suspend", "inline", "companion", "data", "sealed", "enum", "object")) {
                if (text.contains("\\b$mod\\b".toRegex())) mods.add(mod)
            }
        } catch (_: Exception) {}
        return mods
    }

    private fun determineKtClassKind(cls: PsiElement): StructureKind {
        val simpleName = cls.javaClass.simpleName
        return try {
            val text = (cls as? PsiElement)?.firstChild?.text ?: ""
            when {
                ktObjectDeclaration?.isInstance(cls) == true -> StructureKind.OBJECT
                simpleName.contains("Enum") -> StructureKind.ENUM
                else -> {
                    // Use isInterface() reflection
                    val isInterface = try { cls.javaClass.getMethod("isInterface").invoke(cls) as? Boolean ?: false } catch (_: Exception) { false }
                    val isData = try { cls.javaClass.getMethod("isData").invoke(cls) as? Boolean ?: false } catch (_: Exception) { false }
                    val isSealed = try { cls.javaClass.getMethod("isSealed").invoke(cls) as? Boolean ?: false } catch (_: Exception) { false }
                    val isEnum = try { cls.javaClass.getMethod("isEnum").invoke(cls) as? Boolean ?: false } catch (_: Exception) { false }
                    when {
                        isInterface -> StructureKind.INTERFACE
                        isEnum -> StructureKind.ENUM
                        else -> StructureKind.CLASS
                    }
                }
            }
        } catch (_: Exception) { StructureKind.CLASS }
    }

    private fun buildKtClassSignature(cls: PsiElement): String {
        return try {
            val getSuperTypeList = cls.javaClass.getMethod("getSuperTypeList")
            val superTypeList = getSuperTypeList.invoke(cls) as? PsiElement
            superTypeList?.text ?: ""
        } catch (_: Exception) { "" }
    }

    private fun buildKtFunctionSignature(func: PsiElement): String {
        return try {
            val getValueParameterList = func.javaClass.getMethod("getValueParameterList")
            val paramList = getValueParameterList.invoke(func) as? PsiElement
            val params = paramList?.text ?: "()"
            val getTypeReference = func.javaClass.getMethod("getTypeReference")
            val retType = getTypeReference.invoke(func) as? PsiElement
            val retText = retType?.text
            if (retText != null) "$params: $retText" else params
        } catch (_: Exception) { "()" }
    }

    private fun getKtTypeSignature(prop: PsiElement): String? {
        return try {
            val getTypeReference = prop.javaClass.getMethod("getTypeReference")
            (getTypeReference.invoke(prop) as? PsiElement)?.text
        } catch (_: Exception) { null }
    }
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

// ── Python Handlers ──

abstract class BasePythonHandler<T> : LanguageHandler<T> {
    protected fun isPythonLanguage(element: PsiElement) = element.language.id == "Python"

    protected val pyClassClass: Class<*>? by lazy {
        try { Class.forName("com.jetbrains.python.psi.PyClass") } catch (_: ClassNotFoundException) { null }
    }
    protected val pyFunctionClass: Class<*>? by lazy {
        try { Class.forName("com.jetbrains.python.psi.PyFunction") } catch (_: ClassNotFoundException) { null }
    }
    protected val pyCallExpressionClass: Class<*>? by lazy {
        try { Class.forName("com.jetbrains.python.psi.PyCallExpression") } catch (_: ClassNotFoundException) { null }
    }

    protected fun getRelativePath(project: Project, file: com.intellij.openapi.vfs.VirtualFile): String {
        val basePath = project.basePath ?: return file.path
        return file.path.removePrefix(basePath).removePrefix("/")
    }
    protected fun getLineNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        return document.getLineNumber(element.textOffset) + 1
    }

    protected fun isPyClass(element: PsiElement) = pyClassClass?.isInstance(element) == true
    protected fun isPyFunction(element: PsiElement) = pyFunctionClass?.isInstance(element) == true

    protected fun findContainingPyClass(element: PsiElement): PsiElement? {
        if (isPyClass(element)) return element
        val cls = pyClassClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, cls as Class<out PsiElement>)
    }
    protected fun findContainingPyFunction(element: PsiElement): PsiElement? {
        if (isPyFunction(element)) return element
        val cls = pyFunctionClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, cls as Class<out PsiElement>)
    }

    protected fun getName(element: PsiElement): String? {
        return try { element.javaClass.getMethod("getName").invoke(element) as? String } catch (_: Exception) { null }
    }
    protected fun getQualifiedName(element: PsiElement): String? {
        return try { element.javaClass.getMethod("getQualifiedName").invoke(element) as? String } catch (_: Exception) { null }
    }
    protected fun getSuperClasses(pyClass: PsiElement): Array<*>? {
        return try {
            val method = pyClass.javaClass.getMethod("getSuperClasses", GlobalSearchScope::class.java)
            method.invoke(pyClass, GlobalSearchScope.allScope(pyClass.project)) as? Array<*>
        } catch (_: Exception) { null }
    }
    protected fun findMethodInClass(pyClass: PsiElement, methodName: String): PsiElement? {
        return try {
            val methods = pyClass.javaClass.getMethod("getMethods").invoke(pyClass) as? Array<*> ?: return null
            methods.filterIsInstance<PsiElement>().find { getName(it) == methodName }
        } catch (_: Exception) { null }
    }
    protected fun buildMethodSignature(pyFunction: PsiElement): String {
        return try {
            val paramList = pyFunction.javaClass.getMethod("getParameterList").invoke(pyFunction)
            val params = (paramList.javaClass.getMethod("getParameters").invoke(paramList) as? Array<*>)
                ?.filterIsInstance<PsiElement>()?.mapNotNull { getName(it) }?.joinToString(", ") ?: ""
            "${getName(pyFunction) ?: "unknown"}($params)"
        } catch (_: Exception) { getName(pyFunction) ?: "unknown" }
    }
}

class PythonTypeHierarchyHandler : BasePythonHandler<TypeHierarchyData>(), TypeHierarchyHandler {
    override val languageId = "Python"
    override fun canHandle(element: PsiElement) = isAvailable() && isPythonLanguage(element)
    override fun isAvailable() = isPythonPluginAvailable() && pyClassClass != null

    override fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyData? {
        val pyClass = findContainingPyClass(element) ?: return null
        return TypeHierarchyData(
            element = TypeElementData(getQualifiedName(pyClass) ?: getName(pyClass) ?: "unknown", getQualifiedName(pyClass), pyClass.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, pyClass), "CLASS", "Python"),
            supertypes = getSupertypes(project, pyClass),
            subtypes = getSubtypes(project, pyClass)
        )
    }

    private fun getSupertypes(project: Project, pyClass: PsiElement, visited: MutableSet<String> = mutableSetOf(), depth: Int = 0): List<TypeElementData> {
        if (depth > 50) return emptyList()
        val name = getQualifiedName(pyClass) ?: getName(pyClass) ?: return emptyList()
        if (name in visited || name == "object") return emptyList()
        visited.add(name)
        val result = mutableListOf<TypeElementData>()
        try {
            getSuperClasses(pyClass)?.filterIsInstance<PsiElement>()?.forEach { sc ->
                val scName = getQualifiedName(sc) ?: getName(sc) ?: return@forEach
                if (scName != "object") {
                    result.add(TypeElementData(scName, getQualifiedName(sc), sc.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, sc), "CLASS", "Python", getSupertypes(project, sc, visited, depth + 1).takeIf { it.isNotEmpty() }))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun getSubtypes(project: Project, pyClass: PsiElement): List<TypeElementData> {
        return try {
            val searchClass = Class.forName("com.jetbrains.python.psi.search.PyClassInheritorsSearch")
            val query = searchClass.getMethod("search", pyClassClass, java.lang.Boolean.TYPE).invoke(null, pyClass, true)
            val inheritors = query.javaClass.getMethod("findAll").invoke(query) as? Collection<*> ?: return emptyList()
            inheritors.filterIsInstance<PsiElement>().take(100).map { sub ->
                TypeElementData(getQualifiedName(sub) ?: getName(sub) ?: "unknown", getQualifiedName(sub), sub.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, sub), "CLASS", "Python")
            }
        } catch (_: Exception) { emptyList() }
    }
}

class PythonImplementationsHandler : BasePythonHandler<List<ImplementationData>>(), ImplementationsHandler {
    override val languageId = "Python"
    override fun canHandle(element: PsiElement) = isAvailable() && isPythonLanguage(element)
    override fun isAvailable() = isPythonPluginAvailable() && pyClassClass != null

    override fun findImplementations(element: PsiElement, project: Project): List<ImplementationData>? {
        findContainingPyFunction(element)?.let { return findMethodImpls(project, it) }
        findContainingPyClass(element)?.let { return findClassImpls(project, it) }
        return null
    }

    private fun findMethodImpls(project: Project, pyFunction: PsiElement): List<ImplementationData> {
        return try {
            val searchClass = Class.forName("com.jetbrains.python.psi.search.PyOverridingMethodsSearch")
            val query = searchClass.getMethod("search", pyFunctionClass, java.lang.Boolean.TYPE).invoke(null, pyFunction, true)
            val overrides = query.javaClass.getMethod("findAll").invoke(query) as? Collection<*> ?: return emptyList()
            overrides.filterIsInstance<PsiElement>().take(100).mapNotNull { m ->
                val f = m.containingFile?.virtualFile ?: return@mapNotNull null
                val cls = findContainingPyClass(m)
                val cn = cls?.let { getName(it) } ?: ""
                val mn = getName(m) ?: "unknown"
                ImplementationData(if (cn.isNotEmpty()) "$cn.$mn" else mn, getRelativePath(project, f), getLineNumber(project, m) ?: 0, "METHOD", "Python")
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun findClassImpls(project: Project, pyClass: PsiElement): List<ImplementationData> {
        return try {
            val searchClass = Class.forName("com.jetbrains.python.psi.search.PyClassInheritorsSearch")
            val query = searchClass.getMethod("search", pyClassClass, java.lang.Boolean.TYPE).invoke(null, pyClass, true)
            val inheritors = query.javaClass.getMethod("findAll").invoke(query) as? Collection<*> ?: return emptyList()
            inheritors.filterIsInstance<PsiElement>().take(100).mapNotNull { sub ->
                val f = sub.containingFile?.virtualFile ?: return@mapNotNull null
                ImplementationData(getQualifiedName(sub) ?: getName(sub) ?: "unknown", getRelativePath(project, f), getLineNumber(project, sub) ?: 0, "CLASS", "Python")
            }
        } catch (_: Exception) { emptyList() }
    }
}

class PythonCallHierarchyHandler : BasePythonHandler<CallHierarchyData>(), CallHierarchyHandler {
    override val languageId = "Python"
    override fun canHandle(element: PsiElement) = isAvailable() && isPythonLanguage(element)
    override fun isAvailable() = isPythonPluginAvailable() && pyFunctionClass != null

    override fun getCallHierarchy(element: PsiElement, project: Project, direction: String, depth: Int): CallHierarchyData? {
        val pyFunction = findContainingPyFunction(element) ?: return null
        val visited = mutableSetOf<String>()
        val calls = if (direction == "callers") findCallers(project, pyFunction, depth, visited) else findCallees(project, pyFunction, depth, visited)
        return CallHierarchyData(createCallElement(project, pyFunction), calls)
    }

    private fun getFuncKey(f: PsiElement): String {
        val cls = findContainingPyClass(f)?.let { getQualifiedName(it) ?: getName(it) } ?: ""
        return "$cls.${getName(f) ?: ""}"
    }

    private fun findCallers(project: Project, func: PsiElement, depth: Int, visited: MutableSet<String>, stackDepth: Int = 0): List<CallElementData> {
        if (stackDepth > 50 || depth <= 0) return emptyList()
        val key = getFuncKey(func)
        if (key in visited) return emptyList()
        visited.add(key)
        return try {
            val scope = GlobalSearchScope.projectScope(project)
            val refs = mutableListOf<PsiReference>()
            ReferencesSearch.search(func, scope).forEach(Processor { refs.add(it); refs.size < 40 })
            refs.take(20).mapNotNull { ref ->
                val containing = findContainingPyFunction(ref.element)
                if (containing != null && containing != func) {
                    createCallElement(project, containing, if (depth > 1) findCallers(project, containing, depth - 1, visited, stackDepth + 1) else null)
                } else null
            }.distinctBy { it.name + it.file + it.line }
        } catch (_: Exception) { emptyList() }
    }

    private fun findCallees(project: Project, func: PsiElement, depth: Int, visited: MutableSet<String>, stackDepth: Int = 0): List<CallElementData> {
        if (stackDepth > 50 || depth <= 0) return emptyList()
        val key = getFuncKey(func)
        if (key in visited) return emptyList()
        visited.add(key)
        val callees = mutableListOf<CallElementData>()
        try {
            val pyCallExpr = pyCallExpressionClass ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val calls = PsiTreeUtil.findChildrenOfType(func, pyCallExpr as Class<out PsiElement>)
            calls.take(20).forEach { callExpr ->
                val resolved = resolveCallExpr(callExpr)
                if (resolved != null && isPyFunction(resolved)) {
                    val el = createCallElement(project, resolved, if (depth > 1) findCallees(project, resolved, depth - 1, visited, stackDepth + 1) else null)
                    if (callees.none { it.name == el.name && it.file == el.file }) callees.add(el)
                }
            }
        } catch (_: Exception) {}
        return callees
    }

    private fun resolveCallExpr(callExpr: PsiElement): PsiElement? {
        return try {
            val callee = callExpr.javaClass.getMethod("getCallee").invoke(callExpr) as? PsiElement ?: return null
            (callee.javaClass.getMethod("getReference").invoke(callee) as? PsiReference)?.resolve()
        } catch (_: Exception) { null }
    }

    private fun createCallElement(project: Project, func: PsiElement, children: List<CallElementData>? = null): CallElementData {
        val cls = findContainingPyClass(func)?.let { getName(it) }
        val name = if (cls != null) "$cls.${getName(func) ?: "unknown"}" else getName(func) ?: "unknown"
        return CallElementData(name, func.containingFile?.virtualFile?.let { getRelativePath(project, it) } ?: "unknown", getLineNumber(project, func) ?: 0, "Python", children?.takeIf { it.isNotEmpty() })
    }
}

class PythonSymbolSearchHandler : BasePythonHandler<List<SymbolData>>(), SymbolSearchHandler {
    override val languageId = "Python"
    override fun canHandle(element: PsiElement) = isAvailable()
    override fun isAvailable() = isPythonPluginAvailable() && pyClassClass != null
    override fun searchSymbols(project: Project, pattern: String, includeLibraries: Boolean, limit: Int): List<SymbolData> {
        val scope = if (includeLibraries) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
        return OptimizedSymbolSearch.search(project, pattern, scope, limit, setOf("Python"))
    }
}

class PythonSuperMethodsHandler : BasePythonHandler<SuperMethodsData>(), SuperMethodsHandler {
    override val languageId = "Python"
    override fun canHandle(element: PsiElement) = isAvailable() && isPythonLanguage(element)
    override fun isAvailable() = isPythonPluginAvailable() && pyFunctionClass != null

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        val func = findContainingPyFunction(element) ?: return null
        val cls = findContainingPyClass(func) ?: return null
        val f = func.containingFile?.virtualFile
        val methodData = MethodData(getName(func) ?: "unknown", buildMethodSignature(func), getQualifiedName(cls) ?: getName(cls) ?: "unknown", f?.let { getRelativePath(project, it) } ?: "unknown", getLineNumber(project, func) ?: 0, "Python")
        return SuperMethodsData(methodData, buildHierarchy(project, func))
    }

    private fun buildHierarchy(project: Project, func: PsiElement, visited: MutableSet<String> = mutableSetOf(), depth: Int = 1): List<SuperMethodData> {
        val result = mutableListOf<SuperMethodData>()
        try {
            val cls = findContainingPyClass(func) ?: return emptyList()
            val methodName = getName(func) ?: return emptyList()
            getSuperClasses(cls)?.filterIsInstance<PsiElement>()?.forEach { sc ->
                val scName = getQualifiedName(sc) ?: getName(sc)
                val key = "$scName.$methodName"
                if (key in visited) return@forEach
                visited.add(key)
                findMethodInClass(sc, methodName)?.let { sm ->
                    result.add(SuperMethodData(methodName, buildMethodSignature(sm), scName ?: "unknown", "CLASS", sm.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, sm), false, depth, "Python"))
                    result.addAll(buildHierarchy(project, sm, visited, depth + 1))
                }
            }
        } catch (_: Exception) {}
        return result
    }
}

class PythonStructureHandler : BasePythonHandler<List<StructureNode>>(), StructureHandler {
    override val languageId = "Python"
    override fun canHandle(element: PsiElement) = isAvailable() && isPythonLanguage(element)
    override fun isAvailable() = isPythonPluginAvailable() && pyClassClass != null

    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        val pyFileCls = try { Class.forName("com.jetbrains.python.psi.PyFile") } catch (_: ClassNotFoundException) { return emptyList() }
        if (!pyFileCls.isInstance(file)) return emptyList()
        val structure = mutableListOf<StructureNode>()
        try {
            val pyClsCls = pyClassClass ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            PsiTreeUtil.findChildrenOfType(file, pyClsCls as Class<PsiElement>).forEach { cls ->
                if (isTopLevel(cls, file)) structure.add(extractClass(cls, project))
            }
            val pyFuncCls = pyFunctionClass
            if (pyFuncCls != null) {
                @Suppress("UNCHECKED_CAST")
                PsiTreeUtil.findChildrenOfType(file, pyFuncCls as Class<PsiElement>).forEach { func ->
                    if (isTopLevel(func, file)) structure.add(extractFunction(func, project))
                }
            }
        } catch (_: Exception) {}
        return structure.sortedBy { it.line }
    }

    private fun isTopLevel(element: PsiElement, file: PsiFile): Boolean {
        var current: PsiElement? = element.parent
        while (current != null && current != file) {
            if (isPyClass(current)) return false
            current = current.parent
        }
        return true
    }

    private fun extractClass(cls: PsiElement, project: Project): StructureNode {
        val children = mutableListOf<StructureNode>()
        try {
            val methods = cls.javaClass.getMethod("getMethods").invoke(cls) as? Array<*> ?: emptyArray<Any?>()
            methods.filterIsInstance<PsiElement>().forEach { children.add(extractFunction(it, project)) }
            val inner = cls.javaClass.getMethod("getInnerClasses").invoke(cls) as? List<*> ?: emptyList<Any?>()
            inner.filterIsInstance<PsiElement>().forEach { children.add(extractClass(it, project)) }
        } catch (_: Exception) {}
        return StructureNode(getName(cls) ?: "unknown", StructureKind.CLASS, getPyModifiers(cls), buildClassSig(cls), getLineNumber(project, cls) ?: 0, children.sortedBy { it.line })
    }

    private fun extractFunction(func: PsiElement, project: Project): StructureNode {
        return StructureNode(getName(func) ?: "unknown", StructureKind.FUNCTION, getPyModifiers(func), buildFuncSig(func), getLineNumber(project, func) ?: 0)
    }

    private fun getPyModifiers(element: PsiElement): List<String> {
        val mods = mutableListOf<String>()
        try {
            val hasDecorator = element.javaClass.getMethod("hasDecorator", String::class.java)
            for (d in listOf("property", "staticmethod", "classmethod")) {
                if (hasDecorator.invoke(element, d) as? Boolean == true) mods.add("@$d")
            }
        } catch (_: Exception) {}
        return mods
    }

    private fun buildClassSig(cls: PsiElement): String {
        return try {
            val supers = cls.javaClass.getMethod("getSuperClasses", GlobalSearchScope::class.java).invoke(cls, GlobalSearchScope.allScope(cls.project)) as? Array<*> ?: return ""
            val names = supers.filterIsInstance<PsiElement>().mapNotNull { getQualifiedName(it) ?: getName(it) }
            if (names.isNotEmpty()) "(${names.joinToString(", ")})" else ""
        } catch (_: Exception) { "" }
    }

    private fun buildFuncSig(func: PsiElement): String {
        return try {
            val paramList = func.javaClass.getMethod("getParameterList").invoke(func)
            val params = (paramList.javaClass.getMethod("getParameters").invoke(paramList) as? Array<*>)
                ?.filterIsInstance<PsiElement>()?.mapNotNull { getName(it) }?.joinToString(", ") ?: ""
            "($params)"
        } catch (_: Exception) { "()" }
    }
}

// ── JavaScript/TypeScript Handlers ──

abstract class BaseJavaScriptHandler<T> : LanguageHandler<T> {
    protected fun isJavaScriptLanguage(element: PsiElement): Boolean {
        val id = element.language.id
        return id == "JavaScript" || id == "TypeScript" || id == "ECMAScript 6" || id == "JSX Harmony" || id == "TypeScript JSX"
    }
    protected fun getLanguageName(element: PsiElement) = when (element.language.id) {
        "TypeScript", "TypeScript JSX" -> "TypeScript"
        else -> "JavaScript"
    }

    protected val jsClassClass: Class<*>? by lazy {
        try { Class.forName("com.intellij.lang.javascript.psi.ecmal4.JSClass") } catch (_: ClassNotFoundException) {
            try { Class.forName("com.intellij.lang.javascript.psi.JSClass") } catch (_: ClassNotFoundException) { null }
        }
    }
    protected val jsFunctionClass: Class<*>? by lazy {
        try { Class.forName("com.intellij.lang.javascript.psi.JSFunction") } catch (_: ClassNotFoundException) { null }
    }
    protected val jsCallExpressionClass: Class<*>? by lazy {
        try { Class.forName("com.intellij.lang.javascript.psi.JSCallExpression") } catch (_: ClassNotFoundException) { null }
    }
    protected val jsVariableClass: Class<*>? by lazy {
        try { Class.forName("com.intellij.lang.javascript.psi.JSVariable") } catch (_: ClassNotFoundException) { null }
    }

    protected fun getRelativePath(project: Project, file: com.intellij.openapi.vfs.VirtualFile): String {
        val basePath = project.basePath ?: return file.path
        return file.path.removePrefix(basePath).removePrefix("/")
    }
    protected fun getLineNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        return document.getLineNumber(element.textOffset) + 1
    }

    protected fun isJSClass(e: PsiElement) = jsClassClass?.isInstance(e) == true
    protected fun isJSFunction(e: PsiElement) = jsFunctionClass?.isInstance(e) == true
    protected fun isJSVariable(e: PsiElement) = jsVariableClass?.isInstance(e) == true

    protected fun findContainingJSClass(element: PsiElement): PsiElement? {
        if (isJSClass(element)) return element
        val cls = jsClassClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, cls as Class<out PsiElement>)
    }
    protected fun findContainingJSFunction(element: PsiElement): PsiElement? {
        if (isJSFunction(element)) return element
        val cls = jsFunctionClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, cls as Class<out PsiElement>)
    }

    protected fun getName(element: PsiElement): String? {
        return try { element.javaClass.getMethod("getName").invoke(element) as? String } catch (_: Exception) { null }
    }
    protected fun getQualifiedName(element: PsiElement): String? {
        return try { element.javaClass.getMethod("getQualifiedName").invoke(element) as? String } catch (_: Exception) { null }
    }
    protected fun getClassKind(jsClass: PsiElement): String {
        return try { if (jsClass.javaClass.getMethod("isInterface").invoke(jsClass) as? Boolean == true) "INTERFACE" else "CLASS" } catch (_: Exception) { "CLASS" }
    }
    protected fun getSuperClasses(jsClass: PsiElement): Array<*>? {
        return try { jsClass.javaClass.getMethod("getSuperClasses").invoke(jsClass) as? Array<*> } catch (_: Exception) { null }
    }
    protected fun getImplementedInterfaces(jsClass: PsiElement): Array<*>? {
        return try { jsClass.javaClass.getMethod("getImplementedInterfaces").invoke(jsClass) as? Array<*> } catch (_: Exception) { null }
    }
    protected fun findMethodInClass(jsClass: PsiElement, methodName: String): PsiElement? {
        return try {
            jsClass.javaClass.getMethod("findFunctionByName", String::class.java).invoke(jsClass, methodName) as? PsiElement
        } catch (_: Exception) {
            try {
                val fns = jsClass.javaClass.getMethod("getFunctions").invoke(jsClass) as? Array<*> ?: return null
                fns.filterIsInstance<PsiElement>().find { getName(it) == methodName }
            } catch (_: Exception) { null }
        }
    }
    protected fun buildMethodSignature(jsFunction: PsiElement): String {
        return try {
            val paramList = jsFunction.javaClass.getMethod("getParameterList").invoke(jsFunction)
            val params = (paramList.javaClass.getMethod("getParameters").invoke(paramList) as? Array<*>)
                ?.filterIsInstance<PsiElement>()?.mapNotNull { p ->
                    try {
                        val n = p.javaClass.getMethod("getName").invoke(p) as? String
                        val t = try { p.javaClass.getMethod("getType").invoke(p)?.toString() } catch (_: Exception) { null }
                        if (t != null) "$n: $t" else n
                    } catch (_: Exception) { null }
                }?.joinToString(", ") ?: ""
            val ret = try { jsFunction.javaClass.getMethod("getReturnType").invoke(jsFunction)?.toString() } catch (_: Exception) { null }
            val fn = getName(jsFunction) ?: "unknown"
            if (ret != null) "$fn($params): $ret" else "$fn($params)"
        } catch (_: Exception) { getName(jsFunction) ?: "unknown" }
    }
}

class JavaScriptTypeHierarchyHandler : BaseJavaScriptHandler<TypeHierarchyData>(), TypeHierarchyHandler {
    override val languageId = "JavaScript"
    override fun canHandle(element: PsiElement) = isAvailable() && isJavaScriptLanguage(element)
    override fun isAvailable() = isJavaScriptPluginAvailable() && jsFunctionClass != null

    override fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyData? {
        val jsClass = findContainingJSClass(element) ?: return null
        return TypeHierarchyData(
            element = TypeElementData(getQualifiedName(jsClass) ?: getName(jsClass) ?: "unknown", getQualifiedName(jsClass), jsClass.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, jsClass), getClassKind(jsClass), getLanguageName(jsClass)),
            supertypes = getSupertypes(project, jsClass),
            subtypes = getSubtypes(project, jsClass)
        )
    }

    private fun getSupertypes(project: Project, jsClass: PsiElement, visited: MutableSet<String> = mutableSetOf(), depth: Int = 0): List<TypeElementData> {
        if (depth > 50) return emptyList()
        val name = getQualifiedName(jsClass) ?: getName(jsClass) ?: return emptyList()
        if (name in visited) return emptyList()
        visited.add(name)
        val result = mutableListOf<TypeElementData>()
        try {
            getSuperClasses(jsClass)?.filterIsInstance<PsiElement>()?.forEach { sc ->
                val scName = getQualifiedName(sc) ?: getName(sc) ?: return@forEach
                result.add(TypeElementData(scName, getQualifiedName(sc), sc.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, sc), getClassKind(sc), getLanguageName(sc), getSupertypes(project, sc, visited, depth + 1).takeIf { it.isNotEmpty() }))
            }
            getImplementedInterfaces(jsClass)?.filterIsInstance<PsiElement>()?.forEach { iface ->
                val ifaceName = getQualifiedName(iface) ?: getName(iface) ?: return@forEach
                if (ifaceName !in visited) {
                    result.add(TypeElementData(ifaceName, getQualifiedName(iface), iface.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, iface), "INTERFACE", getLanguageName(iface), getSupertypes(project, iface, visited, depth + 1).takeIf { it.isNotEmpty() }))
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun getSubtypes(project: Project, jsClass: PsiElement): List<TypeElementData> {
        // Strategy 1: try JSInheritorsSearch
        try {
            val searchCls = Class.forName("com.intellij.lang.javascript.psi.resolve.JSInheritorsSearch")
            val query = searchCls.getMethod("search", jsClassClass).invoke(null, jsClass)
            val results = mutableListOf<TypeElementData>()
            val forEachMethod = query.javaClass.getMethod("forEach", Processor::class.java)
            forEachMethod.invoke(query, Processor<Any> { inh ->
                if (inh is PsiElement) results.add(TypeElementData(getQualifiedName(inh) ?: getName(inh) ?: "unknown", getQualifiedName(inh), inh.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, inh), getClassKind(inh), getLanguageName(inh)))
                results.size < 100
            })
            if (results.isNotEmpty()) return results
        } catch (_: Exception) {}
        // Strategy 2: DefinitionsScopedSearch
        return try {
            val results = mutableListOf<TypeElementData>()
            DefinitionsScopedSearch.search(jsClass, GlobalSearchScope.projectScope(project)).forEach(Processor { def ->
                if (def != jsClass && isJSClass(def)) results.add(TypeElementData(getQualifiedName(def) ?: getName(def) ?: "unknown", getQualifiedName(def), def.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, def), getClassKind(def), getLanguageName(def)))
                results.size < 100
            })
            results
        } catch (_: Exception) { emptyList() }
    }
}

class JavaScriptImplementationsHandler : BaseJavaScriptHandler<List<ImplementationData>>(), ImplementationsHandler {
    override val languageId = "JavaScript"
    override fun canHandle(element: PsiElement) = isAvailable() && isJavaScriptLanguage(element)
    override fun isAvailable() = isJavaScriptPluginAvailable() && jsFunctionClass != null

    override fun findImplementations(element: PsiElement, project: Project): List<ImplementationData>? {
        val jsFunction = findContainingJSFunction(element)
        if (jsFunction != null && findContainingJSClass(jsFunction) != null) return findMethodImpls(project, jsFunction)
        findContainingJSClass(element)?.let { return findClassImpls(project, it) }
        return null
    }

    private fun findMethodImpls(project: Project, jsFunction: PsiElement): List<ImplementationData> {
        // Strategy 1: JSFunctionOverridingSearch
        try {
            val searchCls = Class.forName("com.intellij.lang.javascript.psi.resolve.JSFunctionOverridingSearch")
            val query = searchCls.getMethod("search", jsFunctionClass).invoke(null, jsFunction)
            val results = mutableListOf<ImplementationData>()
            val forEachMethod = query.javaClass.getMethod("forEach", Processor::class.java)
            forEachMethod.invoke(query, Processor<Any> { m ->
                if (m is PsiElement) {
                    val f = m.containingFile?.virtualFile
                    if (f != null) {
                        val cls = findContainingJSClass(m)?.let { getName(it) } ?: ""
                        val mn = getName(m) ?: "unknown"
                        results.add(ImplementationData(if (cls.isNotEmpty()) "$cls.$mn" else mn, getRelativePath(project, f), getLineNumber(project, m) ?: 0, "METHOD", getLanguageName(m)))
                    }
                }
                results.size < 100
            })
            if (results.isNotEmpty()) return results
        } catch (_: Exception) {}
        // Strategy 2: DefinitionsScopedSearch
        return searchDefinitions(project, jsFunction)
    }

    private fun findClassImpls(project: Project, jsClass: PsiElement): List<ImplementationData> {
        // Strategy 1: JSInheritorsSearch
        try {
            val searchCls = Class.forName("com.intellij.lang.javascript.psi.resolve.JSInheritorsSearch")
            val query = searchCls.getMethod("search", jsClassClass).invoke(null, jsClass)
            val results = mutableListOf<ImplementationData>()
            val forEachMethod = query.javaClass.getMethod("forEach", Processor::class.java)
            forEachMethod.invoke(query, Processor<Any> { inh ->
                if (inh is PsiElement) {
                    val f = inh.containingFile?.virtualFile
                    if (f != null) results.add(ImplementationData(getQualifiedName(inh) ?: getName(inh) ?: "unknown", getRelativePath(project, f), getLineNumber(project, inh) ?: 0, getClassKind(inh), getLanguageName(inh)))
                }
                results.size < 100
            })
            if (results.isNotEmpty()) return results
        } catch (_: Exception) {}
        return searchDefinitions(project, jsClass)
    }

    private fun searchDefinitions(project: Project, element: PsiElement): List<ImplementationData> {
        val results = mutableListOf<ImplementationData>()
        try {
            DefinitionsScopedSearch.search(element, GlobalSearchScope.projectScope(project)).forEach(Processor { def ->
                if (def != element) {
                    val f = def.containingFile?.virtualFile
                    if (f != null) {
                        val kind = when { isJSClass(def) -> getClassKind(def); isJSFunction(def) -> "METHOD"; else -> "UNKNOWN" }
                        results.add(ImplementationData(getQualifiedName(def) ?: getName(def) ?: "unknown", getRelativePath(project, f), getLineNumber(project, def) ?: 0, kind, getLanguageName(def)))
                    }
                }
                results.size < 100
            })
        } catch (_: Exception) {}
        return results
    }
}

class JavaScriptCallHierarchyHandler : BaseJavaScriptHandler<CallHierarchyData>(), CallHierarchyHandler {
    override val languageId = "JavaScript"
    override fun canHandle(element: PsiElement) = isAvailable() && isJavaScriptLanguage(element)
    override fun isAvailable() = isJavaScriptPluginAvailable() && jsFunctionClass != null

    override fun getCallHierarchy(element: PsiElement, project: Project, direction: String, depth: Int): CallHierarchyData? {
        val func = findContainingJSFunction(element) ?: return null
        val visited = mutableSetOf<String>()
        val calls = if (direction == "callers") findCallers(project, func, depth, visited) else findCallees(project, func, depth, visited)
        return CallHierarchyData(createCallElement(project, func), calls)
    }

    private fun getFuncKey(f: PsiElement): String {
        val file = f.containingFile?.virtualFile?.path ?: ""
        val cls = findContainingJSClass(f)?.let { getQualifiedName(it) ?: getName(it) } ?: ""
        return "$file:$cls.${getName(f) ?: ""}"
    }

    private fun findSuperMethods(func: PsiElement): Set<PsiElement> {
        val result = mutableSetOf<PsiElement>()
        val visited = mutableSetOf<String>()
        fun recurse(f: PsiElement) {
            val cls = findContainingJSClass(f) ?: return
            val methodName = getName(f) ?: return
            val supers = getSuperClasses(cls)?.filterIsInstance<PsiElement>() ?: emptyList()
            val ifaces = getImplementedInterfaces(cls)?.filterIsInstance<PsiElement>() ?: emptyList()
            (supers + ifaces).forEach { sc ->
                val key = "${getQualifiedName(sc) ?: getName(sc)}.$methodName"
                if (key in visited) return@forEach
                visited.add(key)
                findMethodInClass(sc, methodName)?.let { sm -> result.add(sm); recurse(sm) }
            }
        }
        recurse(func)
        return result.take(10).toSet()
    }

    private fun findCallers(project: Project, func: PsiElement, depth: Int, visited: MutableSet<String>, stackDepth: Int = 0): List<CallElementData> {
        if (stackDepth > 50 || depth <= 0) return emptyList()
        val key = getFuncKey(func)
        if (key in visited) return emptyList()
        visited.add(key)
        return try {
            val scope = GlobalSearchScope.projectScope(project)
            val toSearch = mutableSetOf(func)
            toSearch.addAll(findSuperMethods(func))
            val refs = mutableListOf<PsiReference>()
            for (m in toSearch) {
                ReferencesSearch.search(m, scope).forEach(Processor { refs.add(it); refs.size < 40 })
            }
            refs.take(20).mapNotNull { ref ->
                val containing = findContainingJSFunction(ref.element)
                if (containing != null && containing != func && !toSearch.contains(containing)) {
                    createCallElement(project, containing, if (depth > 1) findCallers(project, containing, depth - 1, visited, stackDepth + 1) else null)
                } else null
            }.distinctBy { it.name + it.file + it.line }
        } catch (_: Exception) { emptyList() }
    }

    private fun findCallees(project: Project, func: PsiElement, depth: Int, visited: MutableSet<String>, stackDepth: Int = 0): List<CallElementData> {
        if (stackDepth > 50 || depth <= 0) return emptyList()
        val key = getFuncKey(func)
        if (key in visited) return emptyList()
        visited.add(key)
        val callees = mutableListOf<CallElementData>()
        try {
            val jsCallExpr = jsCallExpressionClass ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val calls = PsiTreeUtil.findChildrenOfType(func, jsCallExpr as Class<out PsiElement>)
            calls.take(20).forEach { callExpr ->
                val resolved = resolveCallExpr(callExpr)
                if (resolved != null && isJSFunction(resolved)) {
                    val el = createCallElement(project, resolved, if (depth > 1) findCallees(project, resolved, depth - 1, visited, stackDepth + 1) else null)
                    if (callees.none { it.name == el.name && it.file == el.file }) callees.add(el)
                }
            }
        } catch (_: Exception) {}
        return callees
    }

    private fun resolveCallExpr(callExpr: PsiElement): PsiElement? {
        return try {
            val methodExpr = callExpr.javaClass.getMethod("getMethodExpression").invoke(callExpr) as? PsiElement ?: return null
            (methodExpr.javaClass.getMethod("getReference").invoke(methodExpr) as? PsiReference)?.resolve()
        } catch (_: Exception) { null }
    }

    private fun createCallElement(project: Project, func: PsiElement, children: List<CallElementData>? = null): CallElementData {
        val cls = findContainingJSClass(func)?.let { getName(it) }
        val name = if (cls != null) "$cls.${getName(func) ?: "unknown"}" else getName(func) ?: "unknown"
        return CallElementData(name, func.containingFile?.virtualFile?.let { getRelativePath(project, it) } ?: "unknown", getLineNumber(project, func) ?: 0, getLanguageName(func), children?.takeIf { it.isNotEmpty() })
    }
}

class JavaScriptSymbolSearchHandler : BaseJavaScriptHandler<List<SymbolData>>(), SymbolSearchHandler {
    override val languageId = "JavaScript"
    override fun canHandle(element: PsiElement) = isAvailable()
    override fun isAvailable() = isJavaScriptPluginAvailable() && jsFunctionClass != null
    override fun searchSymbols(project: Project, pattern: String, includeLibraries: Boolean, limit: Int): List<SymbolData> {
        val scope = if (includeLibraries) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
        return OptimizedSymbolSearch.search(project, pattern, scope, limit, setOf("JavaScript", "TypeScript"))
    }
}

class JavaScriptSuperMethodsHandler : BaseJavaScriptHandler<SuperMethodsData>(), SuperMethodsHandler {
    override val languageId = "JavaScript"
    override fun canHandle(element: PsiElement) = isAvailable() && isJavaScriptLanguage(element)
    override fun isAvailable() = isJavaScriptPluginAvailable() && jsFunctionClass != null

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        val func = findContainingJSFunction(element) ?: return null
        val cls = findContainingJSClass(func) ?: return null
        val f = func.containingFile?.virtualFile
        val methodData = MethodData(getName(func) ?: "unknown", buildMethodSignature(func), getQualifiedName(cls) ?: getName(cls) ?: "unknown", f?.let { getRelativePath(project, it) } ?: "unknown", getLineNumber(project, func) ?: 0, getLanguageName(func))
        return SuperMethodsData(methodData, buildHierarchy(project, func))
    }

    private fun buildHierarchy(project: Project, func: PsiElement, visited: MutableSet<String> = mutableSetOf(), depth: Int = 1): List<SuperMethodData> {
        val result = mutableListOf<SuperMethodData>()
        try {
            val cls = findContainingJSClass(func) ?: return emptyList()
            val methodName = getName(func) ?: return emptyList()
            val supers = getSuperClasses(cls)?.filterIsInstance<PsiElement>() ?: emptyList()
            val ifaces = getImplementedInterfaces(cls)?.filterIsInstance<PsiElement>() ?: emptyList()
            (supers + ifaces).forEach { sc ->
                val scName = getQualifiedName(sc) ?: getName(sc)
                val key = "$scName.$methodName"
                if (key in visited) return@forEach
                visited.add(key)
                findMethodInClass(sc, methodName)?.let { sm ->
                    val isIface = getClassKind(sc) == "INTERFACE"
                    result.add(SuperMethodData(methodName, buildMethodSignature(sm), scName ?: "unknown", getClassKind(sc), sm.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, sm), isIface, depth, getLanguageName(sm)))
                    result.addAll(buildHierarchy(project, sm, visited, depth + 1))
                }
            }
        } catch (_: Exception) {}
        return result
    }
}

// TypeScript delegates to JavaScript handlers
class TypeScriptTypeHierarchyHandler : TypeHierarchyHandler by JavaScriptTypeHierarchyHandler() { override val languageId = "TypeScript" }
class TypeScriptImplementationsHandler : ImplementationsHandler by JavaScriptImplementationsHandler() { override val languageId = "TypeScript" }
class TypeScriptCallHierarchyHandler : CallHierarchyHandler by JavaScriptCallHierarchyHandler() { override val languageId = "TypeScript" }
class TypeScriptSymbolSearchHandler : SymbolSearchHandler by JavaScriptSymbolSearchHandler() { override val languageId = "TypeScript" }
class TypeScriptSuperMethodsHandler : SuperMethodsHandler by JavaScriptSuperMethodsHandler() { override val languageId = "TypeScript" }

// ── Go Handlers ──

abstract class BaseGoHandler<T> : LanguageHandler<T> {
    protected fun isGoLanguage(element: PsiElement) = element.language.id == "go"

    protected val goFileClass: Class<*>? by lazy {
        try { Class.forName("com.goide.psi.GoFile") } catch (_: ClassNotFoundException) { null }
    }
    protected val goTypeSpecClass: Class<*>? by lazy {
        try { Class.forName("com.goide.psi.GoTypeSpec") } catch (_: ClassNotFoundException) { null }
    }
    protected val goFunctionDeclarationClass: Class<*>? by lazy {
        try { Class.forName("com.goide.psi.GoFunctionDeclaration") } catch (_: ClassNotFoundException) { null }
    }
    protected val goMethodDeclarationClass: Class<*>? by lazy {
        try { Class.forName("com.goide.psi.GoMethodDeclaration") } catch (_: ClassNotFoundException) { null }
    }
    protected val goStructTypeClass: Class<*>? by lazy {
        try { Class.forName("com.goide.psi.GoStructType") } catch (_: ClassNotFoundException) { null }
    }
    protected val goInterfaceTypeClass: Class<*>? by lazy {
        try { Class.forName("com.goide.psi.GoInterfaceType") } catch (_: ClassNotFoundException) { null }
    }
    protected val goCallExprClass: Class<*>? by lazy {
        try { Class.forName("com.goide.psi.GoCallExpr") } catch (_: ClassNotFoundException) { null }
    }

    protected fun getRelativePath(project: Project, file: com.intellij.openapi.vfs.VirtualFile): String {
        val basePath = project.basePath ?: return file.path
        return file.path.removePrefix(basePath).removePrefix("/")
    }
    protected fun getLineNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        return document.getLineNumber(element.textOffset) + 1
    }

    protected fun isGoTypeSpec(e: PsiElement) = goTypeSpecClass?.isInstance(e) == true
    protected fun isGoFunction(e: PsiElement) = goFunctionDeclarationClass?.isInstance(e) == true
    protected fun isGoMethod(e: PsiElement) = goMethodDeclarationClass?.isInstance(e) == true
    protected fun isGoStructType(e: PsiElement) = goStructTypeClass?.isInstance(e) == true
    protected fun isGoInterfaceType(e: PsiElement) = goInterfaceTypeClass?.isInstance(e) == true

    protected fun findContainingGoType(element: PsiElement): PsiElement? {
        if (isGoTypeSpec(element)) return element
        val cls = goTypeSpecClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, cls as Class<out PsiElement>)
    }
    protected fun findContainingGoFunction(element: PsiElement): PsiElement? {
        if (isGoFunction(element) || isGoMethod(element)) return element
        val goMethod = goMethodDeclarationClass
        if (goMethod != null) {
            @Suppress("UNCHECKED_CAST")
            PsiTreeUtil.getParentOfType(element, goMethod as Class<out PsiElement>)?.let { return it }
        }
        val goFunc = goFunctionDeclarationClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, goFunc as Class<out PsiElement>)
    }

    protected fun getName(element: PsiElement): String? {
        return try { element.javaClass.getMethod("getName").invoke(element) as? String } catch (_: Exception) { null }
    }
    protected fun getQualifiedName(element: PsiElement): String? {
        return try { element.javaClass.getMethod("getQualifiedName").invoke(element) as? String } catch (_: Exception) { getName(element) }
    }
    protected fun getSpecType(goTypeSpec: PsiElement): PsiElement? {
        return try { goTypeSpec.javaClass.getMethod("getSpecType").invoke(goTypeSpec) as? PsiElement } catch (_: Exception) { null }
    }
    protected fun determineTypeKind(element: PsiElement): String {
        val spec = getSpecType(element)
        return when { spec != null && isGoStructType(spec) -> "STRUCT"; spec != null && isGoInterfaceType(spec) -> "INTERFACE"; else -> "TYPE" }
    }
    protected fun resolveRef(element: PsiElement): PsiElement? {
        return try { (element.javaClass.getMethod("getReference").invoke(element) as? PsiReference)?.resolve() } catch (_: Exception) { null }
    }
}

class GoTypeHierarchyHandler : BaseGoHandler<TypeHierarchyData>(), TypeHierarchyHandler {
    override val languageId = "go"
    override fun canHandle(element: PsiElement) = isAvailable() && isGoLanguage(element)
    override fun isAvailable() = isGoPluginAvailable() && goTypeSpecClass != null

    override fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyData? {
        val goType = findContainingGoType(element) ?: return null
        val specType = getSpecType(goType)
        return TypeHierarchyData(
            element = TypeElementData(getQualifiedName(goType) ?: getName(goType) ?: "unknown", getQualifiedName(goType), goType.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, goType), determineTypeKind(goType), "Go"),
            supertypes = getSupertypes(project, goType, specType),
            subtypes = getSubtypes(project, goType)
        )
    }

    private fun getSupertypes(project: Project, goType: PsiElement, specType: PsiElement?, visited: MutableSet<String> = mutableSetOf(), depth: Int = 0): List<TypeElementData> {
        if (depth > 50) return emptyList()
        val name = getQualifiedName(goType) ?: getName(goType) ?: return emptyList()
        if (name in visited) return emptyList()
        visited.add(name)
        val result = mutableListOf<TypeElementData>()
        try {
            when {
                specType != null && isGoStructType(specType) -> result.addAll(getEmbeddedTypes(project, specType, visited, depth))
                specType != null && isGoInterfaceType(specType) -> result.addAll(getEmbeddedInterfaces(project, specType, visited, depth))
            }
        } catch (_: Exception) {}
        return result
    }

    private fun getEmbeddedTypes(project: Project, structType: PsiElement, visited: MutableSet<String>, depth: Int): List<TypeElementData> {
        val result = mutableListOf<TypeElementData>()
        try {
            val fields = structType.javaClass.getMethod("getFieldDeclarationList").invoke(structType) as? List<*> ?: return emptyList()
            fields.filterIsInstance<PsiElement>().forEach { field ->
                try {
                    val anonField = field.javaClass.getMethod("getAnonymousFieldDefinition").invoke(field) as? PsiElement ?: return@forEach
                    val embName = getName(anonField) ?: return@forEach
                    if (embName !in visited) {
                        resolveRef(anonField)?.takeIf { isGoTypeSpec(it) }?.let { resolved ->
                            val nested = getSupertypes(project, resolved, getSpecType(resolved), visited, depth + 1)
                            result.add(TypeElementData(getQualifiedName(resolved) ?: embName, getQualifiedName(resolved), resolved.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, resolved), determineTypeKind(resolved), "Go", nested.takeIf { it.isNotEmpty() }))
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return result
    }

    private fun getEmbeddedInterfaces(project: Project, interfaceType: PsiElement, visited: MutableSet<String>, depth: Int): List<TypeElementData> {
        val result = mutableListOf<TypeElementData>()
        try {
            val typeRefs = interfaceType.javaClass.getMethod("getTypeReferenceExpressionList").invoke(interfaceType) as? List<*> ?: return emptyList()
            typeRefs.filterIsInstance<PsiElement>().forEach { typeRef ->
                val embName = getName(typeRef) ?: typeRef.text ?: return@forEach
                if (embName !in visited) {
                    resolveRef(typeRef)?.takeIf { isGoTypeSpec(it) }?.let { resolved ->
                        val nested = getSupertypes(project, resolved, getSpecType(resolved), visited, depth + 1)
                        result.add(TypeElementData(getQualifiedName(resolved) ?: embName, getQualifiedName(resolved), resolved.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, resolved), "INTERFACE", "Go", nested.takeIf { it.isNotEmpty() }))
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun getSubtypes(project: Project, goType: PsiElement): List<TypeElementData> {
        val results = mutableListOf<TypeElementData>()
        try {
            DefinitionsScopedSearch.search(goType, GlobalSearchScope.projectScope(project)).forEach(Processor { def ->
                if (def != goType && isGoTypeSpec(def)) results.add(TypeElementData(getQualifiedName(def) ?: getName(def) ?: "unknown", getQualifiedName(def), def.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, def), determineTypeKind(def), "Go"))
                results.size < 100
            })
        } catch (_: Exception) {}
        return results
    }
}

class GoCallHierarchyHandler : BaseGoHandler<CallHierarchyData>(), CallHierarchyHandler {
    override val languageId = "go"
    override fun canHandle(element: PsiElement) = isAvailable() && isGoLanguage(element)
    override fun isAvailable() = isGoPluginAvailable() && goFunctionDeclarationClass != null

    override fun getCallHierarchy(element: PsiElement, project: Project, direction: String, depth: Int): CallHierarchyData? {
        val func = findContainingGoFunction(element) ?: return null
        val visited = mutableSetOf<String>()
        val calls = if (direction == "callers") findCallers(project, func, depth, visited) else findCallees(project, func, depth, visited)
        return CallHierarchyData(createCallElement(project, func), calls)
    }

    private fun getFuncKey(f: PsiElement): String {
        val file = f.containingFile?.virtualFile?.path ?: ""
        return "$file:${getLineNumber(f.project, f) ?: 0}:${getName(f) ?: ""}"
    }

    private fun findCallers(project: Project, func: PsiElement, depth: Int, visited: MutableSet<String>, stackDepth: Int = 0): List<CallElementData> {
        if (stackDepth > 50 || depth <= 0) return emptyList()
        val key = getFuncKey(func)
        if (key in visited) return emptyList()
        visited.add(key)
        return try {
            val refs = mutableListOf<PsiReference>()
            ReferencesSearch.search(func, GlobalSearchScope.projectScope(project)).forEach(Processor { refs.add(it); refs.size < 40 })
            refs.take(20).mapNotNull { ref ->
                val containing = findContainingGoFunction(ref.element)
                if (containing != null && containing != func) createCallElement(project, containing, if (depth > 1) findCallers(project, containing, depth - 1, visited, stackDepth + 1) else null)
                else null
            }.distinctBy { it.name + it.file + it.line }
        } catch (_: Exception) { emptyList() }
    }

    private fun findCallees(project: Project, func: PsiElement, depth: Int, visited: MutableSet<String>, stackDepth: Int = 0): List<CallElementData> {
        if (stackDepth > 50 || depth <= 0) return emptyList()
        val key = getFuncKey(func)
        if (key in visited) return emptyList()
        visited.add(key)
        val callees = mutableListOf<CallElementData>()
        try {
            val goCallExpr = goCallExprClass ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val calls = PsiTreeUtil.findChildrenOfType(func, goCallExpr as Class<out PsiElement>)
            calls.take(20).forEach { callExpr ->
                val resolved = resolveGoCallExpr(callExpr)
                if (resolved != null && (isGoFunction(resolved) || isGoMethod(resolved))) {
                    val el = createCallElement(project, resolved, if (depth > 1) findCallees(project, resolved, depth - 1, visited, stackDepth + 1) else null)
                    if (callees.none { it.name == el.name && it.file == el.file }) callees.add(el)
                }
            }
        } catch (_: Exception) {}
        return callees
    }

    private fun resolveGoCallExpr(callExpr: PsiElement): PsiElement? {
        return try {
            val expr = callExpr.javaClass.getMethod("getExpression").invoke(callExpr) as? PsiElement ?: return null
            (expr.javaClass.getMethod("getReference").invoke(expr) as? PsiReference)?.resolve()
        } catch (_: Exception) { null }
    }

    private fun createCallElement(project: Project, func: PsiElement, children: List<CallElementData>? = null): CallElementData {
        val name = if (isGoMethod(func)) {
            try {
                val receiver = func.javaClass.getMethod("getReceiver").invoke(func) as? PsiElement
                val typeName = receiver?.let {
                    try { (it.javaClass.getMethod("getType").invoke(it) as? PsiElement)?.text?.trim('*', ' ') } catch (_: Exception) { null }
                }
                if (typeName != null) "$typeName.${getName(func) ?: "unknown"}" else getName(func) ?: "unknown"
            } catch (_: Exception) { getName(func) ?: "unknown" }
        } else getName(func) ?: "unknown"
        return CallElementData(name, func.containingFile?.virtualFile?.let { getRelativePath(project, it) } ?: "unknown", getLineNumber(project, func) ?: 0, "Go", children?.takeIf { it.isNotEmpty() })
    }
}

class GoSymbolSearchHandler : BaseGoHandler<List<SymbolData>>(), SymbolSearchHandler {
    override val languageId = "go"
    override fun canHandle(element: PsiElement) = isAvailable()
    override fun isAvailable() = isGoPluginAvailable() && goFileClass != null
    override fun searchSymbols(project: Project, pattern: String, includeLibraries: Boolean, limit: Int): List<SymbolData> {
        val scope = if (includeLibraries) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
        return OptimizedSymbolSearch.search(project, pattern, scope, limit, setOf("go", "Go"))
    }
}

// ── Rust Handlers ──

abstract class BaseRustHandler<T> : LanguageHandler<T> {
    protected fun isRustLanguage(element: PsiElement) = element.language.id == "Rust"

    private fun loadClass(name: String): Class<*>? = try { Class.forName(name) } catch (_: ClassNotFoundException) { null }

    protected val rsFileClass: Class<*>? by lazy { loadClass("org.rust.lang.core.psi.RsFile") }
    protected val rsStructItemClass: Class<*>? by lazy { loadClass("org.rust.lang.core.psi.RsStructItem") }
    protected val rsTraitItemClass: Class<*>? by lazy { loadClass("org.rust.lang.core.psi.RsTraitItem") }
    protected val rsImplItemClass: Class<*>? by lazy { loadClass("org.rust.lang.core.psi.RsImplItem") }
    protected val rsEnumItemClass: Class<*>? by lazy { loadClass("org.rust.lang.core.psi.RsEnumItem") }
    protected val rsFunctionClass: Class<*>? by lazy { loadClass("org.rust.lang.core.psi.RsFunction") }
    protected val rsModItemClass: Class<*>? by lazy { loadClass("org.rust.lang.core.psi.RsModItem") }
    protected val rsCallExprClass: Class<*>? by lazy { loadClass("org.rust.lang.core.psi.RsCallExpr") }
    protected val rsMethodCallClass: Class<*>? by lazy { loadClass("org.rust.lang.core.psi.RsMethodCall") }

    protected fun getRelativePath(project: Project, file: com.intellij.openapi.vfs.VirtualFile): String {
        val basePath = project.basePath ?: return file.path
        return file.path.removePrefix(basePath).removePrefix("/")
    }
    protected fun getLineNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        return document.getLineNumber(element.textOffset) + 1
    }

    protected fun isRsTrait(e: PsiElement) = rsTraitItemClass?.isInstance(e) == true
    protected fun isRsStruct(e: PsiElement) = rsStructItemClass?.isInstance(e) == true
    protected fun isRsEnum(e: PsiElement) = rsEnumItemClass?.isInstance(e) == true
    protected fun isRsImpl(e: PsiElement) = rsImplItemClass?.isInstance(e) == true
    protected fun isRsFunction(e: PsiElement) = rsFunctionClass?.isInstance(e) == true
    protected fun isRsMod(e: PsiElement) = rsModItemClass?.isInstance(e) == true

    protected fun findContainingRsTrait(e: PsiElement): PsiElement? {
        if (isRsTrait(e)) return e
        val cls = rsTraitItemClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(e, cls as Class<out PsiElement>)
    }
    protected fun findContainingRsImpl(e: PsiElement): PsiElement? {
        if (isRsImpl(e)) return e
        val cls = rsImplItemClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(e, cls as Class<out PsiElement>)
    }
    protected fun findContainingRsFunction(e: PsiElement): PsiElement? {
        if (isRsFunction(e)) return e
        val cls = rsFunctionClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(e, cls as Class<out PsiElement>)
    }
    protected fun findContainingRsStruct(e: PsiElement): PsiElement? {
        if (isRsStruct(e)) return e
        val cls = rsStructItemClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(e, cls as Class<out PsiElement>)
    }
    protected fun findContainingRsEnum(e: PsiElement): PsiElement? {
        if (isRsEnum(e)) return e
        val cls = rsEnumItemClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(e, cls as Class<out PsiElement>)
    }

    protected fun getName(element: PsiElement): String? {
        return try { element.javaClass.getMethod("getName").invoke(element) as? String } catch (_: Exception) { null }
    }
    protected fun getQualifiedName(element: PsiElement): String? {
        return try {
            for (m in listOf("getQualifiedName", "getName")) {
                try { (element.javaClass.getMethod(m).invoke(element) as? String)?.let { return it } } catch (_: NoSuchMethodException) {}
            }
            null
        } catch (_: Exception) { getName(element) }
    }
    protected fun getTraitRef(implItem: PsiElement): PsiElement? {
        return try { implItem.javaClass.getMethod("getTraitRef").invoke(implItem) as? PsiElement } catch (_: Exception) { null }
    }
    protected fun getTypeReference(implItem: PsiElement): PsiElement? {
        return try { implItem.javaClass.getMethod("getTypeReference").invoke(implItem) as? PsiElement } catch (_: Exception) { null }
    }
    protected fun getSuperTraits(traitItem: PsiElement): List<PsiElement>? {
        return try {
            for (m in listOf("getSuperTraits", "getTypeParamBounds")) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    (traitItem.javaClass.getMethod(m).invoke(traitItem) as? List<PsiElement>)?.let { return it }
                } catch (_: NoSuchMethodException) {}
            }
            null
        } catch (_: Exception) { null }
    }
    protected fun resolveReference(element: PsiElement): PsiElement? {
        return try {
            (element.javaClass.getMethod("getReference").invoke(element) as? PsiReference)?.resolve()
                ?: try { element.javaClass.getMethod("resolve").invoke(element) as? PsiElement } catch (_: Exception) { null }
        } catch (_: Exception) { null }
    }
    protected fun getFunctions(container: PsiElement): List<PsiElement>? {
        for (m in listOf("getFunctions", "getMembers", "getExpandedMembers")) {
            try {
                @Suppress("UNCHECKED_CAST")
                val list = container.javaClass.getMethod(m).invoke(container) as? List<*>
                if (list != null) return list.filterIsInstance<PsiElement>().filter { isRsFunction(it) }
            } catch (_: NoSuchMethodException) {}
        }
        return null
    }
    protected fun determineElementKind(e: PsiElement) = when {
        isRsTrait(e) -> "TRAIT"; isRsStruct(e) -> "STRUCT"; isRsEnum(e) -> "ENUM"
        isRsImpl(e) -> "IMPL"; isRsFunction(e) -> "FUNCTION"; isRsMod(e) -> "MODULE"; else -> "SYMBOL"
    }
    protected fun buildMethodSignature(function: PsiElement): String {
        for (m in listOf("getValueParameterList", "getParameterList")) {
            try {
                val paramList = function.javaClass.getMethod(m).invoke(function) as? PsiElement
                if (paramList != null) return "fn ${getName(function) ?: "unknown"}${paramList.text}"
            } catch (_: NoSuchMethodException) {}
        }
        return "fn ${getName(function) ?: "unknown"}()"
    }
}

class RustTypeHierarchyHandler : BaseRustHandler<TypeHierarchyData>(), TypeHierarchyHandler {
    override val languageId = "Rust"
    override fun canHandle(element: PsiElement) = isAvailable() && isRustLanguage(element)
    override fun isAvailable() = isRustPluginAvailable() && rsTraitItemClass != null

    override fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyData? {
        findContainingRsTrait(element)?.let { return getTraitHierarchy(project, it) }
        findContainingRsStruct(element)?.let { return getTypeImplHierarchy(project, it) }
        findContainingRsEnum(element)?.let { return getTypeImplHierarchy(project, it) }
        findContainingRsImpl(element)?.let { return getImplHierarchy(project, it) }
        return null
    }

    private fun getTraitHierarchy(project: Project, trait: PsiElement): TypeHierarchyData {
        return TypeHierarchyData(
            TypeElementData(getName(trait) ?: "unknown", getQualifiedName(trait), trait.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, trait), "TRAIT", "Rust"),
            getSupertraitHierarchy(project, trait, mutableSetOf()),
            getImplementingTypes(project, trait)
        )
    }

    private fun getSupertraitHierarchy(project: Project, trait: PsiElement, visited: MutableSet<String>, depth: Int = 0): List<TypeElementData> {
        if (depth > 50) return emptyList()
        val name = getName(trait) ?: return emptyList()
        if (name in visited) return emptyList()
        visited.add(name)
        val result = mutableListOf<TypeElementData>()
        try {
            getSuperTraits(trait)?.forEach { superRef ->
                val resolved = resolveReference(superRef)
                if (resolved != null && isRsTrait(resolved)) {
                    val rn = getName(resolved) ?: return@forEach
                    if (rn !in visited) {
                        result.add(TypeElementData(rn, getQualifiedName(resolved), resolved.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, resolved), "TRAIT", "Rust", getSupertraitHierarchy(project, resolved, visited, depth + 1).takeIf { it.isNotEmpty() }))
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun getImplementingTypes(project: Project, trait: PsiElement): List<TypeElementData> {
        val results = mutableListOf<TypeElementData>()
        try {
            DefinitionsScopedSearch.search(trait, GlobalSearchScope.projectScope(project)).forEach(Processor { def ->
                if (isRsImpl(def)) {
                    val typeRef = getTypeReference(def)
                    val resolved = typeRef?.let { resolveReference(it) }
                    val typeName = resolved?.let { getName(it) } ?: typeRef?.text?.trim()
                    if (typeName != null) {
                        val target = resolved ?: def
                        results.add(TypeElementData(typeName, resolved?.let { getQualifiedName(it) }, target.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, target), if (resolved != null) determineElementKind(resolved) else "IMPL", "Rust"))
                    }
                }
                results.size < 100
            })
        } catch (_: Exception) {}
        return results
    }

    private fun getTypeImplHierarchy(project: Project, type: PsiElement): TypeHierarchyData {
        val traits = mutableListOf<TypeElementData>()
        try {
            ReferencesSearch.search(type, GlobalSearchScope.projectScope(project)).forEach(Processor { ref ->
                val impl = findContainingRsImpl(ref.element)
                if (impl != null) {
                    val traitRef = getTraitRef(impl)
                    if (traitRef != null) {
                        val resolved = resolveReference(traitRef)
                        val traitName = resolved?.let { getName(it) } ?: traitRef.text?.trim()
                        if (traitName != null && traits.none { it.name == traitName }) {
                            val target = resolved ?: impl
                            traits.add(TypeElementData(traitName, resolved?.let { getQualifiedName(it) }, target.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, target), "TRAIT", "Rust"))
                        }
                    }
                }
                traits.size < 100
            })
        } catch (_: Exception) {}
        return TypeHierarchyData(TypeElementData(getName(type) ?: "unknown", getQualifiedName(type), type.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, type), determineElementKind(type), "Rust"), traits, emptyList())
    }

    private fun getImplHierarchy(project: Project, impl: PsiElement): TypeHierarchyData {
        val traitRef = getTraitRef(impl)
        val typeRef = getTypeReference(impl)
        val supertypes = mutableListOf<TypeElementData>()
        val subtypes = mutableListOf<TypeElementData>()
        traitRef?.let { tr ->
            val res = resolveReference(tr)
            val tn = res?.let { getName(it) } ?: tr.text?.trim()
            if (tn != null) {
                val target = res ?: impl
                supertypes.add(TypeElementData(tn, res?.let { getQualifiedName(it) }, target.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, target), "TRAIT", "Rust"))
            }
        }
        typeRef?.let { tr ->
            val res = resolveReference(tr)
            val tn = res?.let { getName(it) } ?: tr.text?.trim()
            if (tn != null) {
                val target = res ?: impl
                subtypes.add(TypeElementData(tn, res?.let { getQualifiedName(it) }, target.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, target), if (res != null) determineElementKind(res) else "TYPE", "Rust"))
            }
        }
        val implName = when {
            traitRef != null && typeRef != null -> "impl ${traitRef.text?.trim()} for ${typeRef.text?.trim()}"
            typeRef != null -> "impl ${typeRef.text?.trim()}"
            else -> "impl"
        }
        return TypeHierarchyData(TypeElementData(implName, null, impl.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, impl), "IMPL", "Rust"), supertypes, subtypes)
    }
}

class RustImplementationsHandler : BaseRustHandler<List<ImplementationData>>(), ImplementationsHandler {
    override val languageId = "Rust"
    override fun canHandle(element: PsiElement) = isAvailable() && isRustLanguage(element)
    override fun isAvailable() = isRustPluginAvailable() && rsTraitItemClass != null

    override fun findImplementations(element: PsiElement, project: Project): List<ImplementationData>? {
        val trait = findContainingRsTrait(element)
        if (trait != null) {
            val func = findContainingRsFunction(element)
            if (func != null && findContainingRsTrait(func) != null) return findMethodImpls(project, func, trait)
            return findTraitImpls(project, trait)
        }
        val func = findContainingRsFunction(element)
        if (func != null) {
            val impl = findContainingRsImpl(func)
            val traitRef = impl?.let { getTraitRef(it) }
            val resolvedTrait = traitRef?.let { resolveReference(it) }
            if (resolvedTrait != null && isRsTrait(resolvedTrait)) return findMethodImpls(project, func, resolvedTrait)
        }
        return null
    }

    private fun findTraitImpls(project: Project, trait: PsiElement): List<ImplementationData> {
        val results = mutableListOf<ImplementationData>()
        val traitName = getName(trait) ?: "unknown"
        try {
            DefinitionsScopedSearch.search(trait, GlobalSearchScope.projectScope(project)).forEach(Processor { def ->
                if (isRsImpl(def)) {
                    val f = def.containingFile?.virtualFile
                    val typeName = getTypeReference(def)?.text?.trim() ?: "unknown"
                    if (f != null) results.add(ImplementationData("impl $traitName for $typeName", getRelativePath(project, f), getLineNumber(project, def) ?: 0, "IMPL", "Rust"))
                }
                results.size < 100
            })
        } catch (_: Exception) {}
        return results
    }

    private fun findMethodImpls(project: Project, method: PsiElement, trait: PsiElement): List<ImplementationData> {
        val results = mutableListOf<ImplementationData>()
        val methodName = getName(method) ?: return emptyList()
        try {
            DefinitionsScopedSearch.search(method, GlobalSearchScope.projectScope(project)).forEach(Processor { def ->
                if (isRsFunction(def) && def != method) {
                    val f = def.containingFile?.virtualFile
                    if (f != null) {
                        val impl = findContainingRsImpl(def)
                        val typeName = impl?.let { getTypeReference(it)?.text?.trim() } ?: ""
                        val displayName = if (typeName.isNotEmpty()) "$typeName::$methodName" else methodName
                        results.add(ImplementationData(displayName, getRelativePath(project, f), getLineNumber(project, def) ?: 0, "METHOD", "Rust"))
                    }
                }
                results.size < 100
            })
        } catch (_: Exception) {}
        return results
    }
}

class RustCallHierarchyHandler : BaseRustHandler<CallHierarchyData>(), CallHierarchyHandler {
    override val languageId = "Rust"
    override fun canHandle(element: PsiElement) = isAvailable() && isRustLanguage(element)
    override fun isAvailable() = isRustPluginAvailable() && rsFunctionClass != null

    override fun getCallHierarchy(element: PsiElement, project: Project, direction: String, depth: Int): CallHierarchyData? {
        val func = findContainingRsFunction(element) ?: return null
        val visited = mutableSetOf<String>()
        val calls = if (direction == "callers") findCallers(project, func, depth, visited) else findCallees(project, func, depth, visited)
        return CallHierarchyData(createCallElement(project, func), calls)
    }

    private fun getFuncKey(f: PsiElement) = "${f.containingFile?.virtualFile?.path ?: ""}:${getLineNumber(f.project, f) ?: 0}:${getName(f) ?: ""}"

    private fun findCallers(project: Project, func: PsiElement, depth: Int, visited: MutableSet<String>, stackDepth: Int = 0): List<CallElementData> {
        if (stackDepth > 50 || depth <= 0) return emptyList()
        val key = getFuncKey(func)
        if (key in visited) return emptyList()
        visited.add(key)
        return try {
            val refs = mutableListOf<PsiReference>()
            ReferencesSearch.search(func, GlobalSearchScope.projectScope(project)).forEach(Processor { refs.add(it); refs.size < 40 })
            refs.take(20).mapNotNull { ref ->
                val containing = findContainingRsFunction(ref.element)
                if (containing != null && containing != func) createCallElement(project, containing, if (depth > 1) findCallers(project, containing, depth - 1, visited, stackDepth + 1) else null)
                else null
            }.distinctBy { it.name + it.file + it.line }
        } catch (_: Exception) { emptyList() }
    }

    private fun findCallees(project: Project, func: PsiElement, depth: Int, visited: MutableSet<String>, stackDepth: Int = 0): List<CallElementData> {
        if (stackDepth > 50 || depth <= 0) return emptyList()
        val key = getFuncKey(func)
        if (key in visited) return emptyList()
        visited.add(key)
        val callees = mutableListOf<CallElementData>()
        try {
            listOfNotNull(rsCallExprClass, rsMethodCallClass).forEach { callCls ->
                @Suppress("UNCHECKED_CAST")
                val calls = PsiTreeUtil.findChildrenOfType(func, callCls as Class<out PsiElement>)
                calls.take(20).forEach { callExpr ->
                    val resolved = resolveRustCallExpr(callExpr)
                    if (resolved != null && isRsFunction(resolved)) {
                        val el = createCallElement(project, resolved, if (depth > 1) findCallees(project, resolved, depth - 1, visited, stackDepth + 1) else null)
                        if (callees.none { it.name == el.name && it.file == el.file }) callees.add(el)
                    }
                }
            }
        } catch (_: Exception) {}
        return callees
    }

    private fun resolveRustCallExpr(callExpr: PsiElement): PsiElement? {
        // Try getExpr -> getPath -> resolve
        try {
            val expr = callExpr.javaClass.getMethod("getExpr").invoke(callExpr) as? PsiElement
            if (expr != null) {
                try {
                    val path = expr.javaClass.getMethod("getPath").invoke(expr) as? PsiElement
                    if (path != null) resolveReference(path)?.takeIf { isRsFunction(it) }?.let { return it }
                } catch (_: NoSuchMethodException) {
                    resolveReference(expr)?.takeIf { isRsFunction(it) }?.let { return it }
                }
            }
        } catch (_: NoSuchMethodException) {}
        // Try getIdentifier -> resolve
        try {
            val id = callExpr.javaClass.getMethod("getIdentifier").invoke(callExpr) as? PsiElement
            if (id != null) resolveReference(id)?.takeIf { isRsFunction(it) }?.let { return it }
        } catch (_: NoSuchMethodException) {}
        // Try direct references
        try {
            for (ref in callExpr.references) {
                ref.resolve()?.takeIf { isRsFunction(it) }?.let { return it }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun createCallElement(project: Project, func: PsiElement, children: List<CallElementData>? = null): CallElementData {
        val impl = findContainingRsImpl(func)
        val typeName = impl?.let { getTypeReference(it)?.text?.trim()?.substringBefore('<') }
        val name = if (typeName != null) "$typeName::${getName(func) ?: "unknown"}" else getName(func) ?: "unknown"
        return CallElementData(name, func.containingFile?.virtualFile?.let { getRelativePath(project, it) } ?: "unknown", getLineNumber(project, func) ?: 0, "Rust", children?.takeIf { it.isNotEmpty() })
    }
}

class RustSymbolSearchHandler : BaseRustHandler<List<SymbolData>>(), SymbolSearchHandler {
    override val languageId = "Rust"
    override fun canHandle(element: PsiElement) = isAvailable()
    override fun isAvailable() = isRustPluginAvailable() && rsFileClass != null
    override fun searchSymbols(project: Project, pattern: String, includeLibraries: Boolean, limit: Int): List<SymbolData> {
        val scope = if (includeLibraries) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
        return OptimizedSymbolSearch.search(project, pattern, scope, limit, setOf("Rust"))
    }
}

// ── PHP Handlers ──

abstract class BasePhpHandler<T> : LanguageHandler<T> {
    protected fun isPhpLanguage(element: PsiElement) = element.language.id == "PHP"

    protected val phpClassClass: Class<*>? by lazy {
        try { Class.forName("com.jetbrains.php.lang.psi.elements.PhpClass") } catch (_: ClassNotFoundException) { null }
    }
    protected val methodClass: Class<*>? by lazy {
        try { Class.forName("com.jetbrains.php.lang.psi.elements.Method") } catch (_: ClassNotFoundException) { null }
    }
    protected val functionClass: Class<*>? by lazy {
        try { Class.forName("com.jetbrains.php.lang.psi.elements.Function") } catch (_: ClassNotFoundException) { null }
    }
    protected val fieldClass: Class<*>? by lazy {
        try { Class.forName("com.jetbrains.php.lang.psi.elements.Field") } catch (_: ClassNotFoundException) { null }
    }
    protected val methodReferenceClass: Class<*>? by lazy {
        try { Class.forName("com.jetbrains.php.lang.psi.elements.MethodReference") } catch (_: ClassNotFoundException) { null }
    }
    protected val functionReferenceClass: Class<*>? by lazy {
        try { Class.forName("com.jetbrains.php.lang.psi.elements.FunctionReference") } catch (_: ClassNotFoundException) { null }
    }
    protected val phpIndexClass: Class<*>? by lazy {
        try { Class.forName("com.jetbrains.php.PhpIndex") } catch (_: ClassNotFoundException) { null }
    }

    protected fun getRelativePath(project: Project, file: com.intellij.openapi.vfs.VirtualFile): String {
        val basePath = project.basePath ?: return file.path
        return file.path.removePrefix(basePath).removePrefix("/")
    }
    protected fun getLineNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        return document.getLineNumber(element.textOffset) + 1
    }

    protected fun isPhpClass(e: PsiElement) = phpClassClass?.isInstance(e) == true
    protected fun isMethod(e: PsiElement) = methodClass?.isInstance(e) == true
    protected fun isFunction(e: PsiElement) = functionClass?.isInstance(e) == true
    protected fun isField(e: PsiElement) = fieldClass?.isInstance(e) == true

    protected fun findContainingPhpClass(element: PsiElement): PsiElement? {
        if (isPhpClass(element)) return element
        val cls = phpClassClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, cls as Class<out PsiElement>)
    }
    protected fun findContainingMethod(element: PsiElement): PsiElement? {
        if (isMethod(element)) return element
        val cls = methodClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, cls as Class<out PsiElement>)
    }
    protected fun findContainingFunction(element: PsiElement): PsiElement? {
        if (isFunction(element)) return element
        val cls = functionClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, cls as Class<out PsiElement>)
    }
    protected fun findContainingCallable(element: PsiElement) = findContainingMethod(element) ?: findContainingFunction(element)

    protected fun getName(element: PsiElement): String? {
        return try { element.javaClass.getMethod("getName").invoke(element) as? String } catch (_: Exception) { null }
    }
    protected fun getFQN(element: PsiElement): String? {
        return try { element.javaClass.getMethod("getFQN").invoke(element) as? String } catch (_: Exception) { getName(element) }
    }
    protected fun getSuperClass(phpClass: PsiElement): PsiElement? {
        return try { phpClass.javaClass.getMethod("getSuperClass").invoke(phpClass) as? PsiElement } catch (_: Exception) { null }
    }
    protected fun getImplementedInterfaces(phpClass: PsiElement): Array<*>? {
        return try { phpClass.javaClass.getMethod("getImplementedInterfaces").invoke(phpClass) as? Array<*> } catch (_: Exception) { null }
    }
    protected fun getTraits(phpClass: PsiElement): Array<*>? {
        return try { phpClass.javaClass.getMethod("getTraits").invoke(phpClass) as? Array<*> } catch (_: Exception) { null }
    }
    protected fun isInterface(phpClass: PsiElement) = try { phpClass.javaClass.getMethod("isInterface").invoke(phpClass) as? Boolean ?: false } catch (_: Exception) { false }
    protected fun isTrait(phpClass: PsiElement) = try { phpClass.javaClass.getMethod("isTrait").invoke(phpClass) as? Boolean ?: false } catch (_: Exception) { false }
    protected fun isAbstract(phpClass: PsiElement) = try { phpClass.javaClass.getMethod("isAbstract").invoke(phpClass) as? Boolean ?: false } catch (_: Exception) { false }
    protected fun getContainingClass(method: PsiElement): PsiElement? {
        return try { method.javaClass.getMethod("getContainingClass").invoke(method) as? PsiElement } catch (_: Exception) { null }
    }
    protected fun determineClassKind(e: PsiElement) = when { isInterface(e) -> "INTERFACE"; isTrait(e) -> "TRAIT"; isAbstract(e) -> "ABSTRACT_CLASS"; else -> "CLASS" }
    protected fun findMethodInClass(phpClass: PsiElement, methodName: String): PsiElement? {
        return try { phpClass.javaClass.getMethod("findMethodByName", String::class.java).invoke(phpClass, methodName) as? PsiElement }
        catch (_: Exception) {
            try {
                val methods = phpClass.javaClass.getMethod("getOwnMethods").invoke(phpClass) as? Array<*> ?: return null
                methods.filterIsInstance<PsiElement>().find { getName(it) == methodName }
            } catch (_: Exception) { null }
        }
    }
    protected fun getPhpIndex(project: Project): Any? {
        return try {
            phpIndexClass?.let { cls -> cls.getMethod("getInstance", Project::class.java).invoke(null, project) }
        } catch (_: Exception) { null }
    }
    protected fun getAllSubclasses(project: Project, fqn: String): Collection<PsiElement> {
        return try {
            val idx = getPhpIndex(project) ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            (idx.javaClass.getMethod("getAllSubclasses", String::class.java).invoke(idx, fqn) as? Collection<*>)?.filterIsInstance<PsiElement>() ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }
    protected fun buildMethodSignature(method: PsiElement): String {
        return try {
            val params = (method.javaClass.getMethod("getParameters").invoke(method) as? Array<*>)
                ?.filterIsInstance<PsiElement>()?.mapNotNull { param ->
                    try {
                        val n = param.javaClass.getMethod("getName").invoke(param) as? String ?: return@mapNotNull null
                        val t = try { param.javaClass.getMethod("getDeclaredType").invoke(param)?.toString() } catch (_: Exception) { null }
                        if (t != null) "$t \$$n" else "\$$n"
                    } catch (_: Exception) { null }
                }?.joinToString(", ") ?: ""
            "${getName(method) ?: "unknown"}($params)"
        } catch (_: Exception) { getName(method) ?: "unknown" }
    }
}

class PhpTypeHierarchyHandler : BasePhpHandler<TypeHierarchyData>(), TypeHierarchyHandler {
    override val languageId = "PHP"
    override fun canHandle(element: PsiElement) = isAvailable() && isPhpLanguage(element)
    override fun isAvailable() = isPhpPluginAvailable() && phpClassClass != null

    override fun getTypeHierarchy(element: PsiElement, project: Project): TypeHierarchyData? {
        val phpClass = findContainingPhpClass(element) ?: return null
        return TypeHierarchyData(
            TypeElementData(getFQN(phpClass) ?: getName(phpClass) ?: "unknown", getFQN(phpClass), phpClass.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, phpClass), determineClassKind(phpClass), "PHP"),
            getSupertypes(project, phpClass),
            getSubtypes(project, phpClass)
        )
    }

    private fun getSupertypes(project: Project, phpClass: PsiElement, visited: MutableSet<String> = mutableSetOf(), depth: Int = 0): List<TypeElementData> {
        if (depth > 50) return emptyList()
        val name = getFQN(phpClass) ?: getName(phpClass) ?: return emptyList()
        if (name in visited) return emptyList()
        visited.add(name)
        val result = mutableListOf<TypeElementData>()
        try {
            getSuperClass(phpClass)?.let { sc ->
                val scName = getFQN(sc) ?: getName(sc)
                if (scName != null && scName !in visited) result.add(TypeElementData(scName, getFQN(sc), sc.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, sc), determineClassKind(sc), "PHP", getSupertypes(project, sc, visited, depth + 1).takeIf { it.isNotEmpty() }))
            }
            getImplementedInterfaces(phpClass)?.filterIsInstance<PsiElement>()?.forEach { iface ->
                val ifaceName = getFQN(iface) ?: getName(iface)
                if (ifaceName != null && ifaceName !in visited) result.add(TypeElementData(ifaceName, getFQN(iface), iface.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, iface), "INTERFACE", "PHP", getSupertypes(project, iface, visited, depth + 1).takeIf { it.isNotEmpty() }))
            }
            getTraits(phpClass)?.filterIsInstance<PsiElement>()?.forEach { trait ->
                val traitName = getFQN(trait) ?: getName(trait)
                if (traitName != null && traitName !in visited) result.add(TypeElementData(traitName, getFQN(trait), trait.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, trait), "TRAIT", "PHP"))
            }
        } catch (_: Exception) {}
        return result
    }

    private fun getSubtypes(project: Project, phpClass: PsiElement): List<TypeElementData> {
        val fqn = getFQN(phpClass) ?: return emptyList()
        return try {
            getAllSubclasses(project, fqn).take(100).map { sub ->
                TypeElementData(getFQN(sub) ?: getName(sub) ?: "unknown", getFQN(sub), sub.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, sub), determineClassKind(sub), "PHP")
            }
        } catch (_: Exception) { emptyList() }
    }
}

class PhpImplementationsHandler : BasePhpHandler<List<ImplementationData>>(), ImplementationsHandler {
    override val languageId = "PHP"
    override fun canHandle(element: PsiElement) = isAvailable() && isPhpLanguage(element)
    override fun isAvailable() = isPhpPluginAvailable() && phpClassClass != null

    override fun findImplementations(element: PsiElement, project: Project): List<ImplementationData>? {
        findContainingMethod(element)?.let { return findMethodImpls(project, it) }
        findContainingPhpClass(element)?.let { return findClassImpls(project, it) }
        return null
    }

    private fun findMethodImpls(project: Project, method: PsiElement): List<ImplementationData> {
        val methodName = getName(method) ?: return emptyList()
        val cls = getContainingClass(method) ?: return emptyList()
        val fqn = getFQN(cls) ?: return emptyList()
        val results = mutableListOf<ImplementationData>()
        try {
            getAllSubclasses(project, fqn).take(100).forEach { sub ->
                findMethodInClass(sub, methodName)?.let { m ->
                    val f = m.containingFile?.virtualFile
                    if (f != null) {
                        val cn = getName(sub) ?: ""
                        results.add(ImplementationData(if (cn.isNotEmpty()) "$cn::$methodName" else methodName, getRelativePath(project, f), getLineNumber(project, m) ?: 0, "METHOD", "PHP"))
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    private fun findClassImpls(project: Project, phpClass: PsiElement): List<ImplementationData> {
        val fqn = getFQN(phpClass) ?: return emptyList()
        val results = mutableListOf<ImplementationData>()
        try {
            getAllSubclasses(project, fqn).take(100).forEach { sub ->
                val f = sub.containingFile?.virtualFile
                if (f != null) results.add(ImplementationData(getFQN(sub) ?: getName(sub) ?: "unknown", getRelativePath(project, f), getLineNumber(project, sub) ?: 0, determineClassKind(sub), "PHP"))
            }
        } catch (_: Exception) {}
        return results
    }
}

class PhpCallHierarchyHandler : BasePhpHandler<CallHierarchyData>(), CallHierarchyHandler {
    override val languageId = "PHP"
    override fun canHandle(element: PsiElement) = isAvailable() && isPhpLanguage(element)
    override fun isAvailable() = isPhpPluginAvailable() && methodClass != null

    override fun getCallHierarchy(element: PsiElement, project: Project, direction: String, depth: Int): CallHierarchyData? {
        val callable = findContainingCallable(element) ?: return null
        val visited = mutableSetOf<String>()
        val calls = if (direction == "callers") findCallers(project, callable, depth, visited) else findCallees(project, callable, depth, visited)
        return CallHierarchyData(createCallElement(project, callable), calls)
    }

    private fun getCallableKey(c: PsiElement): String {
        val cls = if (isMethod(c)) getContainingClass(c)?.let { getFQN(it) ?: getName(it) } ?: "" else ""
        return "$cls::${getName(c) ?: ""}"
    }

    private fun findSuperMethods(callable: PsiElement): Set<PsiElement> {
        if (!isMethod(callable)) return emptySet()
        val result = mutableSetOf<PsiElement>()
        val visited = mutableSetOf<String>()
        fun recurse(method: PsiElement) {
            val cls = getContainingClass(method) ?: return
            val methodName = getName(method) ?: return
            getSuperClass(cls)?.let { sc ->
                val key = "${getFQN(sc) ?: getName(sc)}::$methodName"
                if (key !in visited) { visited.add(key); findMethodInClass(sc, methodName)?.let { sm -> result.add(sm); recurse(sm) } }
            }
            getImplementedInterfaces(cls)?.filterIsInstance<PsiElement>()?.forEach { iface ->
                val key = "${getFQN(iface) ?: getName(iface)}::$methodName"
                if (key !in visited) { visited.add(key); findMethodInClass(iface, methodName)?.let { result.add(it) } }
            }
        }
        recurse(callable)
        return result.take(10).toSet()
    }

    private fun findCallers(project: Project, callable: PsiElement, depth: Int, visited: MutableSet<String>, stackDepth: Int = 0): List<CallElementData> {
        if (stackDepth > 50 || depth <= 0) return emptyList()
        val key = getCallableKey(callable)
        if (key in visited) return emptyList()
        visited.add(key)
        return try {
            val scope = GlobalSearchScope.projectScope(project)
            val toSearch = mutableSetOf(callable)
            toSearch.addAll(findSuperMethods(callable))
            val refs = mutableListOf<PsiReference>()
            for (m in toSearch) ReferencesSearch.search(m, scope).forEach(Processor { refs.add(it); refs.size < 40 })
            refs.take(20).mapNotNull { ref ->
                val containing = findContainingCallable(ref.element)
                if (containing != null && containing != callable && !toSearch.contains(containing)) createCallElement(project, containing, if (depth > 1) findCallers(project, containing, depth - 1, visited, stackDepth + 1) else null)
                else null
            }.distinctBy { it.name + it.file + it.line }
        } catch (_: Exception) { emptyList() }
    }

    private fun findCallees(project: Project, callable: PsiElement, depth: Int, visited: MutableSet<String>, stackDepth: Int = 0): List<CallElementData> {
        if (stackDepth > 50 || depth <= 0) return emptyList()
        val key = getCallableKey(callable)
        if (key in visited) return emptyList()
        visited.add(key)
        val callees = mutableListOf<CallElementData>()
        try {
            listOfNotNull(methodReferenceClass, functionReferenceClass).forEach { refCls ->
                @Suppress("UNCHECKED_CAST")
                val calls = PsiTreeUtil.findChildrenOfType(callable, refCls as Class<out PsiElement>)
                calls.take(20).forEach { callExpr ->
                    val resolved = resolvePhpRef(callExpr)
                    if (resolved != null && (isMethod(resolved) || isFunction(resolved))) {
                        val el = createCallElement(project, resolved, if (depth > 1) findCallees(project, resolved, depth - 1, visited, stackDepth + 1) else null)
                        if (callees.none { it.name == el.name && it.file == el.file }) callees.add(el)
                    }
                }
            }
        } catch (_: Exception) {}
        return callees
    }

    private fun resolvePhpRef(ref: PsiElement): PsiElement? {
        return try { ref.javaClass.getMethod("resolve").invoke(ref) as? PsiElement } catch (_: Exception) { null }
    }

    private fun createCallElement(project: Project, callable: PsiElement, children: List<CallElementData>? = null): CallElementData {
        val callableName = getName(callable) ?: "unknown"
        val name = if (isMethod(callable)) {
            val cls = getContainingClass(callable)?.let { getName(it) }
            if (cls != null) "$cls::$callableName" else callableName
        } else callableName
        return CallElementData(name, callable.containingFile?.virtualFile?.let { getRelativePath(project, it) } ?: "unknown", getLineNumber(project, callable) ?: 0, "PHP", children?.takeIf { it.isNotEmpty() })
    }
}

class PhpSymbolSearchHandler : BasePhpHandler<List<SymbolData>>(), SymbolSearchHandler {
    override val languageId = "PHP"
    override fun canHandle(element: PsiElement) = isAvailable()
    override fun isAvailable() = isPhpPluginAvailable() && phpClassClass != null
    override fun searchSymbols(project: Project, pattern: String, includeLibraries: Boolean, limit: Int): List<SymbolData> {
        val scope = if (includeLibraries) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)
        return OptimizedSymbolSearch.search(project, pattern, scope, limit, setOf("PHP"))
    }
}

class PhpSuperMethodsHandler : BasePhpHandler<SuperMethodsData>(), SuperMethodsHandler {
    override val languageId = "PHP"
    override fun canHandle(element: PsiElement) = isAvailable() && isPhpLanguage(element)
    override fun isAvailable() = isPhpPluginAvailable() && methodClass != null

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        val method = findContainingMethod(element) ?: return null
        val cls = getContainingClass(method) ?: return null
        val f = method.containingFile?.virtualFile
        val methodData = MethodData(getName(method) ?: "unknown", buildMethodSignature(method), getFQN(cls) ?: getName(cls) ?: "unknown", f?.let { getRelativePath(project, it) } ?: "unknown", getLineNumber(project, method) ?: 0, "PHP")
        return SuperMethodsData(methodData, buildHierarchy(project, method))
    }

    private fun buildHierarchy(project: Project, method: PsiElement, visited: MutableSet<String> = mutableSetOf(), depth: Int = 1): List<SuperMethodData> {
        val result = mutableListOf<SuperMethodData>()
        try {
            val cls = getContainingClass(method) ?: return emptyList()
            val methodName = getName(method) ?: return emptyList()
            getSuperClass(cls)?.let { sc ->
                val scName = getFQN(sc) ?: getName(sc)
                val key = "$scName::$methodName"
                if (key !in visited) {
                    visited.add(key)
                    findMethodInClass(sc, methodName)?.let { sm ->
                        result.add(SuperMethodData(methodName, buildMethodSignature(sm), scName ?: "unknown", determineClassKind(sc), sm.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, sm), isInterface(sc), depth, "PHP"))
                        result.addAll(buildHierarchy(project, sm, visited, depth + 1))
                    }
                }
            }
            getImplementedInterfaces(cls)?.filterIsInstance<PsiElement>()?.forEach { iface ->
                val ifaceName = getFQN(iface) ?: getName(iface)
                val key = "$ifaceName::$methodName"
                if (key !in visited) {
                    visited.add(key)
                    findMethodInClass(iface, methodName)?.let { ifaceMethod ->
                        result.add(SuperMethodData(methodName, buildMethodSignature(ifaceMethod), ifaceName ?: "unknown", "INTERFACE", ifaceMethod.containingFile?.virtualFile?.let { getRelativePath(project, it) }, getLineNumber(project, ifaceMethod), true, depth, "PHP"))
                        result.addAll(buildHierarchy(project, ifaceMethod, visited, depth + 1))
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }
}
