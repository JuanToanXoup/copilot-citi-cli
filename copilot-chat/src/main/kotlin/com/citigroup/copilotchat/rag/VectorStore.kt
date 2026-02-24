package com.citigroup.copilotchat.rag

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Pure Kotlin in-memory vector store with JSON file persistence.
 *
 * Replaces Qdrant — no external binary, no ports, no process management.
 * Each collection is a list of points held in memory and persisted to
 * `~/.copilot-chat/vector-store/<collection>.json`.
 *
 * Search is brute-force cosine similarity — fast enough for project-scale
 * indexes (< 50K vectors, < 1ms search time at 384 dims).
 *
 * Thread-safe: per-collection synchronized access.
 */
@Service(Service.Level.APP)
class VectorStore : VectorSearchEngine, Disposable {

    private val log = Logger.getInstance(VectorStore::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val storeDir = File(System.getProperty("user.home"), ".copilot-chat/vector-store")
    private val collections = ConcurrentHashMap<String, MutableList<StoredPoint>>()
    private val dirty = ConcurrentHashMap<String, Boolean>()

    companion object {
        fun getInstance(): VectorStore =
            com.intellij.openapi.application.ApplicationManager.getApplication().getService(VectorStore::class.java)
    }

    /**
     * Ensure a collection exists. Loads from disk if a persisted file is found.
     * If the existing collection has a different vector dimension, clears it.
     */
    override fun ensureCollection(name: String, vectorDim: Int) {
        if (collections.containsKey(name)) {
            // Verify dimension matches
            val points = collections[name]!!
            synchronized(points) {
                if (points.isNotEmpty() && points.first().vector.size != vectorDim) {
                    log.info("Collection '$name' has wrong dimension (${points.first().vector.size} vs $vectorDim), clearing")
                    points.clear()
                    dirty[name] = true
                }
            }
            return
        }

        val file = File(storeDir, "$name.json")
        if (file.exists()) {
            try {
                val loaded = json.decodeFromString<List<StoredPoint>>(file.readText())
                if (loaded.isNotEmpty() && loaded.first().vector.size != vectorDim) {
                    log.info("Persisted collection '$name' has wrong dimension, starting fresh")
                    collections[name] = mutableListOf()
                } else {
                    collections[name] = loaded.toMutableList()
                    log.info("Loaded collection '$name' from disk: ${loaded.size} points")
                }
            } catch (e: Exception) {
                log.warn("Failed to load collection '$name' from disk: ${e.message}")
                collections[name] = mutableListOf()
            }
        } else {
            collections[name] = mutableListOf()
            log.info("Created new collection '$name'")
        }
    }

    /**
     * Upsert points into a collection. Points with existing IDs are replaced.
     */
    override fun upsertPoints(collection: String, points: List<VectorPoint>) {
        if (points.isEmpty()) return
        val list = getCollection(collection)
        synchronized(list) {
            val existingIds = points.map { it.id }.toSet()
            list.removeAll { it.id in existingIds }
            for (point in points) {
                list.add(StoredPoint(
                    id = point.id,
                    vector = point.vector.toList(),
                    payload = point.payload,
                ))
            }
        }
        dirty[collection] = true
    }

    /**
     * Search for similar vectors using brute-force cosine similarity.
     */
    override fun search(collection: String, query: FloatArray, topK: Int, scoreThreshold: Float): List<VectorSearchResult> {
        val list = getCollection(collection)
        val results: List<VectorSearchResult>
        synchronized(list) {
            results = list.mapNotNull { point ->
                val score = cosineSimilarity(query, point.vector)
                if (score >= scoreThreshold) VectorSearchResult(point.id, score, point.payload) else null
            }
        }
        return results.sortedByDescending { it.score }.take(topK)
    }

    /**
     * Delete points by their IDs.
     */
    override fun deletePoints(collection: String, ids: List<String>) {
        if (ids.isEmpty()) return
        val idSet = ids.toSet()
        val list = getCollection(collection)
        synchronized(list) {
            list.removeAll { it.id in idSet }
        }
        dirty[collection] = true
    }

    /**
     * Delete points where a payload field matches a value.
     */
    override fun deleteByPayload(collection: String, key: String, value: String) {
        val list = getCollection(collection)
        synchronized(list) {
            list.removeAll { it.payload[key] == value }
        }
        dirty[collection] = true
    }

    /**
     * Return all points in a collection (IDs and payloads, no vectors).
     */
    override fun scrollAll(collection: String): List<VectorScrollResult> {
        val list = getCollection(collection)
        synchronized(list) {
            return list.map { VectorScrollResult(it.id, it.payload) }
        }
    }

    /**
     * Persist a collection to disk. Call after bulk operations (e.g. end of indexing).
     */
    override fun save(collection: String) {
        if (dirty[collection] != true) return
        val list = getCollection(collection)
        val snapshot: List<StoredPoint>
        synchronized(list) {
            snapshot = list.toList()
        }

        try {
            storeDir.mkdirs()
            val file = File(storeDir, "$collection.json")
            file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(StoredPoint.serializer()), snapshot))
            dirty[collection] = false
            log.info("Saved collection '$collection': ${snapshot.size} points (${file.length() / 1024}KB)")
        } catch (e: Exception) {
            log.warn("Failed to save collection '$collection': ${e.message}", e)
        }
    }

    /** Persist all dirty collections. */
    override fun saveAll() {
        for (name in dirty.keys) {
            save(name)
        }
    }

    private fun getCollection(name: String): MutableList<StoredPoint> {
        return collections[name] ?: run {
            ensureCollection(name)
            collections[name]!!
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: List<Float>): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }

    override fun dispose() {
        saveAll()
    }
}

data class VectorPoint(
    val id: String,
    val vector: FloatArray,
    val payload: Map<String, String>,
)

data class VectorSearchResult(
    val id: String,
    val score: Float,
    val payload: Map<String, String>,
)

data class VectorScrollResult(
    val id: String,
    val payload: Map<String, String>,
)

@Serializable
data class StoredPoint(
    val id: String,
    val vector: List<Float>,
    val payload: Map<String, String>,
)
