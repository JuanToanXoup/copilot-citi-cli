package com.citigroup.copilotchat.tools.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

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
