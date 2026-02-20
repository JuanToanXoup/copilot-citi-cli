import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * Standalone integration test for the RAG pipeline.
 * Tests each layer independently without needing the IntelliJ runtime.
 *
 * Run: Right-click → Run 'RagIntegrationTestKt' in IntelliJ
 *   or: ./gradlew compileTestKotlin && java -cp <classpath> RagIntegrationTestKt
 *
 * Prerequisites:
 *   - Valid ghu_ token in ~/.config/github-copilot/apps.json
 *   - Internet access (for Copilot API + Qdrant download)
 *   - ~50MB disk for Qdrant binary
 *
 * Tests (in order):
 *   1. Token exchange — ghu_ → session token
 *   2. Embeddings API — embed sample text → 1536-dim vector
 *   3. Qdrant download — fetch binary for current platform
 *   4. Qdrant lifecycle — start, health check, stop
 *   5. Qdrant CRUD — create collection, upsert, search, delete
 *   6. Round-trip — embed text, store in Qdrant, query back
 */

private val json = Json { ignoreUnknownKeys = true }
private val client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(30))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

private const val QDRANT_VERSION = "v1.13.2"
private const val QDRANT_PORT = 6399 // Use non-default port to avoid conflicts
private val qdrantHome = File(System.getProperty("user.home"), ".copilot-chat/qdrant-test")
private val qdrantBinary = File(qdrantHome, if (System.getProperty("os.name").lowercase().contains("win")) "qdrant.exe" else "qdrant")
private val qdrantStorage = File(qdrantHome, "test-storage")

private var qdrantProcess: Process? = null
private var sessionToken: String? = null
private var testVector: FloatArray? = null

// ── Test runner ──

fun main() {
    println("╔══════════════════════════════════════════════════╗")
    println("║         RAG Integration Test Suite               ║")
    println("╚══════════════════════════════════════════════════╝")
    println()

    val results = mutableListOf<Pair<String, Boolean>>()

    try {
        results += "1. Read ghu_ token from apps.json" to runTest { testReadToken() }
        results += "2. Token exchange (ghu_ → session)" to runTest { testTokenExchange() }
        results += "3. Embeddings API (text → 1536-dim)" to runTest { testEmbeddings() }
        results += "4. Embeddings batch (3 texts)" to runTest { testEmbeddingsBatch() }
        results += "5. Qdrant binary download" to runTest { testQdrantDownload() }
        results += "6. Qdrant start + health check" to runTest { testQdrantStart() }
        results += "7. Qdrant collection CRUD" to runTest { testQdrantCollection() }
        results += "8. Qdrant upsert + search" to runTest { testQdrantUpsertSearch() }
        results += "9. Qdrant filter delete" to runTest { testQdrantDelete() }
        results += "10. Full round-trip (embed → store → query)" to runTest { testRoundTrip() }
    } finally {
        // Always clean up
        println("\n--- Cleanup ---")
        stopQdrant()
    }

    // Summary
    println()
    println("╔══════════════════════════════════════════════════╗")
    println("║                  Results                         ║")
    println("╠══════════════════════════════════════════════════╣")
    for ((name, passed) in results) {
        val icon = if (passed) "PASS" else "FAIL"
        val pad = name.padEnd(42)
        println("║ $pad $icon ║")
    }
    println("╚══════════════════════════════════════════════════╝")

    val passed = results.count { it.second }
    val failed = results.count { !it.second }
    println("\n$passed passed, $failed failed out of ${results.size} tests")

    if (failed > 0) {
        System.exit(1)
    }
}

private fun runTest(block: () -> Unit): Boolean {
    return try {
        block()
        true
    } catch (e: Exception) {
        System.err.println("  ERROR: ${e.message}")
        false
    }
}

// ── Test: Read token ──

private fun testReadToken() {
    print("  Reading apps.json... ")
    val token = readGhuToken()
    check(token.startsWith("ghu_")) { "Expected ghu_ token, got: ${token.take(10)}..." }
    println("OK (${token.take(8)}...)")
}

// ── Test: Token exchange ──

