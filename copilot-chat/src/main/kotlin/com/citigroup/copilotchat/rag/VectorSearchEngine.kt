package com.citigroup.copilotchat.rag

/**
 * Abstraction over vector storage and similarity search.
 *
 * [VectorStore] is the default in-memory/JSON-file implementation.
 * This interface allows swapping to an external vector database
 * without changing callers.
 */
interface VectorSearchEngine {
    fun ensureCollection(name: String, vectorDim: Int = 384)
    fun upsertPoints(collection: String, points: List<VectorPoint>)
    fun search(collection: String, query: FloatArray, topK: Int = 5, scoreThreshold: Float = 0.25f): List<VectorSearchResult>
    fun deletePoints(collection: String, ids: List<String>)
    fun deleteByPayload(collection: String, key: String, value: String)
    fun scrollAll(collection: String): List<VectorScrollResult>
    fun save(collection: String)
    fun saveAll()
}
