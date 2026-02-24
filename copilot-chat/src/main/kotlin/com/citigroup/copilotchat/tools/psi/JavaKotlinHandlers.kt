package com.citigroup.copilotchat.tools.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

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