private fun testTokenExchange() {
    print("  Exchanging token... ")
    val ghu = readGhuToken()

    val request = HttpRequest.newBuilder()
        .uri(URI("https://api.github.com/copilot_internal/v2/token"))
        .header("Authorization", "token $ghu")
        .header("Accept", "application/json")
        .header("Editor-Version", "JetBrains-IC/2025.2")
        .header("Editor-Plugin-Version", "copilot-intellij/1.420.0")
        .GET()
        .timeout(Duration.ofSeconds(15))
        .build()

    val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
    check(resp.statusCode() == 200) { "Token exchange HTTP ${resp.statusCode()}: ${resp.body().take(200)}" }

    val body = json.parseToJsonElement(resp.body()).jsonObject
    val token = body["token"]?.jsonPrimitive?.contentOrNull
    check(token != null && token.isNotEmpty()) { "No 'token' field in response" }

    val expiresAt = body["expires_at"]?.jsonPrimitive?.longOrNull
    if (expiresAt != null) {
        val expiry = Instant.ofEpochSecond(expiresAt)
        println("OK (expires $expiry)")
    } else {
        println("OK (no expiry field)")
    }

    sessionToken = token
}

// ── Test: Embeddings ──

private fun testEmbeddings() {
    print("  Embedding single text... ")
    val token = sessionToken ?: error("No session token (test 2 must pass first)")

    val vectors = callEmbeddingsApi(token, listOf("fun findUserById(id: String): User?"))
    check(vectors.size == 1) { "Expected 1 vector, got ${vectors.size}" }
    check(vectors[0].size == 1536) { "Expected 1536 dimensions, got ${vectors[0].size}" }

    // Verify non-zero
    val nonZero = vectors[0].count { it != 0f }
    check(nonZero > 100) { "Vector seems mostly zeros ($nonZero non-zero values)" }

    testVector = vectors[0]
    println("OK (dim=${vectors[0].size}, nonzero=$nonZero)")
}

private fun testEmbeddingsBatch() {
    print("  Embedding batch of 3... ")
    val token = sessionToken ?: error("No session token")

    val texts = listOf(
        "class UserService { fun findById(id: String): User? }",
        "data class User(val id: String, val name: String, val email: String)",
        "interface UserRepository { fun save(user: User): User }",
    )

    val vectors = callEmbeddingsApi(token, texts)
    check(vectors.size == 3) { "Expected 3 vectors, got ${vectors.size}" }
    for ((i, v) in vectors.withIndex()) {
        check(v.size == 1536) { "Vector $i has ${v.size} dims, expected 1536" }
    }

    // Verify vectors are different from each other (not just returning same thing)
    val sim01 = cosineSimilarity(vectors[0], vectors[1])
    val sim02 = cosineSimilarity(vectors[0], vectors[2])
    check(sim01 < 0.99f) { "Vectors 0 and 1 are identical (sim=$sim01)" }
    println("OK (sim[0,1]=${"%.3f".format(sim01)}, sim[0,2]=${"%.3f".format(sim02)})")
}

// ── Test: Qdrant download ──

private fun testQdrantDownload() {
    if (qdrantBinary.exists() && qdrantBinary.canExecute()) {
        println("  Binary already exists at ${qdrantBinary.absolutePath}, skipping download")
        return
    }

    print("  Detecting platform... ")
    val osName = System.getProperty("os.name").lowercase()
    val archName = System.getProperty("os.arch").lowercase()

    val os = when {
        osName.contains("mac") || osName.contains("darwin") -> "darwin"
        osName.contains("linux") -> "linux"
        osName.contains("win") -> "windows"
        else -> error("Unsupported OS: $osName")
    }
    val arch = when {
        archName == "aarch64" || archName == "arm64" -> "aarch64"
        archName == "amd64" || archName == "x86_64" -> "x86_64"
        else -> error("Unsupported arch: $archName")
    }
    println("$os/$arch")

    val fileName = when (os) {
        "darwin" -> "qdrant-$arch-apple-darwin.tar.gz"
        "linux" -> "qdrant-$arch-unknown-linux-gnu.tar.gz"
        "windows" -> "qdrant-$arch-pc-windows-msvc.zip"
        else -> error("Unsupported")
    }
    val url = "https://github.com/qdrant/qdrant/releases/download/$QDRANT_VERSION/$fileName"

    print("  Downloading $fileName... ")
    qdrantHome.mkdirs()
    val tarFile = File(qdrantHome, fileName)

    val dlReq = HttpRequest.newBuilder()
        .uri(URI(url))
        .timeout(Duration.ofMinutes(5))
        .GET()
        .build()
    val dlResp = client.send(dlReq, HttpResponse.BodyHandlers.ofFile(tarFile.toPath()))
    check(dlResp.statusCode() == 200) { "Download failed: HTTP ${dlResp.statusCode()}" }
    println("OK (${tarFile.length() / 1024}KB)")

    print("  Extracting... ")
    if (os == "windows") {
        val proc = ProcessBuilder("powershell", "-Command",
            "Expand-Archive -Path '${tarFile.absolutePath}' -DestinationPath '${qdrantHome.absolutePath}' -Force")
            .redirectErrorStream(true).start()
        proc.waitFor()
        check(proc.exitValue() == 0) { "Extraction failed" }
    } else {
        val proc = ProcessBuilder("tar", "xzf", tarFile.absolutePath, "-C", qdrantHome.absolutePath)
            .redirectErrorStream(true).start()
        proc.waitFor()
        check(proc.exitValue() == 0) { "Extraction failed" }
        qdrantBinary.setExecutable(true)
    }
    tarFile.delete()

    check(qdrantBinary.exists()) { "Binary not found after extraction at ${qdrantBinary.absolutePath}" }
    check(qdrantBinary.canExecute()) { "Binary is not executable" }
    println("OK (${qdrantBinary.absolutePath})")
}

