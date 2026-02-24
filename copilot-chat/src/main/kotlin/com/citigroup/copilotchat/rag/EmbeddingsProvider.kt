package com.citigroup.copilotchat.rag

/**
 * Abstraction over embedding model implementations.
 *
 * [LocalEmbeddings] is the default on-device ONNX implementation.
 * This interface allows swapping to a remote embeddings API or
 * a different local model without changing callers.
 */
interface EmbeddingsProvider {
    fun embed(text: String): FloatArray
    fun embedBatch(texts: List<String>): List<FloatArray>
    fun vectorDimension(): Int
}
