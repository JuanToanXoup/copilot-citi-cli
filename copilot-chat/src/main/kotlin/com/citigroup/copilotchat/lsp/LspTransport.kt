package com.citigroup.copilotchat.lsp

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Content-Length framed transport over stdin/stdout of a child process.
 * Port of client.py _encode_message / _read_message.
 */
class LspTransport(
    private val input: InputStream,
    private val output: OutputStream,
) {
    private val log = Logger.getInstance(LspTransport::class.java)
    private val buffer = ByteArray(8192)
    private var accumulated = ByteArray(0)

    /** Write a Content-Length framed JSON message to stdout of the process. */
    @Synchronized
    fun sendMessage(json: String) {
        val body = json.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        output.write(header)
        output.write(body)
        output.flush()
    }

    /**
     * Blocking read loop — call from Dispatchers.IO.
     * Parses Content-Length framing and yields complete JSON messages.
     */
    suspend fun readLoop(onMessage: (String) -> Unit) = withContext(Dispatchers.IO) {
        try {
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break

                // Append to accumulated buffer
                val newAccumulated = ByteArray(accumulated.size + bytesRead)
                accumulated.copyInto(newAccumulated)
                buffer.copyInto(newAccumulated, accumulated.size, 0, bytesRead)
                accumulated = newAccumulated

                // Try to parse complete messages
                while (true) {
                    val message = tryParseMessage() ?: break
                    onMessage(message)
                }
            }
        } catch (e: Exception) {
            if (e !is InterruptedException) {
                log.warn("LSP transport read loop ended", e)
            }
        }
    }

    private fun tryParseMessage(): String? {
        val headerEnd = findHeaderEnd() ?: return null
        val headerSection = String(accumulated, 0, headerEnd, Charsets.US_ASCII)
        val contentLength = parseContentLength(headerSection) ?: return null

        val bodyStart = headerEnd + 4  // skip \r\n\r\n
        val bodyEnd = bodyStart + contentLength

        if (accumulated.size < bodyEnd) return null

        val body = String(accumulated, bodyStart, contentLength, Charsets.UTF_8)
        accumulated = accumulated.copyOfRange(bodyEnd, accumulated.size)
        return body
    }

    private fun findHeaderEnd(): Int? {
        val marker = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        for (i in 0..accumulated.size - marker.size) {
            if (accumulated[i] == marker[0] &&
                accumulated[i + 1] == marker[1] &&
                accumulated[i + 2] == marker[2] &&
                accumulated[i + 3] == marker[3]
            ) {
                return i
            }
        }
        return null
    }

    private fun parseContentLength(header: String): Int? {
        for (line in header.split("\r\n")) {
            if (line.lowercase().startsWith("content-length:")) {
                return line.substringAfter(":").trim().toIntOrNull()
            }
        }
        return null
    }
}
