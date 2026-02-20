package com.citigroup.copilotchat.rag

import com.citigroup.copilotchat.auth.CopilotAuth
import com.citigroup.copilotchat.config.CopilotChatSettings
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
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

    private fun buildHttpClient(): HttpClient {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
        val proxyUrl = CopilotChatSettings.getInstance().proxyUrl
        if (proxyUrl.isNotBlank()) {
            try {
                val uri = URI(proxyUrl)
                builder.proxy(java.net.ProxySelector.of(java.net.InetSocketAddress(uri.host, uri.port)))
            } catch (e: Exception) {
                log.warn("Failed to configure proxy for embeddings: ${e.message}")
            }
        }
        return builder.build()
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
        val client = buildHttpClient()

        val request = HttpRequest.newBuilder()
            .uri(URI(TOKEN_EXCHANGE_URL))
            .header("Authorization", "token ${auth.token}")
            .header("Accept", "application/json")
            .header("Editor-Version", "JetBrains-IC/2025.2")
            .header("Editor-Plugin-Version", "copilot-intellij/1.420.0")
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("Token exchange failed (${response.statusCode()}): ${response.body().take(200)}")
        }

        val body = json.parseToJsonElement(response.body()).jsonObject
        val token = body["token"]?.jsonPrimitive?.contentOrNull
            ?: throw RuntimeException("No 'token' field in token exchange response")

        val expiresAt = body["expires_at"]?.jsonPrimitive?.longOrNull
        tokenExpiry = if (expiresAt != null) Instant.ofEpochSecond(expiresAt) else Instant.now().plusSeconds(1500)
        sessionToken = token

        log.info("Copilot session token refreshed, expires at $tokenExpiry")
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
        val client = buildHttpClient()
        val requestBody = buildJsonObject {
            put("model", MODEL)
            putJsonArray("input") {
                texts.forEach { add(it) }
            }
        }

        var lastException: Exception? = null

        for (attempt in 0 until MAX_RETRIES) {
            val token = ensureSessionToken()

            val request = HttpRequest.newBuilder()
                .uri(URI(EMBEDDINGS_URL))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .header("Editor-Version", "JetBrains-IC/2025.2")
                .header("Editor-Plugin-Version", "copilot-intellij/1.420.0")
                .header("Copilot-Integration-Id", "vscode-chat")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .timeout(Duration.ofSeconds(30))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            when {
                response.statusCode() == 200 -> {
                    return parseEmbeddingsResponse(response.body())
                }

                response.statusCode() == 429 -> {
                    val retryAfter = response.headers().firstValue("retry-after").orElse(null)
                    val delayMs = if (retryAfter != null) {
                        // Retry-After is in seconds
                        (retryAfter.toLongOrNull() ?: 1) * 1000
                    } else {
                        // Exponential backoff: 500ms, 1s, 2s, 4s...
                        (INITIAL_RETRY_DELAY_MS shl attempt).coerceAtMost(MAX_RETRY_DELAY_MS)
                    }
                    log.warn("Embeddings API rate limited (429), retrying in ${delayMs}ms (attempt ${attempt + 1}/$MAX_RETRIES)")
                    Thread.sleep(delayMs)
                    lastException = RuntimeException("Rate limited (429)")
                }

                response.statusCode() == 401 -> {
                    // Token expired mid-flight — force refresh and retry
                    log.info("Embeddings API returned 401, refreshing session token")
                    sessionToken = null
                    tokenExpiry = Instant.EPOCH
                    lastException = RuntimeException("Unauthorized (401)")
                }

                else -> {
                    throw RuntimeException("Embeddings API failed (${response.statusCode()}): ${response.body().take(200)}")
                }
            }
        }

        throw RuntimeException("Embeddings API failed after $MAX_RETRIES retries", lastException)
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
