package com.citigroup.copilotchat.tools.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

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
