package com.citigroup.copilotchat.rag

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Local embedding model using ONNX Runtime with bge-small-en-v1.5 (INT8 quantized).
 *
 * Drop-in replacement for [CopilotEmbeddings]. Runs entirely on-device — no network calls needed.
 *
 * Model (~32MB) is downloaded on first use to `~/.copilot-chat/models/`.
 * [OrtSession] is created once and reused (thread-safe for concurrent `run()` calls).
 */
object LocalEmbeddings {

    private val log = Logger.getInstance(LocalEmbeddings::class.java)

    private const val VECTOR_DIM = 384
    private const val MODEL_NAME = "bge-small-en-v1.5-quantized.onnx"
    private const val MODEL_URL =
        "https://huggingface.co/Xenova/bge-small-en-v1.5/resolve/main/onnx/model_quantized.onnx"

    private val modelsDir = File(System.getProperty("user.home"), ".copilot-chat/models")
    private val modelFile = File(modelsDir, MODEL_NAME)

    @Volatile
    private var session: OrtSession? = null

    // Lazy: OrtEnvironment triggers native lib loading via static init.
    // IntelliJ's plugin classloader can't find the native libs without help,
    // so we extract them first before touching any ONNX Runtime classes.
    private val env: OrtEnvironment by lazy {
        extractNativeLibrary()
        OrtEnvironment.getEnvironment()
    }

    /** Embed a single text string. Returns a float array of dimension 384. */
    fun embed(text: String): FloatArray = embedBatch(listOf(text)).first()

    /** Embed multiple texts. Returns a list of float arrays, each of dimension 384. */
    fun embedBatch(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        val ortSession = ensureSession()
        val tokenizer = WordPieceTokenizer.getInstance()

        val encodings = texts.map { tokenizer.encode(it) }
        val batchSize = encodings.size
        val seqLen = encodings.first().inputIds.size

        // Build batched tensors
        val inputIdsBuffer = LongBuffer.allocate(batchSize * seqLen)
        val attMaskBuffer = LongBuffer.allocate(batchSize * seqLen)
        val tokenTypeBuffer = LongBuffer.allocate(batchSize * seqLen)

        for (enc in encodings) {
            inputIdsBuffer.put(enc.inputIds)
            attMaskBuffer.put(enc.attentionMask)
            tokenTypeBuffer.put(enc.tokenTypeIds)
        }
        inputIdsBuffer.flip()
        attMaskBuffer.flip()
        tokenTypeBuffer.flip()

        val shape = longArrayOf(batchSize.toLong(), seqLen.toLong())

        val inputIdsTensor = OnnxTensor.createTensor(env, inputIdsBuffer, shape)
        val attMaskTensor = OnnxTensor.createTensor(env, attMaskBuffer, shape)
        val tokenTypeTensor = OnnxTensor.createTensor(env, tokenTypeBuffer, shape)

        val inputs = mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to attMaskTensor,
            "token_type_ids" to tokenTypeTensor,
        )

