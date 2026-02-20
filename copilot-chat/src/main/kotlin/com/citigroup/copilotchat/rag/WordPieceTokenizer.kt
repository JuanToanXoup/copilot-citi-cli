package com.citigroup.copilotchat.rag

/**
 * Pure Kotlin BERT WordPiece tokenizer.
 *
 * Pipeline: lowercase -> whitespace/punctuation split -> WordPiece sub-tokenize
 * -> prepend [CLS], append [SEP] -> pad/truncate to [maxLength] tokens.
 *
 * Thread-safe: the vocabulary map is immutable after construction.
 */
class WordPieceTokenizer(
    vocabLines: List<String>,
    private val maxLength: Int = 256,
) {
    private val vocab: Map<String, Int> = vocabLines
        .mapIndexed { index, token -> token to index }
        .toMap()

    private val unkId = vocab["[UNK]"] ?: 100
    private val clsId = vocab["[CLS]"] ?: 101
    private val sepId = vocab["[SEP]"] ?: 102
    private val padId = vocab["[PAD]"] ?: 0

    companion object {
        private var instance: WordPieceTokenizer? = null

        @Synchronized
        fun getInstance(): WordPieceTokenizer {
            instance?.let { return it }
            val lines = WordPieceTokenizer::class.java
                .getResourceAsStream("/tokenizer/vocab.txt")!!
                .bufferedReader()
                .readLines()
            return WordPieceTokenizer(lines).also { instance = it }
        }
    }

    data class Encoded(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray,
    )

    fun encode(text: String): Encoded {
        val tokens = tokenize(text)
        // Truncate to maxLength - 2 to leave room for [CLS] and [SEP]
        val truncated = tokens.take(maxLength - 2)

        val ids = mutableListOf(clsId)
        truncated.forEach { ids.add(vocab[it] ?: unkId) }
        ids.add(sepId)

        val attentionLen = ids.size
        // Pad to maxLength
        while (ids.size < maxLength) ids.add(padId)

        val inputIds = LongArray(maxLength) { ids[it].toLong() }
        val attentionMask = LongArray(maxLength) { if (it < attentionLen) 1L else 0L }
        val tokenTypeIds = LongArray(maxLength) // all zeros for single-sentence

        return Encoded(inputIds, attentionMask, tokenTypeIds)
    }

    private fun tokenize(text: String): List<String> {
        val lower = text.lowercase()
        val words = splitOnWhitespaceAndPunctuation(lower)
        val result = mutableListOf<String>()
        for (word in words) {
            result.addAll(wordPieceTokenize(word))
        }
        return result
    }

    private fun splitOnWhitespaceAndPunctuation(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        for (ch in text) {
            when {
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                isPunctuation(ch) -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                    tokens.add(ch.toString())
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    private fun isPunctuation(ch: Char): Boolean {
        val code = ch.code
        // ASCII punctuation ranges
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        // Unicode general punctuation
        return Character.getType(ch).toByte() in listOf(
            Character.CONNECTOR_PUNCTUATION,
            Character.DASH_PUNCTUATION,
            Character.END_PUNCTUATION,
            Character.FINAL_QUOTE_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            Character.START_PUNCTUATION,
        ).map { it.toByte() }
    }

    private fun wordPieceTokenize(word: String): List<String> {
        if (word.isEmpty()) return emptyList()

        val tokens = mutableListOf<String>()
        var start = 0

        while (start < word.length) {
            var end = word.length
            var found: String? = null

            while (start < end) {
                val substr = if (start == 0) {
                    word.substring(start, end)
                } else {
                    "##" + word.substring(start, end)
                }

                if (substr in vocab) {
                    found = substr
                    break
                }
                end--
            }

            if (found == null) {
                tokens.add("[UNK]")
                break
            }

            tokens.add(found)
            start = end
        }

        return tokens
    }
}
