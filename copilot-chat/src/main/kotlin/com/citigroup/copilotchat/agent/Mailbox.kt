package com.citigroup.copilotchat.agent

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile

/**
 * File-based mailbox for agent communication within a team.
 * Each agent gets an inbox file at ~/.copilot-chat/teams/{team-name}/inboxes/{agent-name}.json.
 * Thread-safe via file locking on a .lock sidecar file.
 */
class Mailbox(
    private val teamName: String,
    private val agentName: String,
) {
    private val log = Logger.getInstance(Mailbox::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val baseDir: File
        get() {
            val dir = File(System.getProperty("user.home"), ".copilot-chat/teams/$teamName/inboxes")
            dir.mkdirs()
            return dir
        }

    private val inboxFile: File get() = File(baseDir, "$agentName.json")
    private val lockFile: File get() = File(baseDir, "$agentName.lock")

    /** Read all messages from this agent's inbox. */
    fun read(): List<MailboxMessage> {
        return withFileLock {
            readMessages()
        }
    }

    /** Read only unread messages. */
    fun readUnread(): List<MailboxMessage> {
        return withFileLock {
            readMessages().filter { !it.read }
        }
    }

    /** Write a message to this agent's inbox. */
    fun write(message: MailboxMessage) {
        withFileLock {
            val messages = readMessages().toMutableList()
            messages.add(message)
            writeMessages(messages)
        }
    }

    /** Mark specific messages as read (by timestamp). */
    fun markRead(timestamps: Set<Long>) {
        withFileLock {
            val messages = readMessages()
            for (msg in messages) {
                if (msg.timestamp in timestamps) {
                    msg.read = true
                }
            }
            writeMessages(messages)
        }
    }

    /** Mark all messages as read. */
    fun markAllRead() {
        withFileLock {
            val messages = readMessages()
            for (msg in messages) {
                msg.read = true
            }
            writeMessages(messages)
        }
    }

    /** Clear all messages from this inbox. */
    fun clear() {
        withFileLock {
            writeMessages(emptyList())
        }
    }

    private fun readMessages(): List<MailboxMessage> {
        if (!inboxFile.exists()) return emptyList()
        return try {
            val text = inboxFile.readText()
            if (text.isBlank()) return emptyList()
            val array = json.parseToJsonElement(text).jsonArray
            array.map { el ->
                val obj = el.jsonObject
                MailboxMessage(
                    from = obj["from"]?.jsonPrimitive?.contentOrNull ?: "",
                    text = obj["text"]?.jsonPrimitive?.contentOrNull ?: "",
                    timestamp = obj["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L,
                    color = obj["color"]?.jsonPrimitive?.contentOrNull,
                    summary = obj["summary"]?.jsonPrimitive?.contentOrNull,
                    read = obj["read"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to read mailbox $agentName: ${e.message}")
            emptyList()
        }
    }

    private fun writeMessages(messages: List<MailboxMessage>) {
        val array = buildJsonArray {
            for (msg in messages) {
                addJsonObject {
                    put("from", msg.from)
                    put("text", msg.text)
                    put("timestamp", msg.timestamp)
                    if (msg.color != null) put("color", msg.color)
                    if (msg.summary != null) put("summary", msg.summary)
                    put("read", msg.read)
                }
            }
        }
        inboxFile.writeText(json.encodeToString(JsonArray.serializer(), array))
    }

    private fun <T> withFileLock(block: () -> T): T {
        lockFile.parentFile.mkdirs()
        val raf = RandomAccessFile(lockFile, "rw")
        return try {
            val lock = raf.channel.lock()
            try {
                block()
            } finally {
                lock.release()
            }
        } finally {
            raf.close()
        }
    }

    companion object {
        /** Delete all mailbox files for a team. */
        fun deleteTeamMailboxes(teamName: String) {
            val dir = File(System.getProperty("user.home"), ".copilot-chat/teams/$teamName")
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }
}