// ── Test: Qdrant start ──

private fun testQdrantStart() {
    print("  Starting Qdrant on port $QDRANT_PORT... ")
    qdrantStorage.mkdirs()

    // Qdrant uses a YAML config file, not CLI flags for port/storage
    val configFile = File(qdrantHome, "test-config.yaml")
    configFile.writeText("""
        |storage:
        |  storage_path: ${qdrantStorage.absolutePath}
        |service:
        |  http_port: $QDRANT_PORT
        |  grpc_port: ${QDRANT_PORT + 1}
        |telemetry_disabled: true
    """.trimMargin())

    val proc = ProcessBuilder(
        qdrantBinary.absolutePath,
        "--config-path", configFile.absolutePath,
    )
        .directory(qdrantHome)
        .redirectErrorStream(true)
        .start()

    qdrantProcess = proc

    // Drain output
    Thread({
        try { proc.inputStream.bufferedReader().forEachLine { /* discard */ } } catch (_: Exception) {}
    }, "qdrant-drain").apply { isDaemon = true }.start()

    // Wait for healthy
    var healthy = false
    for (i in 0 until 30) {
        Thread.sleep(500)
        if (isQdrantHealthy()) { healthy = true; break }
    }
    check(healthy) { "Qdrant did not become healthy within 15 seconds" }
    println("OK (healthy)")
}

// ── Test: Collection CRUD ──

private fun testQdrantCollection() {
    val collection = "rag-test-collection"

    // Create
    print("  Creating collection '$collection'... ")
    val createBody = buildJsonObject {
        putJsonObject("vectors") {
            put("size", 1536)
            put("distance", "Cosine")
        }
    }
    val createResp = qdrantRequest("PUT", "/collections/$collection", createBody.toString())
    check(createResp.statusCode() in 200..299) { "Create failed: ${createResp.body()}" }
    println("OK")

    // Verify exists
    print("  Checking collection exists... ")
    val getResp = qdrantRequest("GET", "/collections/$collection")
    check(getResp.statusCode() == 200) { "Collection not found: ${getResp.body()}" }
    val info = json.parseToJsonElement(getResp.body()).jsonObject
    val vectorSize = info["result"]?.jsonObject
        ?.get("config")?.jsonObject
        ?.get("params")?.jsonObject
        ?.get("vectors")?.jsonObject
        ?.get("size")?.jsonPrimitive?.intOrNull
    check(vectorSize == 1536) { "Vector size mismatch: expected 1536, got $vectorSize" }
    println("OK (size=$vectorSize)")

    // Delete collection
    print("  Deleting collection... ")
    val delResp = qdrantRequest("DELETE", "/collections/$collection")
    check(delResp.statusCode() in 200..299) { "Delete failed: ${delResp.body()}" }
    println("OK")
}

// ── Test: Upsert + Search ──

