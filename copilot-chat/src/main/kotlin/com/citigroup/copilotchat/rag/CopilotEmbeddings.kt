package com.citigroup.copilotchat.rag

import com.citigroup.copilotchat.auth.CopilotAuth
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.net.HttpConfigurable
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Embedding client using the Copilot Internal Embeddings API.
 *
 * Token flow:
 *   1. Read `ghu_` token from CopilotAuth (apps.json)
 *   2. Exchange for session token via `POST https://api.github.com/copilot_internal/v2/token`
 *   3. Call `POST https://api.githubcopilot.com/embeddings` with session token
 *
 * Session tokens expire ~30 min; cached and auto-refreshed.
 *
 * Uses [HttpConfigurable.openConnection] for HTTP calls with IDE proxy settings.
 * If the proxy blocks HTTPS tunneling (403/407), automatically falls back to
 * direct connections (bypassing the proxy) for subsequent calls.
 */
object CopilotEmbeddings {

    private val log = Logger.getInstance(CopilotEmbeddings::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private const val TOKEN_EXCHANGE_URL = "https://api.github.com/copilot_internal/v2/token"
    private const val EMBEDDINGS_URL = "https://api.githubcopilot.com/embeddings"
    private const val MODEL = "copilot-text-embedding-ada-002"
    private const val VECTOR_DIM = 1536
    private const val MAX_BATCH_SIZE = 50
    private const val BATCH_DELAY_MS = 200L
    private const val MAX_RETRIES = 4
    private const val INITIAL_RETRY_DELAY_MS = 500L
    private const val MAX_RETRY_DELAY_MS = 30_000L

    // Cached session token
    @Volatile private var sessionToken: String? = null
    @Volatile private var tokenExpiry: Instant = Instant.EPOCH

    /**
     * When true, skip IDE proxy and connect directly.
     * Auto-set when the proxy returns 403 "unable to tunnel".
     */
    @Volatile private var useDirectConnection = false

    /**
     * Open an [HttpURLConnection]. Tries IDE proxy first; if the proxy blocks
     * HTTPS tunneling (403 "unable to tunnel"), falls back to a direct connection.
     */
    private fun openConnection(url: String): HttpURLConnection {
        val connection = if (useDirectConnection) {
            URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        } else {
            HttpConfigurable.getInstance().openHttpConnection(url)
        }
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        return connection
    }

    /**
     * Exchange the `ghu_` OAuth token for a Copilot session token.
     * The session token is cached and refreshed 5 minutes before expiry.
     */
    @Synchronized
    private fun ensureSessionToken(): String {
        val existing = sessionToken
        if (existing != null && Instant.now().isBefore(tokenExpiry.minusSeconds(300))) {
            return existing
        }

        val auth = CopilotAuth.readAuth()

        val connection = try {
            val conn = openConnection(TOKEN_EXCHANGE_URL)
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "token ${auth.token}")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Editor-Version", "JetBrains-IC/2025.2")
            conn.setRequestProperty("Editor-Plugin-Version", "copilot-intellij/1.420.0")
            conn.readTimeout = 15_000
            // Trigger the connection to detect tunnel failures early
            conn.responseCode
            conn
        } catch (e: java.io.IOException) {
            if (!useDirectConnection && isTunnelError(e)) {
                log.warn("Proxy blocks HTTPS tunnel, switching to direct connection for Copilot API")
                useDirectConnection = true
                val conn = openConnection(TOKEN_EXCHANGE_URL)
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "token ${auth.token}")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Editor-Version", "JetBrains-IC/2025.2")
                conn.setRequestProperty("Editor-Plugin-Version", "copilot-intellij/1.420.0")
                conn.readTimeout = 15_000
                conn.responseCode
                conn
            } else {
                throw e
            }
        }

        val statusCode = connection.responseCode
        val responseBody = if (statusCode in 200..299) {
            connection.inputStream.bufferedReader().readText()
        } else {
            connection.errorStream?.bufferedReader()?.readText() ?: ""
        }

        if (statusCode != 200) {
            throw RuntimeException("Token exchange failed ($statusCode): ${responseBody.take(200)}")
        }

        val body = json.parseToJsonElement(responseBody).jsonObject
        val token = body["token"]?.jsonPrimitive?.contentOrNull
            ?: throw RuntimeException("No 'token' field in token exchange response")

        val expiresAt = body["expires_at"]?.jsonPrimitive?.longOrNull
        tokenExpiry = if (expiresAt != null) Instant.ofEpochSecond(expiresAt) else Instant.now().plusSeconds(1500)
        sessionToken = token

