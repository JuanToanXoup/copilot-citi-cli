package com.citigroup.copilotchat.tools.psi

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
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
        // Kotlin delegates to Java handlers
        registerTypeHierarchyHandler(KotlinTypeHierarchyHandler())
        registerImplementationsHandler(KotlinImplementationsHandler())
        registerCallHierarchyHandler(KotlinCallHierarchyHandler())
        registerSymbolSearchHandler(KotlinSymbolSearchHandler())
        registerSuperMethodsHandler(KotlinSuperMethodsHandler())
        registerStructureHandler(KotlinStructureHandler())
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
class KotlinCallHierarchyHandler : CallHierarchyHandler by JavaCallHierarchyHandler() { override val languageId = "kotlin" }
class KotlinSymbolSearchHandler : SymbolSearchHandler by JavaSymbolSearchHandler() { override val languageId = "kotlin" }
class KotlinSuperMethodsHandler : SuperMethodsHandler by JavaSuperMethodsHandler() { override val languageId = "kotlin" }

class KotlinStructureHandler : BaseJavaHandler<List<StructureNode>>(), StructureHandler {
    override val languageId = "kotlin"
    override fun canHandle(element: PsiElement) = isAvailable() && element.language.id == "kotlin"
    override fun isAvailable() = isJavaPluginAvailable()
    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        // Delegate to Java handler which handles Kotlin via light classes
        return JavaStructureHandler().getFileStructure(file, project)
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
