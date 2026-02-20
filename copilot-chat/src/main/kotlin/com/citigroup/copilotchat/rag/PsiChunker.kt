package com.citigroup.copilotchat.rag

import com.citigroup.copilotchat.tools.psi.LanguageHandlerRegistry
import com.citigroup.copilotchat.tools.psi.StructureKind
import com.citigroup.copilotchat.tools.psi.StructureNode
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * PSI-aware code chunker that splits source files into semantically meaningful chunks
 * suitable for embedding and retrieval.
 *
 * Uses the existing [LanguageHandlerRegistry.getStructureHandler] for language-specific
 * structure extraction, then groups structure nodes into chunks.
 *
 * Chunking strategy:
 * - Methods/functions: each becomes one chunk
 * - Small classes (<30 lines): kept as single chunk
 * - Large classes: split into header + individual methods
 * - Top-level declarations: grouped per file section
 */
object PsiChunker {

    private val log = Logger.getInstance(PsiChunker::class.java)

    private const val MAX_CHUNK_CHARS = 8000
    private const val MIN_CHUNK_CHARS = 50
    private const val SMALL_CLASS_LINE_THRESHOLD = 30

    /**
     * Chunk a PSI file into [CodeChunk]s using the structure handler for its language.
     * Falls back to a simple line-based split if no handler is available.
     */
    fun chunkFile(psiFile: PsiFile, project: Project): List<CodeChunk> {
        val filePath = psiFile.virtualFile?.path ?: return emptyList()
        val fileText = psiFile.text ?: return emptyList()
        if (fileText.length < MIN_CHUNK_CHARS) return emptyList()

        LanguageHandlerRegistry.ensureInitialized()
        val handler = LanguageHandlerRegistry.getStructureHandler(psiFile)

        return if (handler != null) {
            try {
                val structure = handler.getFileStructure(psiFile, project)
                chunkFromStructure(filePath, fileText, structure)
            } catch (e: Exception) {
                log.debug("PSI structure extraction failed for $filePath, falling back to line-based: ${e.message}")
                chunkByLines(filePath, fileText)
            }
        } else {
            chunkByLines(filePath, fileText)
        }
    }

    private fun chunkFromStructure(filePath: String, fileText: String, nodes: List<StructureNode>): List<CodeChunk> {
        if (nodes.isEmpty()) return chunkByLines(filePath, fileText)

        val lines = fileText.lines()
        val chunks = mutableListOf<CodeChunk>()

        for (node in nodes) {
            chunks.addAll(chunkStructureNode(filePath, lines, node, parentName = null))
        }

        // If no chunks were produced, fall back to line-based
        if (chunks.isEmpty()) return chunkByLines(filePath, fileText)

        return chunks.filter { it.content.length >= MIN_CHUNK_CHARS }
    }

