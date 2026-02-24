package com.citigroup.copilotchat.tools.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

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
