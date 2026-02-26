package com.citigroup.copilotchat.tools

import com.citigroup.copilotchat.config.StoragePaths
import com.citigroup.copilotchat.rag.EmbeddingsProvider
import com.citigroup.copilotchat.rag.LocalEmbeddings
import com.citigroup.copilotchat.rag.VectorPoint
import com.citigroup.copilotchat.rag.VectorStore
import com.citigroup.copilotchat.tools.BuiltInToolUtils.OUTPUT_LIMIT
import com.citigroup.copilotchat.tools.BuiltInToolUtils.str
import com.citigroup.copilotchat.tools.BuiltInToolUtils.int
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object MemoryTools : ToolGroup {

    private val log = Logger.getInstance(MemoryTools::class.java)
    private val memoryJson = Json { ignoreUnknownKeys = true }
    private val embeddings: EmbeddingsProvider = LocalEmbeddings
    private const val MEMORIES_COLLECTION = "copilot-memories"

    override val schemas: List<String> = listOf(
        """{"name":"semantic_search","description":"Search the project's code index for relevant code snippets using semantic similarity. Returns matching code chunks ranked by relevance. The project must be indexed first (via Memory tab).","inputSchema":{"type":"object","properties":{"query":{"type":"string","description":"Natural language query describing what code you're looking for."},"topK":{"type":"number","description":"Maximum number of results to return. Default: 5."}},"required":["query"]}}""",
        """{"name":"remember","description":"Store a fact or piece of knowledge for persistent cross-session recall. Facts are embedded into the vector store and can be retrieved later with the recall tool.","inputSchema":{"type":"object","properties":{"fact":{"type":"string","description":"The fact, procedure, or insight to remember."},"category":{"type":"string","description":"Category: 'semantic' (facts about code/architecture), 'procedural' (how-to steps), 'failure' (mistakes/anti-patterns), 'general' (default).","enum":["semantic","procedural","failure","general"]}},"required":["fact"]}}""",
        """{"name":"recall","description":"Retrieve stored knowledge by topic. Uses both semantic similarity and keyword matching to find relevant memories saved with the remember tool.","inputSchema":{"type":"object","properties":{"topic":{"type":"string","description":"The topic or question to search memories for."},"category":{"type":"string","description":"Optional: filter results to a specific category.","enum":["semantic","procedural","failure","general"]}},"required":["topic"]}}""",
    )

    override val executors: Map<String, (ToolInvocationRequest) -> String> = mapOf(
        "semantic_search" to ::executeSemanticSearch,
        "remember" to ::executeRemember,
        "recall" to ::executeRecall,
    )

    private fun executeSemanticSearch(request: ToolInvocationRequest): String {
        val input = request.input
        val ws = request.workspaceRoot
        val query = input.str("query") ?: return "Error: query is required"
        val topK = input.int("topK") ?: 5

        return try {
            val store = VectorStore.getInstance()
            val collection = projectCollectionName(ws)
            store.ensureCollection(collection, embeddings.vectorDimension())

            val queryVector = embeddings.embed(query)
            val results = store.search(collection, queryVector, topK, 0.25f)

            if (results.isEmpty()) return "No relevant code found for: $query"

            buildString {
                for (result in results) {
                    val filePath = result.payload["filePath"] ?: continue
                    val startLine = result.payload["startLine"] ?: ""
                    val endLine = result.payload["endLine"] ?: ""
                    val symbolName = result.payload["symbolName"]
                    val content = result.payload["content"] ?: continue

                    val relativePath = if (filePath.startsWith(ws))
                        filePath.removePrefix(ws).removePrefix("/") else filePath

                    append("--- $relativePath")
                    if (startLine.isNotEmpty()) append(":$startLine-$endLine")
                    if (!symbolName.isNullOrEmpty()) append(" ($symbolName)")
                    appendLine(" [score: ${"%.2f".format(result.score)}] ---")
                    appendLine(content)
                    appendLine()
                }
            }.take(OUTPUT_LIMIT)
        } catch (e: Exception) {
            "Error: semantic search failed: ${e.message}"
        }
    }

    private fun executeRemember(request: ToolInvocationRequest): String {
        val input = request.input
        val fact = input.str("fact") ?: return "Error: fact is required"
        val category = input.str("category") ?: "general"

        val memDir = StoragePaths.memories()
        memDir.mkdirs()

        val entry = buildJsonObject {
            put("fact", fact)
            put("category", category)
            put("timestamp", System.currentTimeMillis().toString())
        }
        val file = File(memDir, "$category.jsonl")
        file.appendText(memoryJson.encodeToString(JsonObject.serializer(), entry) + "\n")

        try {
            val store = VectorStore.getInstance()
            val collection = MEMORIES_COLLECTION
            store.ensureCollection(collection, embeddings.vectorDimension())

            val vector = embeddings.embed(fact)
            val point = VectorPoint(
                id = UUID.randomUUID().toString(),
                vector = vector,
                payload = mapOf(
                    "fact" to fact,
                    "category" to category,
                    "timestamp" to System.currentTimeMillis().toString(),
                ),
            )
            store.upsertPoints(collection, listOf(point))
            store.save(collection)
        } catch (e: Exception) {
            log.warn("Failed to embed memory (file storage still succeeded): ${e.message}")
        }

        return "Remembered [$category]: $fact"
    }

    private fun executeRecall(request: ToolInvocationRequest): String {
        val input = request.input
        val topic = input.str("topic") ?: return "Error: topic is required"
        val category = input.str("category")

        val sections = mutableListOf<String>()

        try {
            val store = VectorStore.getInstance()
            val collection = MEMORIES_COLLECTION
            store.ensureCollection(collection, embeddings.vectorDimension())

            val queryVector = embeddings.embed(topic)
            val vectorResults = store.search(collection, queryVector, 10, 0.3f)
                .filter { category == null || it.payload["category"] == category }

            if (vectorResults.isNotEmpty()) {
                sections.add("=== Semantic matches ===")
                for (r in vectorResults) {
                    val cat = r.payload["category"] ?: "general"
                    sections.add("[$cat] (score: ${"%.2f".format(r.score)}) ${r.payload["fact"]}")
                }
            }
        } catch (e: Exception) {
            log.warn("Semantic recall failed: ${e.message}")
        }

        val memDir = StoragePaths.memories()
        if (memDir.isDirectory) {
            val files = if (category != null) {
                listOfNotNull(File(memDir, "$category.jsonl").takeIf { it.exists() })
            } else {
                memDir.listFiles()?.filter { it.extension == "jsonl" }?.toList() ?: emptyList()
            }

            val keywordMatches = mutableListOf<String>()
            val topicLower = topic.lowercase()
            for (f in files) {
                for (line in f.readLines()) {
                    if (topicLower in line.lowercase()) {
                        try {
                            val obj = memoryJson.decodeFromString<JsonObject>(line)
                            val fact = obj["fact"]?.jsonPrimitive?.contentOrNull ?: continue
                            val cat = obj["category"]?.jsonPrimitive?.contentOrNull ?: "general"
                            keywordMatches.add("[$cat] $fact")
                        } catch (_: Exception) {}
                    }
                }
            }

            if (keywordMatches.isNotEmpty()) {
                sections.add("=== Keyword matches ===")
                sections.addAll(keywordMatches.distinct())
            }
        }

        return if (sections.isEmpty()) "No memories found for: $topic"
        else sections.joinToString("\n").take(OUTPUT_LIMIT)
    }

    private fun projectCollectionName(ws: String): String {
        val projectName = File(ws).name
        val hash = md5(projectName).take(8)
        return "copilot-chat-$hash"
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