        log.info("Copilot session token refreshed (direct=$useDirectConnection), expires at $tokenExpiry")
        return token
    }

    /**
     * Embed a single text string. Returns a float array of dimension [VECTOR_DIM].
     */
    fun embed(text: String): FloatArray {
        return embedBatch(listOf(text)).first()
    }

    /**
     * Embed multiple texts, batching into groups of [MAX_BATCH_SIZE].
     * Returns a list of float arrays, one per input text, each of dimension [VECTOR_DIM].
     */
    fun embedBatch(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        val results = mutableListOf<FloatArray>()
        val batches = texts.chunked(MAX_BATCH_SIZE)

        for ((i, batch) in batches.withIndex()) {
            if (i > 0) {
                Thread.sleep(BATCH_DELAY_MS)
            }
            results.addAll(callEmbeddingsApi(batch))
        }

        return results
    }

    private fun callEmbeddingsApi(texts: List<String>): List<FloatArray> {
        val requestBody = buildJsonObject {
            put("model", MODEL)
            putJsonArray("input") {
                texts.forEach { add(it) }
            }
        }

        var lastException: Exception? = null

        for (attempt in 0 until MAX_RETRIES) {
            val token = ensureSessionToken()

            val connection = try {
                val conn = openConnection(EMBEDDINGS_URL)
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Editor-Version", "JetBrains-IC/2025.2")
                conn.setRequestProperty("Editor-Plugin-Version", "copilot-intellij/1.420.0")
                conn.setRequestProperty("Copilot-Integration-Id", "vscode-chat")
                conn.outputStream.bufferedWriter().use { it.write(requestBody.toString()) }
                conn
            } catch (e: java.io.IOException) {
                if (!useDirectConnection && isTunnelError(e)) {
                    log.warn("Proxy blocks HTTPS tunnel, switching to direct connection for Copilot API")
                    useDirectConnection = true
                    val conn = openConnection(EMBEDDINGS_URL)
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Editor-Version", "JetBrains-IC/2025.2")
                    conn.setRequestProperty("Editor-Plugin-Version", "copilot-intellij/1.420.0")
                    conn.setRequestProperty("Copilot-Integration-Id", "vscode-chat")
                    conn.outputStream.bufferedWriter().use { it.write(requestBody.toString()) }
                    conn
                } else {
                    throw e
                }
            }

            val statusCode = connection.responseCode
            val responseBody = if (statusCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: ""
            }

            when {
                statusCode == 200 -> {
                    return parseEmbeddingsResponse(responseBody)
                }

                statusCode == 429 -> {
                    val retryAfter = connection.getHeaderField("retry-after")
                    val delayMs = if (retryAfter != null) {
                        (retryAfter.toLongOrNull() ?: 1) * 1000
                    } else {
                        (INITIAL_RETRY_DELAY_MS shl attempt).coerceAtMost(MAX_RETRY_DELAY_MS)
                    }
                    log.warn("Embeddings API rate limited (429), retrying in ${delayMs}ms (attempt ${attempt + 1}/$MAX_RETRIES)")
                    Thread.sleep(delayMs)
                    lastException = RuntimeException("Rate limited (429)")
                }

                statusCode == 401 -> {
                    log.info("Embeddings API returned 401, refreshing session token")
                    sessionToken = null
                    tokenExpiry = Instant.EPOCH
                    lastException = RuntimeException("Unauthorized (401)")
                }

                statusCode == 403 -> {
                    val msg = responseBody.take(200)
                    if (!useDirectConnection && (msg.contains("tunnel", ignoreCase = true) || msg.contains("proxy", ignoreCase = true))) {
                        log.warn("Proxy returned 403, switching to direct connection: $msg")
                        useDirectConnection = true
                        lastException = RuntimeException("Proxy 403: $msg")
                        continue
                    }
                    throw RuntimeException("Embeddings API failed (403): $msg")
                }

                else -> {
                    throw RuntimeException("Embeddings API failed ($statusCode): ${responseBody.take(200)}")
                }
            }
        }

        throw RuntimeException("Embeddings API failed after $MAX_RETRIES retries", lastException)
    }

    /**
     * Detect proxy tunnel errors: "Unable to tunnel through proxy" (403)
     * or "Proxy Authentication Required" (407) thrown as IOException.
     */
    private fun isTunnelError(e: java.io.IOException): Boolean {
        val msg = e.message?.lowercase() ?: return false
        return msg.contains("tunnel") || msg.contains("proxy") || msg.contains("403") || msg.contains("407")
    }

    private fun parseEmbeddingsResponse(body: String): List<FloatArray> {
        val parsed = json.parseToJsonElement(body).jsonObject
        val data = parsed["data"]?.jsonArray
            ?: throw RuntimeException("No 'data' field in embeddings response")

        return data.map { entry ->
            val embedding = entry.jsonObject["embedding"]?.jsonArray
                ?: throw RuntimeException("No 'embedding' field in data entry")
            FloatArray(embedding.size) { idx ->
                embedding[idx].jsonPrimitive.float
            }
        }
    }

    /** Dimension of the embedding vectors. */
    fun vectorDimension(): Int = VECTOR_DIM
}
