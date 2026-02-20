package com.citigroup.copilotchat.rag

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

/**
 * Background indexing service that ties together [PsiChunker], [CopilotEmbeddings], and [QdrantManager].
 *
 * On `indexProject()`:
 *   1. Scans all project source files (respects excludes)
 *   2. Chunks each file via PSI
 *   3. Embeds chunks via Copilot API
 *   4. Upserts vectors into Qdrant
 *
 * Incremental: stores content MD5 hash in Qdrant payload, skips unchanged files.
 */
@Service(Service.Level.PROJECT)
class RagIndexer(private val project: Project) : Disposable {

    private val log = Logger.getInstance(RagIndexer::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var indexingJob: Job? = null

    @Volatile var isIndexing: Boolean = false
        private set
    @Volatile var indexedFiles: Int = 0
        private set
    @Volatile var totalFiles: Int = 0
        private set
    @Volatile var skippedFiles: Int = 0
        private set
    @Volatile var lastError: String? = null
        private set

    companion object {
        private const val UPSERT_BATCH_SIZE = 20
        private val EXCLUDED_DIRS = setOf(
            "build", "out", "target", "node_modules", ".gradle", ".idea",
            ".git", "dist", "vendor", "__pycache__", ".venv", "venv",
            ".next", ".nuxt", "coverage", ".cache",
        )
        private val SUPPORTED_EXTENSIONS = setOf(
            "kt", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs",
            "rb", "php", "swift", "scala", "groovy", "c", "cpp", "h", "hpp",
            "cs", "m", "mm", "vue", "svelte", "dart", "lua", "r",
            "sql", "graphql", "proto", "toml", "yaml", "yml", "json",
            "xml", "html", "css", "scss", "less", "sh", "bash", "zsh",
            "md", "txt", "gradle", "kts",
        )
        private const val MAX_FILE_SIZE_BYTES = 500_000L // 500KB

        fun getInstance(project: Project): RagIndexer =
            project.getService(RagIndexer::class.java)
    }

    /** Collection name unique to this project. */
    fun collectionName(): String {
        val projectName = project.name
        val hash = md5(projectName).take(8)
        return "copilot-chat-$hash"
    }

    /**
     * Start full project indexing in background.
     */
    fun indexProject() {
        if (isIndexing) {
            log.info("Indexing already in progress")
            return
        }

        indexingJob?.cancel()
        indexingJob = scope.launch {
            try {
                isIndexing = true
                indexedFiles = 0
                skippedFiles = 0
                lastError = null
                log.info("Starting RAG indexing for project: ${project.name}")

                // Ensure Qdrant is running
                val qdrant = QdrantManager.getInstance()
                if (!qdrant.ensureRunning()) {
                    log.warn("Failed to start Qdrant, aborting indexing")
                    lastError = "Failed to start Qdrant. Check that the binary can be downloaded and ports 6333/6334 are available."
                    return@launch
                }

                val collection = collectionName()
                qdrant.ensureCollection(collection, CopilotEmbeddings.vectorDimension())

                // Gather existing file hashes from Qdrant for incremental indexing
                val existingPoints = try {
                    qdrant.scrollAll(collection)
                } catch (e: Exception) {
                    log.warn("Could not scroll existing points: ${e.message}", e)
                    emptyList()
                }
                log.info("RAG: found ${existingPoints.size} existing points in collection '$collection'")
                if (existingPoints.isNotEmpty()) {
                    val sample = existingPoints.first()
                    log.info("RAG: sample point — id=${sample.id}, payload keys=${sample.payload.keys}, filePath=${sample.payload["filePath"]?.take(80)}, hash=${sample.payload["contentHash"]?.take(10)}")
                }
                val existingHashes = existingPoints.associate {
                    it.payload["filePath"].orEmpty() to it.payload["contentHash"].orEmpty()
                }
                log.info("RAG: ${existingHashes.size} unique file hashes loaded (${existingHashes.values.count { it.isNotEmpty() }} with non-empty hash)")
                val existingPointsByFile = existingPoints.groupBy { it.payload["filePath"].orEmpty() }

                // Collect project files
                val files = collectProjectFiles()
                totalFiles = files.size
                log.info("Found $totalFiles files to index")

                val currentFilePaths = mutableSetOf<String>()

                for (file in files) {
                    if (!isActive) break

                    try {
                        val reindexed = indexFile(file, collection, existingHashes)
                        currentFilePaths.add(file.path)
                        if (!reindexed) skippedFiles++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.debug("Failed to index ${file.path}: ${e.message}")
                    }

                    indexedFiles++
                }

                // Cleanup: remove points for deleted files
                val deletedFilePaths = existingPointsByFile.keys - currentFilePaths
                for (deletedPath in deletedFilePaths) {
                    val pointIds = existingPointsByFile[deletedPath]?.map { it.id } ?: continue
                    try {
                        qdrant.deletePoints(collection, pointIds)
                        log.debug("Removed ${pointIds.size} points for deleted file: $deletedPath")
                    } catch (e: Exception) {
                        log.debug("Failed to clean up points for $deletedPath: ${e.message}")
                    }
                }

                val reindexed = indexedFiles - skippedFiles
                log.info("RAG indexing complete: $indexedFiles files checked, $reindexed re-indexed, $skippedFiles unchanged")

            } catch (e: CancellationException) {
                log.info("RAG indexing cancelled")
            } catch (e: Exception) {
                log.warn("RAG indexing failed: ${e.message}", e)
                lastError = "Indexing failed: ${e.message}"
            } finally {
                isIndexing = false
            }
        }
    }

    /**
     * Index a single file. Skips if content hash hasn't changed.
     * Returns true if the file was actually re-indexed, false if skipped.
     */
    private suspend fun indexFile(
        virtualFile: VirtualFile,
        collection: String,
        existingHashes: Map<String, String>,
    ): Boolean {
        val filePath = virtualFile.path
        val content = ReadAction.compute<String?, Throwable> {
            try {
                String(virtualFile.contentsToByteArray(), Charsets.UTF_8)
            } catch (_: Exception) { null }
        } ?: return false

        val contentHash = md5(content)

        // Skip if unchanged
        if (existingHashes[filePath] == contentHash) return false

        // Chunk via PSI
        val chunks = ReadAction.compute<List<CodeChunk>, Throwable> {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@compute emptyList()
            PsiChunker.chunkFile(psiFile, project)
        }

        if (chunks.isEmpty()) return false

        // Embed all chunks
        val texts = chunks.map { chunk ->
            buildString {
                append("// File: ${chunk.filePath}")
                if (chunk.symbolName != null) append(" | Symbol: ${chunk.symbolName}")
                append("\n")
                append(chunk.content)
            }
        }

        val vectors = try {
            CopilotEmbeddings.embedBatch(texts)
        } catch (e: Exception) {
            log.debug("Embedding failed for $filePath: ${e.message}")
            return false
        }

        // Build Qdrant points
        val points = chunks.zip(vectors).map { (chunk, vector) ->
            QdrantPoint(
                id = UUID.randomUUID().toString(),
                vector = vector,
                payload = mapOf(
                    "filePath" to chunk.filePath,
                    "startLine" to chunk.startLine.toString(),
                    "endLine" to chunk.endLine.toString(),
                    "symbolName" to (chunk.symbolName ?: ""),
                    "content" to chunk.content.take(4000),
                    "contentHash" to contentHash,
                ),
            )
        }

        // Delete old points for this file first
        val qdrant = QdrantManager.getInstance()
        try {
            // Search for existing points with this filePath via scroll + filter
            deletePointsByFilePath(qdrant, collection, filePath)
        } catch (_: Exception) {}

        // Upsert new points in batches
        for (batch in points.chunked(UPSERT_BATCH_SIZE)) {
            qdrant.upsertPoints(collection, batch)
        }

        log.debug("Indexed $filePath: ${chunks.size} chunks")
        return true
    }

    /**
     * Re-index a single file (e.g., on save).
     */
    fun reindexFile(virtualFile: VirtualFile) {
        scope.launch {
            try {
                val qdrant = QdrantManager.getInstance()
                if (!qdrant.isRunning) return@launch

                indexFile(virtualFile, collectionName(), emptyMap())
            } catch (e: Exception) {
                log.debug("Failed to reindex ${virtualFile.path}: ${e.message}")
            }
        }
    }

    fun cancelIndexing() {
        indexingJob?.cancel()
        indexingJob = null
        isIndexing = false
    }

    private fun collectProjectFiles(): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()

        ApplicationManager.getApplication().runReadAction {
            val fileIndex = ProjectFileIndex.getInstance(project)
            fileIndex.iterateContent { file ->
                if (shouldIndexFile(file)) {
                    files.add(file)
                }
                true // continue iterating
            }
        }

        return files
    }

    private fun shouldIndexFile(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        if (!file.isValid) return false
        if (file.length > MAX_FILE_SIZE_BYTES) return false

        val extension = file.extension?.lowercase() ?: return false
        if (extension !in SUPPORTED_EXTENSIONS) return false

        // Check excluded directories
        var parent = file.parent
        while (parent != null) {
            if (parent.name in EXCLUDED_DIRS) return false
            parent = parent.parent
        }

        // Respect .gitignore and VCS ignore rules
        if (ChangeListManager.getInstance(project).isIgnoredFile(file)) return false

        return true
    }

    private fun deletePointsByFilePath(qdrant: QdrantManager, collection: String, filePath: String) {
        // Use Qdrant's filter-based delete via REST API
        val client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build()

        val body = buildJsonObject {
            putJsonObject("filter") {
                putJsonArray("must") {
                    addJsonObject {
                        put("key", "filePath")
                        putJsonObject("match") {
                            put("value", filePath)
                        }
                    }
                }
            }
        }

        val request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI("http://localhost:6333/collections/$collection/points/delete?wait=true"))
            .header("Content-Type", "application/json")
            .timeout(java.time.Duration.ofSeconds(10))
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun dispose() {
        cancelIndexing()
        scope.cancel()
    }
}