private fun testQdrantUpsertSearch() {
    val collection = "rag-test-search"
    val vector = testVector ?: error("No test vector (test 3 must pass first)")

    // Setup collection
    val createBody = buildJsonObject {
        putJsonObject("vectors") { put("size", 1536); put("distance", "Cosine") }
    }
    qdrantRequest("PUT", "/collections/$collection", createBody.toString())

    // Upsert 3 points
    print("  Upserting 3 points... ")
    val points = buildJsonObject {
        putJsonArray("points") {
            addJsonObject {
                put("id", 1)
                putJsonArray("vector") { vector.forEach { add(it) } }
                putJsonObject("payload") {
                    put("filePath", "src/UserService.kt")
                    put("content", "fun findById(id: String): User?")
                }
            }
            addJsonObject {
                put("id", 2)
                // Slightly different vector (shift first 10 values)
                putJsonArray("vector") {
                    val v2 = vector.copyOf()
                    for (i in 0 until 10) v2[i] += 0.5f
                    v2.forEach { add(it) }
                }
                putJsonObject("payload") {
                    put("filePath", "src/OrderService.kt")
                    put("content", "fun placeOrder(order: Order): OrderResult")
                }
            }
            addJsonObject {
                put("id", 3)
                // Very different vector (negate all)
                putJsonArray("vector") {
                    vector.map { -it }.forEach { add(it) }
                }
                putJsonObject("payload") {
                    put("filePath", "src/Config.kt")
                    put("content", "val DB_URL = \"jdbc:postgresql://localhost/mydb\"")
                }
            }
        }
    }
    val upsertResp = qdrantRequest("PUT", "/collections/$collection/points?wait=true", points.toString())
    check(upsertResp.statusCode() in 200..299) { "Upsert failed: ${upsertResp.body()}" }
    println("OK")

    // Search — should find point-1 as the best match
    print("  Searching for nearest vector... ")
    val searchBody = buildJsonObject {
        putJsonArray("vector") { vector.forEach { add(it) } }
        put("limit", 3)
        put("with_payload", true)
    }
    val searchResp = qdrantRequest("POST", "/collections/$collection/points/search", searchBody.toString())
    check(searchResp.statusCode() in 200..299) { "Search failed: ${searchResp.body()}" }

    val hits = json.parseToJsonElement(searchResp.body()).jsonObject["result"]?.jsonArray
    check(hits != null && hits.isNotEmpty()) { "No search results returned" }

    val topHit = hits[0].jsonObject
    val topId = topHit["id"]?.jsonPrimitive?.contentOrNull
    val topScore = topHit["score"]?.jsonPrimitive?.floatOrNull ?: 0f
    val topFile = topHit["payload"]?.jsonObject?.get("filePath")?.jsonPrimitive?.contentOrNull

    check(topId == "1") { "Expected point 1 as top result, got $topId" }
    check(topScore > 0.99f) { "Expected score ~1.0 for exact match, got $topScore" }
    println("OK (top=$topId, score=${"%.4f".format(topScore)}, file=$topFile)")

    // Verify ranking: point-2 should rank higher than point-3
    if (hits.size >= 3) {
        val secondId = hits[1].jsonObject["id"]?.jsonPrimitive?.contentOrNull
        val thirdScore = hits[2].jsonObject["score"]?.jsonPrimitive?.floatOrNull ?: 0f
        print("  Verifying ranking... ")
        check(secondId == "2") { "Expected point 2 as second result, got $secondId" }
        println("OK (point 2 second, point 3 score=${"%.4f".format(thirdScore)})")
    }

    // Cleanup
    qdrantRequest("DELETE", "/collections/$collection")
}

// ── Test: Delete ──