        return try {
            val result = ortSession.run(inputs)
            // Output shape: [batch, seqLen, 384]
            @Suppress("UNCHECKED_CAST")
            val output = result[0].value as Array<Array<FloatArray>>

            // Mean pool + L2 normalize
            (0 until batchSize).map { b ->
                meanPoolAndNormalize(output[b], encodings[b].attentionMask)
            }
        } finally {
            inputIdsTensor.close()
            attMaskTensor.close()
            tokenTypeTensor.close()
        }
    }

    /** Dimension of the embedding vectors. */
    fun vectorDimension(): Int = VECTOR_DIM

    private fun meanPoolAndNormalize(tokenEmbeddings: Array<FloatArray>, attentionMask: LongArray): FloatArray {
        val dim = tokenEmbeddings[0].size
        val sum = FloatArray(dim)
        var count = 0f

        for (i in tokenEmbeddings.indices) {
            if (attentionMask[i] == 1L) {
                for (d in 0 until dim) {
                    sum[d] += tokenEmbeddings[i][d]
                }
                count++
            }
        }

        if (count > 0) {
            for (d in 0 until dim) sum[d] /= count
        }

        // L2 normalize
        var norm = 0f
        for (d in 0 until dim) norm += sum[d] * sum[d]
        norm = sqrt(norm)

        if (norm > 0f) {
            for (d in 0 until dim) sum[d] /= norm
        }

        return sum
    }

    /**
     * Extract ONNX Runtime native libraries from the JAR to a temp directory
     * and set the system property so ONNX Runtime can find them.
     *
     * IntelliJ's plugin classloader isolates dependencies, which prevents
     * ONNX Runtime's built-in native lib extraction from working.
     */
    private fun extractNativeLibrary() {
        // If already set (e.g. by a previous call or user config), skip
        if (System.getProperty("onnxruntime.native.path") != null) return

        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()

        val (nativeDir, libNames) = when {
            "mac" in osName && ("aarch64" in osArch || "arm64" in osArch) ->
                "osx-aarch64" to listOf("libonnxruntime.dylib", "libonnxruntime4j_jni.dylib")
            "mac" in osName ->
                "osx-x64" to listOf("libonnxruntime.dylib", "libonnxruntime4j_jni.dylib")
            "linux" in osName && ("aarch64" in osArch || "arm64" in osArch) ->
                "linux-aarch64" to listOf("libonnxruntime.so", "libonnxruntime4j_jni.so")
            "linux" in osName ->
                "linux-x64" to listOf("libonnxruntime.so", "libonnxruntime4j_jni.so")
            "win" in osName ->
                "win-x64" to listOf("onnxruntime.dll", "onnxruntime4j_jni.dll", "onnxruntime_providers_shared.dll")
            else -> return
        }

        val extractDir = File(System.getProperty("user.home"), ".copilot-chat/native/$nativeDir")
        extractDir.mkdirs()

        val classLoader = LocalEmbeddings::class.java.classLoader
        for (libName in libNames) {
            val target = File(extractDir, libName)
            if (target.exists()) continue

            val resourcePath = "ai/onnxruntime/native/$nativeDir/$libName"
            val stream = classLoader.getResourceAsStream(resourcePath)
            if (stream == null) {
                log.warn("Native library not found in JAR: $resourcePath")
                continue
            }
            stream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            log.info("Extracted native library: $resourcePath -> ${target.absolutePath}")
        }

        System.setProperty("onnxruntime.native.path", extractDir.absolutePath)
        log.info("Set onnxruntime.native.path=${extractDir.absolutePath}")
    }

    @Synchronized
    private fun ensureSession(): OrtSession {
        session?.let { return it }

        if (!modelFile.exists()) {
            downloadModel()
        }

        log.info("Loading ONNX model from ${modelFile.absolutePath}")
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
        }
        return env.createSession(modelFile.absolutePath, opts).also { session = it }
    }

    private fun downloadModel() {
        modelsDir.mkdirs()

        log.info("Downloading ONNX model from $MODEL_URL")

        try {
            com.intellij.util.io.HttpRequests.request(MODEL_URL)
                .connectTimeout(30_000)
                .readTimeout(300_000)
                .saveToFile(modelFile, null)
            log.info("ONNX model saved to ${modelFile.absolutePath} (${modelFile.length() / 1024}KB)")
        } catch (e: Exception) {
            log.warn("Failed to download ONNX model: ${e.message}", e)
            if (modelFile.exists()) modelFile.delete()
            throw RuntimeException(
                "Failed to download ONNX model. You can manually download it from:\n" +
                    "  $MODEL_URL\n" +
                    "and place it at:\n" +
                    "  ${modelFile.absolutePath}\n" +
                    "Error: ${e.message}",
                e,
            )
        }
    }
}
