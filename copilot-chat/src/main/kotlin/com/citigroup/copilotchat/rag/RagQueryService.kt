package com.citigroup.copilotchat.rag

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Query pipeline for RAG: embeds user query, searches vector store, formats results as XML context.
 *
 * Flow:
 *   1. Embed user query via [LocalEmbeddings]
 *   2. Search vector store for similar code chunks
 *   3. Deduplicate overlapping chunks
 *   4. Format as `<rag_context>` XML block
 *   5. Return formatted context string to prepend to user message
 */
@Service(Service.Level.PROJECT)
class RagQueryService(private val project: Project) : com.intellij.openapi.Disposable {

    private val log = Logger.getInstance(RagQueryService::class.java)
    private val embeddings: EmbeddingsProvider = LocalEmbeddings

    companion object {
        private const val MAX_CONTEXT_CHARS = 4000
        private const val DEFAULT_TOP_K = 5
        private const val SCORE_THRESHOLD = 0.25f

        fun getInstance(project: Project): RagQueryService =
            project.getService(RagQueryService::class.java)
    }

    /**
     * Retrieve relevant code context for a user query.
     * Returns formatted XML string, or empty string if RAG is unavailable or finds nothing.
     */
    fun retrieve(query: String, topK: Int = DEFAULT_TOP_K): String {
        return try {
            doRetrieve(query, topK)
        } catch (e: Exception) {
            log.warn("RAG retrieval failed: ${e.message}", e)
            ""
        }
    }

    private fun doRetrieve(query: String, topK: Int): String {
        val store = VectorStore.getInstance()
        val indexer = RagIndexer.getInstance(project)
        val collection = indexer.collectionName()

        // Embed the query
        val queryVector = embeddings.embed(query)
        log.info("RAG: embedded query (${query.take(50)}...), searching collection '$collection'")

        // Search vector store
        val results = store.search(collection, queryVector, topK, SCORE_THRESHOLD)
        if (results.isEmpty()) {
            log.info("RAG: no results above score threshold $SCORE_THRESHOLD")
            return ""
        }

        // Deduplicate overlapping chunks
        val deduplicated = deduplicateResults(results)

        log.info("RAG: injecting ${deduplicated.size} chunks (scores: ${deduplicated.map { "%.2f".format(it.score) }})")

        // Format as XML context, respecting budget
        return formatContext(deduplicated)
    }

    private fun deduplicateResults(results: List<VectorSearchResult>): List<VectorSearchResult> {
        val seen = mutableSetOf<String>()
        return results.filter { result ->
            val key = "${result.payload["filePath"]}:${result.payload["startLine"]}-${result.payload["endLine"]}"
            seen.add(key)
        }
    }

    private fun formatContext(results: List<VectorSearchResult>): String {
        val sb = StringBuilder()
        sb.appendLine("<rag_context>")

        var totalChars = "<rag_context>\n</rag_context>\n".length

        for (result in results) {
            val filePath = result.payload["filePath"] ?: continue
            val startLine = result.payload["startLine"] ?: ""
            val endLine = result.payload["endLine"] ?: ""
            val symbolName = result.payload["symbolName"]?.takeIf { it.isNotEmpty() }
            val content = result.payload["content"] ?: continue

            // Build relative path from project base
            val relativePath = project.basePath?.let { base ->
                if (filePath.startsWith(base)) filePath.removePrefix(base).removePrefix("/")
                else filePath
            } ?: filePath

            val entry = buildString {
                append("<code_context file=\"$relativePath\"")
                if (startLine.isNotEmpty() && endLine.isNotEmpty()) {
                    append(" lines=\"$startLine-$endLine\"")
                }
                if (symbolName != null) {
                    append(" symbol=\"$symbolName\"")
                }
                appendLine(">")
                appendLine(content)
                appendLine("</code_context>")
            }

            if (totalChars + entry.length > MAX_CONTEXT_CHARS) break

            sb.append(entry)
            totalChars += entry.length
        }

        sb.appendLine("</rag_context>")
        return sb.toString()
    }

    override fun dispose() {}
}