private fun testQdrantDelete() {
    val collection = "rag-test-delete"
    val vector = testVector ?: error("No test vector")

    // Setup
    val createBody = buildJsonObject {
        putJsonObject("vectors") { put("size", 1536); put("distance", "Cosine") }
    }
    qdrantRequest("PUT", "/collections/$collection", createBody.toString())

    val points = buildJsonObject {
        putJsonArray("points") {
            addJsonObject {
                put("id", 1)
                putJsonArray("vector") { vector.forEach { add(it) } }
                putJsonObject("payload") { put("filePath", "a.kt") }
            }
            addJsonObject {
                put("id", 2)
                putJsonArray("vector") { vector.forEach { add(it) } }
                putJsonObject("payload") { put("filePath", "b.kt") }
            }
        }
    }
    qdrantRequest("PUT", "/collections/$collection/points?wait=true", points.toString())

    // Delete by filter (filePath = "a.kt")
    print("  Deleting points by filter... ")
    val deleteBody = buildJsonObject {
        putJsonObject("filter") {
            putJsonArray("must") {
                addJsonObject {
                    put("key", "filePath")
                    putJsonObject("match") { put("value", "a.kt") }
                }
            }
        }
    }
    val delResp = qdrantRequest("POST", "/collections/$collection/points/delete?wait=true", deleteBody.toString())
    check(delResp.statusCode() in 200..299) { "Delete failed: ${delResp.body()}" }

    // Verify only del-2 remains
    val scrollBody = buildJsonObject { put("limit", 10); put("with_payload", true) }
    val scrollResp = qdrantRequest("POST", "/collections/$collection/points/scroll", scrollBody.toString())
    val remaining = json.parseToJsonElement(scrollResp.body()).jsonObject["result"]
        ?.jsonObject?.get("points")?.jsonArray
    check(remaining != null && remaining.size == 1) { "Expected 1 point remaining, got ${remaining?.size}" }
    val remainingId = remaining[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull
    check(remainingId == "2") { "Expected point 2 to remain, got $remainingId" }
    println("OK (point 1 removed, point 2 remains)")

    // Cleanup
    qdrantRequest("DELETE", "/collections/$collection")
}

// ── Test: Full round-trip ──

private fun testRoundTrip() {
    val token = sessionToken ?: error("No session token")
    val collection = "rag-test-roundtrip"

    // Setup collection
    val createBody = buildJsonObject {
        putJsonObject("vectors") { put("size", 1536); put("distance", "Cosine") }
    }
    qdrantRequest("PUT", "/collections/$collection", createBody.toString())

    // Simulate indexing: embed 3 code snippets and store them
    val codeSnippets = listOf(
        "fun authenticateUser(username: String, password: String): AuthToken { val hash = bcrypt(password); return db.verifyCredentials(username, hash) }",
        "data class Order(val id: String, val items: List<OrderItem>, val total: Double, val status: OrderStatus)",
        "class DatabaseConnection(val url: String, val poolSize: Int) { fun connect(): Connection { return DriverManager.getConnection(url) } }",
    )

    print("  Embedding 3 code snippets... ")
    val vectors = callEmbeddingsApi(token, codeSnippets)
    check(vectors.size == 3) { "Expected 3 vectors" }
    println("OK")

    print("  Storing in Qdrant... ")
    val points = buildJsonObject {
        putJsonArray("points") {
            codeSnippets.forEachIndexed { i, snippet ->
                addJsonObject {
                    put("id", i + 1)
                    putJsonArray("vector") { vectors[i].forEach { add(it) } }
                    putJsonObject("payload") {
                        put("content", snippet)
                        put("filePath", "src/Snippet$i.kt")
                        put("symbolName", listOf("authenticateUser", "Order", "DatabaseConnection")[i])
                    }
                }
            }
        }
    }
    val upsertResp = qdrantRequest("PUT", "/collections/$collection/points?wait=true", points.toString())
    check(upsertResp.statusCode() in 200..299) { "Upsert failed" }
    println("OK")

    // Query: "how does login work?" — should match the auth snippet
    print("  Querying 'how does login work?'... ")
    val queryVec = callEmbeddingsApi(token, listOf("how does login work?"))[0]
    val searchBody = buildJsonObject {
        putJsonArray("vector") { queryVec.forEach { add(it) } }
        put("limit", 3)
        put("with_payload", true)
        put("score_threshold", 0.3)
    }
    val searchResp = qdrantRequest("POST", "/collections/$collection/points/search", searchBody.toString())
    val hits = json.parseToJsonElement(searchResp.body()).jsonObject["result"]?.jsonArray
    check(hits != null && hits.isNotEmpty()) { "No results for auth query" }

    val topSymbol = hits[0].jsonObject["payload"]?.jsonObject?.get("symbolName")?.jsonPrimitive?.contentOrNull
    val topScore = hits[0].jsonObject["score"]?.jsonPrimitive?.floatOrNull ?: 0f
    check(topSymbol == "authenticateUser") { "Expected 'authenticateUser' as top result, got '$topSymbol'" }
    println("OK (top='$topSymbol', score=${"%.3f".format(topScore)})")

    // Query: "database pool configuration" — should match DatabaseConnection
    print("  Querying 'database pool configuration'... ")
    val dbVec = callEmbeddingsApi(token, listOf("database pool configuration"))[0]
    val dbSearchBody = buildJsonObject {
        putJsonArray("vector") { dbVec.forEach { add(it) } }
        put("limit", 3)
        put("with_payload", true)
        put("score_threshold", 0.3)
    }
    val dbSearchResp = qdrantRequest("POST", "/collections/$collection/points/search", dbSearchBody.toString())
    val dbHits = json.parseToJsonElement(dbSearchResp.body()).jsonObject["result"]?.jsonArray
    check(dbHits != null && dbHits.isNotEmpty()) { "No results for db query" }

    val dbTopSymbol = dbHits[0].jsonObject["payload"]?.jsonObject?.get("symbolName")?.jsonPrimitive?.contentOrNull
    val dbTopScore = dbHits[0].jsonObject["score"]?.jsonPrimitive?.floatOrNull ?: 0f
    check(dbTopSymbol == "DatabaseConnection") { "Expected 'DatabaseConnection' as top result, got '$dbTopSymbol'" }
    println("OK (top='$dbTopSymbol', score=${"%.3f".format(dbTopScore)})")

    // Cleanup
    qdrantRequest("DELETE", "/collections/$collection")
    println("  Round-trip verified: semantic search correctly ranks code by relevance")
}

// ── Helpers ──

private fun readGhuToken(): String {
    val os = System.getProperty("os.name").lowercase()
    val path = if (os.contains("win")) {
        val appdata = System.getenv("APPDATA") ?: (System.getProperty("user.home") + "/AppData/Roaming")
        "$appdata/github-copilot/apps.json"
    } else {
        System.getProperty("user.home") + "/.config/github-copilot/apps.json"
    }

    val file = File(path)
    check(file.exists()) { "apps.json not found at $path — sign in to GitHub Copilot first" }

    val root = json.parseToJsonElement(file.readText()).jsonObject

    // Prefer ghu_ tokens
    for ((_, value) in root) {
        val obj = value.jsonObject
        val token = obj["oauth_token"]?.jsonPrimitive?.contentOrNull ?: continue
        if (token.startsWith("ghu_")) return token
    }

    // Fallback
    for ((_, value) in root) {
        val obj = value.jsonObject
        val token = obj["oauth_token"]?.jsonPrimitive?.contentOrNull ?: continue
        return token
    }

    error("No OAuth token found in apps.json")
}

private fun callEmbeddingsApi(sessionToken: String, texts: List<String>): List<FloatArray> {
    val body = buildJsonObject {
        put("model", "copilot-text-embedding-ada-002")
        putJsonArray("input") { texts.forEach { add(it) } }
    }

    val request = HttpRequest.newBuilder()
        .uri(URI("https://api.githubcopilot.com/embeddings"))
        .header("Authorization", "Bearer $sessionToken")
        .header("Content-Type", "application/json")
        .header("Editor-Version", "JetBrains-IC/2025.2")
        .header("Editor-Plugin-Version", "copilot-intellij/1.420.0")
        .header("Copilot-Integration-Id", "vscode-chat")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        .timeout(Duration.ofSeconds(30))
        .build()

    val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
    check(resp.statusCode() == 200) { "Embeddings API HTTP ${resp.statusCode()}: ${resp.body().take(300)}" }

    val data = json.parseToJsonElement(resp.body()).jsonObject["data"]?.jsonArray
        ?: error("No 'data' field in embeddings response")

    return data.map { entry ->
        val embedding = entry.jsonObject["embedding"]?.jsonArray
            ?: error("No 'embedding' in data entry")
        FloatArray(embedding.size) { i -> embedding[i].jsonPrimitive.float }
    }
}

private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    var dot = 0f; var normA = 0f; var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    return dot / (Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())).toFloat()
}

private fun isQdrantHealthy(): Boolean {
    return try {
        val req = HttpRequest.newBuilder()
            .uri(URI("http://localhost:$QDRANT_PORT/healthz"))
            .timeout(Duration.ofSeconds(2))
            .GET().build()
        client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200
    } catch (_: Exception) { false }
}

private fun qdrantRequest(method: String, path: String, body: String? = null): HttpResponse<String> {
    val builder = HttpRequest.newBuilder()
        .uri(URI("http://localhost:$QDRANT_PORT$path"))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(15))

    when (method) {
        "GET" -> builder.GET()
        "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body ?: "{}"))
        "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body ?: "{}"))
        "DELETE" -> builder.DELETE()
    }

    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
}

private fun stopQdrant() {
    qdrantProcess?.let { p ->
        print("  Stopping Qdrant... ")
        p.destroy()
        try { p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        if (p.isAlive) p.destroyForcibly()
        println("OK")
    }
    qdrantProcess = null
}
