package com.citigroup.copilotchat.rag

import com.citigroup.copilotchat.config.CopilotChatSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Manages a bundled Qdrant binary for local vector storage.
 * Follows the PlaywrightManager pattern: download binary, manage process lifecycle, expose REST API.
 *
 * Binary stored at `~/.copilot-chat/qdrant/`.
 * REST API on port 6333, gRPC on port 6334.
 */
@Service(Service.Level.APP)
class QdrantManager : Disposable {

    private val log = Logger.getInstance(QdrantManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val home = File(System.getProperty("user.home"), ".copilot-chat/qdrant")
    private val storagePath = File(home, "storage")
    private val configPath = File(home, "config.yaml")
    private val binaryPath: File get() = File(home, if (SystemInfo.isWindows) "qdrant.exe" else "qdrant")

    private var process: Process? = null

    companion object {
        private const val HTTP_PORT = 6333
        private const val GRPC_PORT = 6334
        private const val QDRANT_VERSION = "v1.13.2"
        private const val HEALTH_URL = "http://localhost:$HTTP_PORT/healthz"
        private const val BASE_URL = "http://localhost:$HTTP_PORT"
        private const val MAX_HEALTH_RETRIES = 30
        private const val HEALTH_RETRY_DELAY_MS = 500L

        fun getInstance(): QdrantManager =
            com.intellij.openapi.application.ApplicationManager.getApplication().getService(QdrantManager::class.java)
    }

    var onStatus: ((String) -> Unit)? = null

    val isInstalled: Boolean get() = binaryPath.exists() && binaryPath.canExecute()

    val isRunning: Boolean get() = isHealthy()

    /**
     * Ensure Qdrant is downloaded and running. Returns true on success.
     */
    @Synchronized
    fun ensureRunning(): Boolean {
        if (isRunning) return true

        if (!isInstalled) {
            onStatus?.invoke("Downloading Qdrant...")
            if (!downloadBinary()) return false
        }

        onStatus?.invoke("Starting Qdrant...")
        if (!startProcess()) return false

        onStatus?.invoke("Waiting for Qdrant to be ready...")
        if (!waitForHealthy()) {
            log.warn("Qdrant failed to become healthy")
            onStatus?.invoke("Qdrant failed to start.")
            stop()
            return false
        }

        onStatus?.invoke("")
        log.info("Qdrant is running on port $HTTP_PORT")
        return true
    }

    private fun downloadBinary(): Boolean {
        home.mkdirs()

        val (os, arch) = detectPlatform()
        val fileName = buildDownloadFileName(os, arch)
        val downloadUrl = "https://github.com/qdrant/qdrant/releases/download/$QDRANT_VERSION/$fileName"

        log.info("Downloading Qdrant from $downloadUrl")
        onStatus?.invoke("Downloading Qdrant $QDRANT_VERSION...")

        try {
            val client = buildExternalHttpClient()
            val request = HttpRequest.newBuilder()
                .uri(URI(downloadUrl))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build()

            val tarFile = File(home, fileName)
            val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tarFile.toPath()))

            if (response.statusCode() != 200) {
                log.warn("Qdrant download failed: HTTP ${response.statusCode()}")
                onStatus?.invoke("Failed to download Qdrant.")
                return false
            }

            // Extract binary from tar.gz
            val extractResult = if (SystemInfo.isWindows) {
                extractWindows(tarFile)
            } else {
                extractUnix(tarFile)
            }

            tarFile.delete()

            if (!extractResult) {
                onStatus?.invoke("Failed to extract Qdrant binary.")
                return false
            }

            if (!SystemInfo.isWindows) {
                binaryPath.setExecutable(true)
            }

            log.info("Qdrant binary installed at ${binaryPath.absolutePath}")
            return binaryPath.exists()
        } catch (e: Exception) {
            log.warn("Failed to download Qdrant: ${e.message}", e)
            onStatus?.invoke("Failed to download Qdrant: ${e.message}")
            return false
        }
    }

    private fun detectPlatform(): Pair<String, String> {
        val osName = System.getProperty("os.name").lowercase()
        val archName = System.getProperty("os.arch").lowercase()

        val os = when {
            osName.contains("mac") || osName.contains("darwin") -> "darwin"
            osName.contains("linux") -> "linux"
            osName.contains("win") -> "windows"
            else -> throw RuntimeException("Unsupported OS: $osName")
        }

        val arch = when {
            archName == "aarch64" || archName == "arm64" -> "aarch64"
            archName == "amd64" || archName == "x86_64" -> "x86_64"
            else -> throw RuntimeException("Unsupported architecture: $archName")
        }

        return Pair(os, arch)
    }

    private fun buildDownloadFileName(os: String, arch: String): String {
        // Qdrant release naming: qdrant-<arch>-apple-darwin.tar.gz / qdrant-<arch>-unknown-linux-gnu.tar.gz
        return when (os) {
            "darwin" -> "qdrant-$arch-apple-darwin.tar.gz"
            "linux" -> "qdrant-$arch-unknown-linux-gnu.tar.gz"
            "windows" -> "qdrant-$arch-pc-windows-msvc.zip"
            else -> throw RuntimeException("Unsupported OS: $os")
        }
    }

    private fun extractUnix(tarFile: File): Boolean {
        val proc = ProcessBuilder("tar", "xzf", tarFile.absolutePath, "-C", home.absolutePath)
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return proc.exitValue() == 0
    }

    private fun extractWindows(zipFile: File): Boolean {
        val proc = ProcessBuilder("powershell", "-Command",
            "Expand-Archive -Path '${zipFile.absolutePath}' -DestinationPath '${home.absolutePath}' -Force")
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return proc.exitValue() == 0
    }

    private fun startProcess(): Boolean {
        storagePath.mkdirs()
        writeConfig()

        val env = buildProcessEnv()
        val cmd = listOf(
            binaryPath.absolutePath,
            "--config-path", configPath.absolutePath,
        )

        return try {
            val pb = ProcessBuilder(cmd)
                .directory(home)
                .redirectErrorStream(true)
                .apply { environment().putAll(env) }

            val p = pb.start()
            process = p

            // Drain output in background to prevent buffer blocking
            Thread({
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        log.debug("qdrant: $line")
                    }
                } catch (_: Exception) {}
            }, "qdrant-output-drain").apply { isDaemon = true }.start()

            true
        } catch (e: Exception) {
            log.warn("Failed to start Qdrant: ${e.message}", e)
            false
        }
    }

    private fun writeConfig() {
        val yaml = """
            |storage:
            |  storage_path: ${storagePath.absolutePath}
            |service:
            |  http_port: $HTTP_PORT
            |  grpc_port: $GRPC_PORT
            |telemetry_disabled: true
        """.trimMargin()
        configPath.writeText(yaml)
    }

    private fun waitForHealthy(): Boolean {
        for (i in 0 until MAX_HEALTH_RETRIES) {
            if (isHealthy()) return true
            Thread.sleep(HEALTH_RETRY_DELAY_MS)
        }
        return false
    }

    private fun isHealthy(): Boolean {
        return try {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build()
            val request = HttpRequest.newBuilder()
                .uri(URI(HEALTH_URL))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build()
            val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
            resp.statusCode() == 200
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        process?.let { p ->
            log.info("Stopping Qdrant...")
            p.destroy()
            try {
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {}
            if (p.isAlive) p.destroyForcibly()
        }
        process = null
    }

    // ── Collection management ──

    fun ensureCollection(name: String, vectorSize: Int = 1536) {
        val client = buildHttpClient()

        // Check if collection exists
        val checkReq = HttpRequest.newBuilder()
            .uri(URI("$BASE_URL/collections/$name"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()

        val checkResp = client.send(checkReq, HttpResponse.BodyHandlers.ofString())
        if (checkResp.statusCode() == 200) return // already exists

        // Create collection
        val createBody = buildJsonObject {
            putJsonObject("vectors") {
                put("size", vectorSize)
                put("distance", "Cosine")
            }
        }

        val createReq = HttpRequest.newBuilder()
            .uri(URI("$BASE_URL/collections/$name"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .PUT(HttpRequest.BodyPublishers.ofString(createBody.toString()))
            .build()

        val createResp = client.send(createReq, HttpResponse.BodyHandlers.ofString())
        if (createResp.statusCode() !in 200..299) {
            throw RuntimeException("Failed to create Qdrant collection '$name': ${createResp.body().take(200)}")
        }
        log.info("Created Qdrant collection '$name' (dim=$vectorSize)")
    }

    // ── Point operations ──

    /**
     * Upsert points into a collection.
     * Each point: id (UUID string), vector (FloatArray), payload (Map).
     */
    fun upsertPoints(collection: String, points: List<QdrantPoint>) {
        if (points.isEmpty()) return
        val client = buildHttpClient()

        val body = buildJsonObject {
            putJsonArray("points") {
                for (point in points) {
                    addJsonObject {
                        put("id", point.id)
                        putJsonArray("vector") {
                            point.vector.forEach { add(it) }
                        }
                        putJsonObject("payload") {
                            for ((k, v) in point.payload) {
                                put(k, v)
                            }
                        }
                    }
                }
            }
        }

        val request = HttpRequest.newBuilder()
            .uri(URI("$BASE_URL/collections/$collection/points?wait=true"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw RuntimeException("Qdrant upsert failed: ${resp.body().take(200)}")
        }
    }

    /**
     * Search for similar vectors in a collection.
     */
    fun search(collection: String, vector: FloatArray, topK: Int = 5, scoreThreshold: Float = 0.3f): List<QdrantSearchResult> {
        val client = buildHttpClient()

        val body = buildJsonObject {
            putJsonArray("vector") {
                vector.forEach { add(it) }
            }
            put("limit", topK)
            put("score_threshold", scoreThreshold)
            put("with_payload", true)
        }

        val request = HttpRequest.newBuilder()
            .uri(URI("$BASE_URL/collections/$collection/points/search"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw RuntimeException("Qdrant search failed: ${resp.body().take(200)}")
        }

        val result = json.parseToJsonElement(resp.body()).jsonObject
        val hits = result["result"]?.jsonArray ?: return emptyList()

        return hits.map { hit ->
            val obj = hit.jsonObject
            val score = obj["score"]?.jsonPrimitive?.floatOrNull ?: 0f
            val payload = obj["payload"]?.jsonObject?.let { p ->
                p.entries.associate { (k, v) -> k to (v.jsonPrimitive.contentOrNull ?: v.toString()) }
            } ?: emptyMap()
            QdrantSearchResult(
                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                score = score,
                payload = payload,
            )
        }
    }

    /**
     * Delete points by their IDs.
     */
    fun deletePoints(collection: String, ids: List<String>) {
        if (ids.isEmpty()) return
        val client = buildHttpClient()

        val body = buildJsonObject {
            putJsonObject("points") {
                putJsonArray("ids") { // Qdrant expects this structure for delete by IDs
                    ids.forEach { add(it) }
                }
            }
        }

        // Use the filter-based delete for string IDs
        val deleteBody = buildJsonObject {
            putJsonArray("points") {
                ids.forEach { add(it) }
            }
        }

        val request = HttpRequest.newBuilder()
            .uri(URI("$BASE_URL/collections/$collection/points/delete?wait=true"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(deleteBody.toString()))
            .build()

        val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            log.warn("Qdrant delete failed: ${resp.body().take(200)}")
        }
    }

    /**
     * Scroll all points in a collection, returning their IDs and payloads.
     * Used for incremental indexing (checking file hashes).
     */
    fun scrollAll(collection: String, limit: Int = 100): List<QdrantScrollResult> {
        val client = buildHttpClient()
        val results = mutableListOf<QdrantScrollResult>()
        var offset: String? = null

        do {
            val body = buildJsonObject {
                put("limit", limit)
                put("with_payload", true)
                if (offset != null) {
                    put("offset", offset)
                }
            }

            val request = HttpRequest.newBuilder()
                .uri(URI("$BASE_URL/collections/$collection/points/scroll"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()

            val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) break

            val result = json.parseToJsonElement(resp.body()).jsonObject["result"]?.jsonObject ?: break
            val points = result["points"]?.jsonArray ?: break
            val nextOffset = result["next_page_offset"]?.jsonPrimitive?.contentOrNull

            for (point in points) {
                val obj = point.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val payload = obj["payload"]?.jsonObject?.let { p ->
                    p.entries.associate { (k, v) -> k to (v.jsonPrimitive.contentOrNull ?: v.toString()) }
                } ?: emptyMap()
                results.add(QdrantScrollResult(id = id, payload = payload))
            }

            offset = nextOffset
        } while (offset != null)

        return results
    }

    /** HTTP client for local Qdrant REST API calls — never uses proxy. */
    private fun buildHttpClient(): HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    /** HTTP client for external calls (GitHub releases download) — uses IDE or plugin proxy. */
    private fun buildExternalHttpClient(): HttpClient {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
        configureProxy(builder)
        return builder.build()
    }

    /**
     * Configure proxy on an HttpClient builder.
     * Priority: IDE proxy settings (Settings → HTTP Proxy) → plugin proxyUrl setting.
     */
    private fun configureProxy(builder: HttpClient.Builder) {
        // Try IDE's built-in proxy settings first
        try {
            val ideProxy = com.intellij.util.net.HttpConfigurable.getInstance()
            if (ideProxy.USE_HTTP_PROXY && ideProxy.PROXY_HOST.isNotBlank()) {
                builder.proxy(java.net.ProxySelector.of(
                    java.net.InetSocketAddress(ideProxy.PROXY_HOST, ideProxy.PROXY_PORT)
                ))
                val login = ideProxy.proxyLogin
                val password = ideProxy.plainProxyPassword
                if (!login.isNullOrBlank()) {
                    builder.authenticator(object : java.net.Authenticator() {
                        override fun getPasswordAuthentication(): java.net.PasswordAuthentication =
                            java.net.PasswordAuthentication(login, (password ?: "").toCharArray())
                    })
                }
                return
            }
        } catch (_: Exception) {}

        // Fall back to plugin's proxyUrl setting
        val proxyUrl = CopilotChatSettings.getInstance().proxyUrl
        if (proxyUrl.isNotBlank()) {
            try {
                val uri = URI(proxyUrl)
                builder.proxy(java.net.ProxySelector.of(java.net.InetSocketAddress(uri.host, uri.port)))
                val userInfo = uri.userInfo
                if (userInfo != null) {
                    val parts = userInfo.split(":", limit = 2)
                    val username = parts[0]
                    val password = parts.getOrElse(1) { "" }
                    builder.authenticator(object : java.net.Authenticator() {
                        override fun getPasswordAuthentication(): java.net.PasswordAuthentication =
                            java.net.PasswordAuthentication(username, password.toCharArray())
                    })
                }
            } catch (_: Exception) {}
        }
    }

    private fun buildProcessEnv(): MutableMap<String, String> =
        System.getenv().toMutableMap()

    override fun dispose() {
        stop()
    }
}

data class QdrantPoint(
    val id: String,
    val vector: FloatArray,
    val payload: Map<String, String>,
)

data class QdrantSearchResult(
    val id: String,
    val score: Float,
    val payload: Map<String, String>,
)

data class QdrantScrollResult(
    val id: String,
    val payload: Map<String, String>,
)
