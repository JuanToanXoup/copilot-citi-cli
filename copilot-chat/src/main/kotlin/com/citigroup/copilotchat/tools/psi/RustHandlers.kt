package com.citigroup.copilotchat.tools.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

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
