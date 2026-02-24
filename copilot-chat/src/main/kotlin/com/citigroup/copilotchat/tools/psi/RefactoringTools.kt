package com.citigroup.copilotchat.tools.psi

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import kotlinx.serialization.json.JsonObject

// ═══════════════════════════════════════════════════════════════
// Refactoring Tools
// ═══════════════════════════════════════════════════════════════

class RenameSymbolTool : PsiToolBase() {
    override val name = "ide_rename_symbol"
    override val description = "Rename a symbol and update all references across the project. Use instead of find-and-replace for safe, semantic renaming. Automatically renames related elements: getters/setters, overriding methods, constructor parameters, and test classes."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Path to file relative to project root"},"line":{"type":"integer","description":"1-based line number where the symbol is located"},"column":{"type":"integer","description":"1-based column number"},"newName":{"type":"string","description":"The new name for the symbol"}},"required":["file","line","column","newName"]}"""

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)
        val file = arguments.str("file") ?: return createErrorResult("file is required")
        val line = arguments.int("line") ?: return createErrorResult("line is required")
        val column = arguments.int("column") ?: return createErrorResult("column is required")
        val newName = arguments.str("newName") ?: return createErrorResult("newName is required")

        val element = suspendingReadAction { findPsiElement(project, file, line, column) }
            ?: return createErrorResult("No element found at $file:$line:$column")
        val target = suspendingReadAction { PsiUtils.resolveTargetElement(element) }
            ?: return createErrorResult("Cannot resolve symbol at $file:$line:$column")

        // Validate the new name against language naming rules
        val isValidName = suspendingReadAction {
            try {
                val lang = target.language
                val validator = com.intellij.lang.LanguageExtension<com.intellij.lang.refactoring.NamesValidator>("com.intellij.lang.namesValidator")
                    .forLanguage(lang)
                validator?.isIdentifier(newName, project) ?: true
            } catch (_: Exception) { true }
        }
        if (!isValidName) {
            return createErrorResult("'$newName' is not a valid identifier for ${suspendingReadAction { target.language.displayName }}")
        }

        // Check for naming conflicts before executing
        val conflictMessage = suspendingReadAction {
            try {
                val processorEp = Class.forName("com.intellij.refactoring.rename.RenamePsiElementProcessor")
                val forElement = processorEp.getMethod("forElement", PsiElement::class.java)
                val renameProcessor = forElement.invoke(null, target)
                val multiMapClass = Class.forName("com.intellij.util.containers.MultiMap")
                val conflicts = multiMapClass.getConstructor().newInstance()
                val findConflicts = renameProcessor.javaClass.getMethod(
                    "findExistingNameConflicts", PsiElement::class.java, String::class.java, multiMapClass
                )
                findConflicts.invoke(renameProcessor, target, newName, conflicts)
                val isEmpty = multiMapClass.getMethod("isEmpty").invoke(conflicts) as Boolean
                if (!isEmpty) {
                    @Suppress("UNCHECKED_CAST")
                    val values = multiMapClass.getMethod("values").invoke(conflicts) as Collection<String>
                    val msgs = values.take(3).joinToString("; ")
                    val more = if (values.size > 3) " (and ${values.size - 3} more)" else ""
                    "Name conflict: $msgs$more"
                } else null
            } catch (_: Exception) { null }
        }
        if (conflictMessage != null) {
            return createErrorResult(conflictMessage)
        }

        // Substitute element if needed (e.g., light elements → real elements)
        val effectiveTarget = suspendingReadAction {
            try {
                val processorEp = Class.forName("com.intellij.refactoring.rename.RenamePsiElementProcessor")
                val forElement = processorEp.getMethod("forElement", PsiElement::class.java)
                val renameProc = forElement.invoke(null, target)
                val substitute = renameProc.javaClass.getMethod("substituteElementToRename", PsiElement::class.java, com.intellij.openapi.editor.Editor::class.java)
                (substitute.invoke(renameProc, target, null) as? PsiElement) ?: target
            } catch (_: Exception) { target }
        }

        return try {
            val processorClass = Class.forName("com.intellij.refactoring.rename.RenameProcessor")
            val affectedFiles = mutableSetOf<String>()
            var relatedRenamesCount = 0

            suspendingWriteAction(project, "Rename to $newName") {
                val processor = processorClass.getConstructor(
                    Project::class.java, PsiElement::class.java,
                    String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
                ).newInstance(project, effectiveTarget, newName, false, false)

                // Add related elements via AutomaticRenamerFactory
                try {
                    val factoryEp = Class.forName("com.intellij.refactoring.rename.naming.AutomaticRenamerFactory")
                    val epNameField = factoryEp.getDeclaredField("EP_NAME")
                    val epName = epNameField.get(null)
                    @Suppress("UNCHECKED_CAST")
                    val factories = epName.javaClass.getMethod("getExtensionList").invoke(epName) as List<Any>
                    val usageInfoClass = Class.forName("com.intellij.usageView.UsageInfo")
                    val emptyUsages = java.lang.reflect.Array.newInstance(usageInfoClass, 0)

                    for (factory in factories) {
                        try {
                            val isApplicable = factory.javaClass.getMethod("isApplicable", PsiElement::class.java)
                            if (isApplicable.invoke(factory, effectiveTarget) != true) continue

                            val createRenamer = factory.javaClass.getMethod("createRenamer", PsiElement::class.java, String::class.java, java.util.Collection::class.java)
                            val renamer = createRenamer.invoke(factory, effectiveTarget, newName, java.util.Collections.EMPTY_LIST) ?: continue

                            @Suppress("UNCHECKED_CAST")
                            val elements = renamer.javaClass.getMethod("getElements").invoke(renamer) as? Collection<PsiNamedElement> ?: continue
                            for (relatedElement in elements) {
                                if (relatedElement == effectiveTarget) continue
                                val relatedNewName = renamer.javaClass.getMethod("getNewName", PsiNamedElement::class.java).invoke(renamer, relatedElement) as? String ?: continue
                                val currentName = relatedElement.name ?: continue
                                if (currentName == relatedNewName) continue

                                // Add to processor's rename map
                                val addElement = processorClass.getMethod("addElement", PsiElement::class.java, String::class.java)
                                addElement.invoke(processor, relatedElement, relatedNewName)
                                relatedRenamesCount++

                                relatedElement.containingFile?.virtualFile?.let { vf ->
                                    val basePath = project.basePath ?: ""
                                    affectedFiles.add(vf.path.removePrefix(basePath).removePrefix("/"))
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}

                // Disable preview dialog for headless operation
                try {
                    processorClass.getMethod("setPreviewUsages", Boolean::class.javaPrimitiveType)
                        .invoke(processor, false)
                } catch (_: Exception) {}

                processorClass.getMethod("run").invoke(processor)

                // Commit and save documents
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()

                // Collect affected files from the target
                effectiveTarget.containingFile?.virtualFile?.let { vf ->
                    val basePath = project.basePath ?: ""
                    affectedFiles.add(vf.path.removePrefix(basePath).removePrefix("/"))
                }
            }

            val msg = if (relatedRenamesCount > 0) {
                "Renamed to '$newName' (including $relatedRenamesCount related element(s))"
            } else {
                "Renamed to '$newName'"
            }
            createJsonResult(RefactoringResult(true, affectedFiles.toList(), affectedFiles.size + relatedRenamesCount, msg))
        } catch (e: Exception) {
            createErrorResult("Rename failed: ${e.cause?.message ?: e.message}")
        }
    }
}

class SafeDeleteTool : PsiToolBase() {
    override val name = "ide_safe_delete"
    override val description = "Delete a symbol or file safely by first checking for usages. Use when removing code to avoid breaking references. Reports any references that would be broken before deleting. When cursor is on whitespace or a comment, suggests nearby deletable symbols."
    override val inputSchema = """{"type":"object","properties":{"file":{"type":"string","description":"Path to file relative to project root"},"line":{"type":"integer","description":"1-based line number where the symbol is located (required when targetType is 'symbol')"},"column":{"type":"integer","description":"1-based column number (required when targetType is 'symbol')"},"searchInComments":{"type":"boolean","description":"Also search for references in comments and strings (default: false)"},"targetType":{"type":"string","description":"What to delete: 'symbol' (default) or 'file'","enum":["symbol","file"]},"force":{"type":"boolean","description":"Force delete even if usages exist (default: false)"}},"required":["file"]}"""

    companion object {
        private const val NEARBY_SEARCH_DISTANCE = 10
        private const val MAX_SUGGESTIONS = 5
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)
        val file = arguments.str("file") ?: return createErrorResult("file is required")
        val targetType = arguments.str("targetType") ?: "symbol"
        val force = arguments.bool("force") ?: false
        val searchInComments = arguments.bool("searchInComments") ?: false

        if (targetType == "file") {
            return deleteFile(project, file, force, searchInComments)
        }

        // Symbol mode - line and column required
        val line = arguments.int("line") ?: return createErrorResult("line is required for symbol deletion")
        val column = arguments.int("column") ?: return createErrorResult("column is required for symbol deletion")

        val element = suspendingReadAction { findPsiElement(project, file, line, column) }
            ?: return createErrorResult("No element found at $file:$line:$column")

        // Check if cursor is on whitespace or comment — suggest nearby symbols
        val nearbySuggestion = suspendingReadAction {
            if (element is PsiWhiteSpace || element is PsiComment) {
                // For doc comments, check if the next sibling is the documented symbol
                val docAdjacent = findDocAdjacentSymbol(element)
                if (docAdjacent != null) {
                    return@suspendingReadAction formatNearbySymbolError("doc comment", listOf(docAdjacent), project)
                }
                val suggestions = findNearbySymbols(element.containingFile, line, project)
                val elementType = if (element is PsiWhiteSpace) "whitespace" else "comment"
                return@suspendingReadAction formatNearbySymbolError(elementType, suggestions, project)
            }
            null
        }
        if (nearbySuggestion != null) return nearbySuggestion

        val target = suspendingReadAction { PsiUtils.resolveTargetElement(element) }
            ?: return createErrorResult("Cannot resolve symbol at $file:$line:$column")

        // Check for usages
        if (!force) {
            val usageCount = suspendingReadAction {
                var count = 0
                ReferencesSearch.search(target, GlobalSearchScope.projectScope(project)).forEach(Processor { count++; count < 100 })
                count
            }
            if (usageCount > 0) {
                return createErrorResult("Cannot safely delete: found $usageCount usage(s). Remove references first or set force=true.")
            }
        }

        return try {
            val processorClass = Class.forName("com.intellij.refactoring.safeDelete.SafeDeleteProcessor")
            val affectedFile = suspendingReadAction { target.containingFile?.virtualFile?.let { getRelativePath(project, it) } ?: file }

            suspendingWriteAction(project, "Safe delete") {
                val create = processorClass.getMethod("createInstance",
                    Project::class.java, Array<PsiElement>::class.java,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                val processor = create.invoke(null, project, arrayOf(target), searchInComments, false, false)
                processorClass.getMethod("run").invoke(processor)
            }

            createJsonResult(RefactoringResult(true, listOf(affectedFile), 1, "Successfully deleted"))
        } catch (e: Exception) {
            createErrorResult("Safe delete failed: ${e.cause?.message ?: e.message}")
        }
    }

    private suspend fun deleteFile(project: Project, filePath: String, force: Boolean, searchInComments: Boolean): ToolCallResult {
        val psiFile = suspendingReadAction { getPsiFile(project, filePath) }
            ?: return createErrorResult("File not found: $filePath")

        if (!force) {
            // Only check top-level declarations and filter to external-only usages
            val externalUsageCount = suspendingReadAction {
                val topLevel = collectTopLevelDeclarations(psiFile)
                var externalCount = 0
                for (element in topLevel) {
                    ReferencesSearch.search(element, GlobalSearchScope.projectScope(project)).forEach(Processor { ref ->
                        val refFile = ref.element.containingFile?.virtualFile
                        val targetVf = psiFile.virtualFile
                        // Only count usages from OTHER files
                        if (refFile != null && refFile != targetVf) {
                            externalCount++
                        }
                        externalCount < 100
                    })
                    if (externalCount >= 100) break
                }
                externalCount
            }
            if (externalUsageCount > 0) {
                return createErrorResult("Cannot safely delete file: found $externalUsageCount external usage(s) of symbols in this file. Internal usages within the file are excluded. Set force=true to delete anyway.")
            }
        }

        return try {
            val processorClass = Class.forName("com.intellij.refactoring.safeDelete.SafeDeleteProcessor")
            val symbolCount = suspendingReadAction { collectTopLevelDeclarations(psiFile).size }

            suspendingWriteAction(project, "Safe delete file") {
                val create = processorClass.getMethod("createInstance",
                    Project::class.java, Array<PsiElement>::class.java,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                val processor = create.invoke(null, project, arrayOf(psiFile), searchInComments, false, false)
                processorClass.getMethod("run").invoke(processor)
            }

            val msg = if (force) {
                "Force-deleted file '$filePath' (may have had external usages that are now broken)"
            } else {
                "Successfully deleted file '$filePath' (contained $symbolCount top-level symbol(s) with no external usages)"
            }
            createJsonResult(RefactoringResult(true, listOf(filePath), 1, msg))
        } catch (e: Exception) {
            createErrorResult("File delete failed: ${e.cause?.message ?: e.message}")
        }
    }

    private fun collectTopLevelDeclarations(psiFile: PsiFile): List<PsiNamedElement> {
        val result = mutableListOf<PsiNamedElement>()
        for (child in psiFile.children) {
            if (child is PsiNamedElement && child.name != null) {
                result.add(child)
            }
        }
        return result
    }

    private fun findDocAdjacentSymbol(commentElement: PsiElement): PsiNamedElement? {
        if (commentElement !is PsiComment) return null
        val text = commentElement.text
        val isDocComment = text.startsWith("/**") || text.startsWith("///")
        if (!isDocComment) return null

        var sibling = commentElement.nextSibling
        while (sibling != null && sibling is PsiWhiteSpace) {
            sibling = sibling.nextSibling
        }
        return if (sibling is PsiNamedElement && sibling !is PsiFile && sibling.name != null) sibling else null
    }

    private fun findNearbySymbols(psiFile: PsiFile, currentLine: Int, project: Project): List<PsiNamedElement> {
        val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return emptyList()
        val startLine = maxOf(1, currentLine - NEARBY_SEARCH_DISTANCE)
        val endLine = minOf(doc.lineCount, currentLine + NEARBY_SEARCH_DISTANCE)
        val startOffset = doc.getLineStartOffset(startLine - 1)
        val endOffset = if (endLine <= doc.lineCount) doc.getLineEndOffset(endLine - 1) else doc.textLength

        val results = mutableListOf<Pair<PsiNamedElement, Int>>()
        com.intellij.psi.util.PsiTreeUtil.processElements(psiFile) { element ->
            if (element.textOffset < startOffset || element.textOffset > endOffset) return@processElements true
            if (element is PsiNamedElement && element !is PsiFile && element.name != null) {
                val elemLine = doc.getLineNumber(element.textOffset) + 1
                val distance = kotlin.math.abs(elemLine - currentLine)
                if (distance <= NEARBY_SEARCH_DISTANCE) {
                    results.add(element to distance)
                }
            }
            true
        }
        return results.sortedBy { it.second }.take(MAX_SUGGESTIONS).map { it.first }
    }

    private fun formatNearbySymbolError(elementType: String, suggestions: List<PsiNamedElement>, project: Project): ToolCallResult {
        if (suggestions.isEmpty()) {
            return createErrorResult("Cursor is on $elementType, not a deletable symbol. No nearby symbols found within $NEARBY_SEARCH_DISTANCE lines.")
        }
        val suggestionLines = suggestions.mapNotNull { elem ->
            val doc = PsiDocumentManager.getInstance(project).getDocument(elem.containingFile) ?: return@mapNotNull null
            val line = doc.getLineNumber(elem.textOffset) + 1
            val col = elem.textOffset - doc.getLineStartOffset(line - 1) + 1
            val kind = determineKind(elem)
            "  - ${elem.name} ($kind) at line $line, column $col"
        }
        return createErrorResult(
            "Cursor is on $elementType, not a deletable symbol. Nearby symbols:\n${suggestionLines.joinToString("\n")}\nUse one of these positions to target a specific symbol."
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// Project Tools
// ═══════════════════════════════════════════════════════════════

class GetIndexStatusTool : PsiToolBase() {
    override val name = "ide_get_index_status"
    override val description = "Check if the IDE is ready for code intelligence operations. Use when other tools fail with indexing errors."
    override val inputSchema = """{"type":"object","properties":{},"required":[]}"""
    override val requiresPsiSync = false

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val isDumb = DumbService.isDumb(project)
        return createJsonResult(IndexStatusResult(isDumb, isDumb, null))
    }
}

// ── Shared utilities ──

internal fun determineKind(element: PsiElement): String {
    val cn = element.javaClass.simpleName.lowercase()
    return when {
        cn.contains("class") -> "CLASS"
        cn.contains("interface") -> "INTERFACE"
        cn.contains("enum") -> "ENUM"
        cn.contains("method") -> "METHOD"
        cn.contains("function") -> "FUNCTION"
        cn.contains("field") -> "FIELD"
        cn.contains("variable") -> "VARIABLE"
        cn.contains("property") -> "PROPERTY"
        else -> "SYMBOL"
    }
}
