package com.citigroup.copilotchat.tools.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

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
