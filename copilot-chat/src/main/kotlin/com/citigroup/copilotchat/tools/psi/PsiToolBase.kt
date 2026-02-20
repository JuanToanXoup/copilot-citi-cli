package com.citigroup.copilotchat.tools.psi

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction as platformReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Base class for PSI-powered tools integrated directly into copilot-chat.
 */
abstract class PsiToolBase {

    protected val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    abstract val name: String
    abstract val description: String
    abstract val inputSchema: String

    protected open val requiresPsiSync: Boolean = true

    suspend fun execute(project: Project, arguments: JsonObject): ToolCallResult {
        if (requiresPsiSync) {
            ensurePsiUpToDate(project)
        }
        return doExecute(project, arguments)
    }

    protected abstract suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult

    private suspend fun ensurePsiUpToDate(project: Project) {
        val projectDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        if (projectDir != null) {
            VfsUtil.markDirtyAndRefresh(true, true, true, projectDir)
        }
        withContext(Dispatchers.EDT) {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
    }

    protected fun requireSmartMode(project: Project) {
        if (DumbService.isDumb(project)) {
            throw IllegalStateException("IDE is indexing. Please wait and retry.")
        }
    }

    protected fun <T> readAction(action: () -> T): T = ReadAction.compute<T, Throwable>(action)

    protected suspend fun <T> suspendingReadAction(action: () -> T): T = platformReadAction { action() }

    protected fun checkCanceled() = ProgressManager.checkCanceled()

    protected fun writeAction(project: Project, commandName: String, action: () -> Unit) {
        WriteCommandAction.runWriteCommandAction(project, commandName, null, { action() })
    }

    protected suspend fun suspendingWriteAction(project: Project, commandName: String, action: () -> Unit) {
        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project, commandName, null, { action() })
        }
    }

    protected fun resolveFile(project: Project, relativePath: String): VirtualFile? {
        val basePath = project.basePath ?: return null
        val fullPath = if (relativePath.startsWith("/")) relativePath else "$basePath/$relativePath"
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)
    }

    protected fun getPsiFile(project: Project, relativePath: String): PsiFile? {
        val virtualFile = resolveFile(project, relativePath) ?: return null
        return PsiManager.getInstance(project).findFile(virtualFile)
    }

    protected fun findPsiElement(project: Project, file: String, line: Int, column: Int): PsiElement? {
        val psiFile = getPsiFile(project, file) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val offset = getOffset(document, line, column) ?: return null
        return psiFile.findElementAt(offset)
    }

    protected fun getOffset(document: Document, line: Int, column: Int): Int? {
        val lineIndex = line - 1
        if (lineIndex < 0 || lineIndex >= document.lineCount) return null
        val lineStartOffset = document.getLineStartOffset(lineIndex)
        val lineEndOffset = document.getLineEndOffset(lineIndex)
        val offset = lineStartOffset + (column - 1)
        return if (offset <= lineEndOffset) offset else lineEndOffset
    }

    protected fun getLineText(document: Document, line: Int): String {
        val lineIndex = line - 1
        if (lineIndex < 0 || lineIndex >= document.lineCount) return ""
        return document.getText(TextRange(document.getLineStartOffset(lineIndex), document.getLineEndOffset(lineIndex)))
    }

    protected fun getRelativePath(project: Project, virtualFile: VirtualFile): String {
        val basePath = project.basePath ?: return virtualFile.path
        return virtualFile.path.removePrefix(basePath).removePrefix("/")
    }

    protected fun createSuccessResult(text: String) = ToolCallResult(listOf(ContentBlock.Text(text)), false)
    protected fun createErrorResult(message: String) = ToolCallResult(listOf(ContentBlock.Text(message)), true)
    protected inline fun <reified T> createJsonResult(data: T) = ToolCallResult(listOf(ContentBlock.Text(json.encodeToString(data))), false)
}

// ── PsiUtils ──

object PsiUtils {
    fun resolveTargetElement(element: PsiElement): PsiElement? {
        // Try direct reference resolution
        element.reference?.resolve()?.let { return it }
        // Try parent references
        var current: PsiElement? = element
        repeat(3) {
            current = current?.parent ?: return@repeat
            current?.reference?.resolve()?.let { return it }
        }
        // If element itself is a named element, use it
        if (element is PsiNamedElement && element.name != null) return element
        // Walk up to find a named element
        current = element.parent
        repeat(5) {
            if (current == null) return@repeat
            if (current is PsiNamedElement && (current as PsiNamedElement).name != null) return current
            current = current?.parent
        }
        return null
    }

    fun getContainingClass(element: PsiElement): PsiNamedElement? {
        var current: PsiElement? = element.parent
        repeat(20) {
            if (current == null) return null
            val className = current!!.javaClass.simpleName.lowercase()
            if (className.contains("class") || className.contains("object")) {
                if (current is PsiNamedElement) return current as PsiNamedElement
            }
            current = current?.parent
        }
        return null
    }
}

// ── TreeFormatter ──

object TreeFormatter {
    fun format(nodes: List<StructureNode>, fileName: String, languageId: String): String {
        val sb = StringBuilder()
        sb.appendLine("$fileName ($languageId)")
        for (node in nodes) {
            formatNode(sb, node, 0, languageId)
        }
        return sb.toString().trimEnd()
    }

    private fun formatNode(sb: StringBuilder, node: StructureNode, depth: Int, languageId: String) {
        val indent = "  ".repeat(depth)
        val modifiers = if (node.modifiers.isNotEmpty()) node.modifiers.joinToString(" ") + " " else ""
        val kindStr = node.kind.name.lowercase()
        val sig = if (!node.signature.isNullOrBlank()) " ${node.signature}" else ""
        sb.appendLine("$indent$modifiers$kindStr ${node.name}$sig  [line ${node.line}]")
        for (child in node.children) {
            formatNode(sb, child, depth + 1, languageId)
        }
    }
}