    private fun chunkStructureNode(
        filePath: String,
        lines: List<String>,
        node: StructureNode,
        parentName: String?,
    ): List<CodeChunk> {
        val chunks = mutableListOf<CodeChunk>()
        val symbolName = if (parentName != null) "$parentName.${node.name}" else node.name

        when {
            // Classes/interfaces/objects with children
            node.kind in setOf(StructureKind.CLASS, StructureKind.INTERFACE, StructureKind.OBJECT,
                StructureKind.ENUM, StructureKind.TRAIT) && node.children.isNotEmpty() -> {

                val classStartLine = node.line
                val lastChildLine = findLastLine(node)
                val classLineCount = lastChildLine - classStartLine + 1

                if (classLineCount <= SMALL_CLASS_LINE_THRESHOLD) {
                    // Small class: keep as single chunk
                    val content = extractLines(lines, classStartLine, lastChildLine)
                    if (content.isNotEmpty()) {
                        chunks.add(CodeChunk(
                            filePath = filePath,
                            startLine = classStartLine,
                            endLine = lastChildLine,
                            content = content.take(MAX_CHUNK_CHARS),
                            symbolName = symbolName,
                        ))
                    }
                } else {
                    // Large class: header chunk + individual method chunks
                    val firstChildLine = node.children.minOf { it.line }
                    if (firstChildLine > classStartLine) {
                        val headerContent = extractLines(lines, classStartLine, firstChildLine - 1)
                        if (headerContent.length >= MIN_CHUNK_CHARS) {
                            chunks.add(CodeChunk(
                                filePath = filePath,
                                startLine = classStartLine,
                                endLine = firstChildLine - 1,
                                content = headerContent.take(MAX_CHUNK_CHARS),
                                symbolName = "$symbolName (header)",
                            ))
                        }
                    }

                    // Recurse into children
                    for (child in node.children) {
                        chunks.addAll(chunkStructureNode(filePath, lines, child, parentName = node.name))
                    }
                }
            }

            // Methods, functions, constructors: each is one chunk
            node.kind in setOf(StructureKind.METHOD, StructureKind.FUNCTION, StructureKind.CONSTRUCTOR) -> {
                val startLine = node.line
                val endLine = findLastLine(node)
                val content = extractLines(lines, startLine, endLine)
                if (content.isNotEmpty()) {
                    chunks.add(CodeChunk(
                        filePath = filePath,
                        startLine = startLine,
                        endLine = endLine,
                        content = content.take(MAX_CHUNK_CHARS),
                        symbolName = symbolName,
                    ))
                }
            }

            // Fields, properties, variables, type aliases, etc.
            else -> {
                val startLine = node.line
                val endLine = findLastLine(node)
                val content = extractLines(lines, startLine, endLine)
                if (content.isNotEmpty()) {
                    chunks.add(CodeChunk(
                        filePath = filePath,
                        startLine = startLine,
                        endLine = endLine,
                        content = content.take(MAX_CHUNK_CHARS),
                        symbolName = symbolName,
                    ))
                }

                // Recurse into children if any
                for (child in node.children) {
                    chunks.addAll(chunkStructureNode(filePath, lines, child, parentName = node.name))
                }
            }
        }

        return chunks
    }

    /**
     * Estimate the last line of a structure node by looking at its children
     * or using a heuristic based on the next sibling/end of file.
     */
    private fun findLastLine(node: StructureNode): Int {
        if (node.children.isEmpty()) {
            // For leaf nodes, estimate ~10 lines for a method, ~1 for a field
            val estimate = when (node.kind) {
                StructureKind.METHOD, StructureKind.FUNCTION, StructureKind.CONSTRUCTOR -> node.line + 15
                else -> node.line + 2
            }
            return estimate
        }
        return maxOf(node.line, node.children.maxOf { findLastLine(it) })
    }

    /**
     * Extract lines from the file text (1-indexed, inclusive).
     */
    private fun extractLines(lines: List<String>, startLine: Int, endLine: Int): String {
        val start = (startLine - 1).coerceIn(0, lines.size - 1)
        val end = (endLine - 1).coerceIn(start, lines.size - 1)
        return lines.subList(start, end + 1).joinToString("\n")
    }

    /**
     * Fallback: split file into ~60-line chunks with overlap.
     */
    private fun chunkByLines(filePath: String, fileText: String): List<CodeChunk> {
        val lines = fileText.lines()
        val chunkSize = 60
        val overlap = 5
        val chunks = mutableListOf<CodeChunk>()

        var start = 0
        while (start < lines.size) {
            val end = minOf(start + chunkSize - 1, lines.size - 1)
            val content = lines.subList(start, end + 1).joinToString("\n")
            if (content.length >= MIN_CHUNK_CHARS) {
                chunks.add(CodeChunk(
                    filePath = filePath,
                    startLine = start + 1,
                    endLine = end + 1,
                    content = content.take(MAX_CHUNK_CHARS),
                    symbolName = null,
                ))
            }
            start += chunkSize - overlap
        }

        return chunks
    }
}

data class CodeChunk(
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val content: String,
    val symbolName: String?,
)
