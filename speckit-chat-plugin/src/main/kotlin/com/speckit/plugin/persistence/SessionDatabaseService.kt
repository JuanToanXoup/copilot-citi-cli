package com.speckit.plugin.persistence

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.speckit.plugin.ui.ChatRun
import com.speckit.plugin.ui.ChatRunStatus
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.io.File

@Service(Service.Level.PROJECT)
class SessionDatabaseService(private val project: Project) : Disposable {

    private var db: Database? = null

    fun initialize() {
        if (db != null) return
        val basePath = project.basePath ?: return
        val dbFile = File(basePath, ".speckit/sessions.db")
        db = DatabaseFactory.connect(dbFile)
        SchemaManager(db!!).migrate()
    }

    fun insertSession(
        sessionId: String,
        agent: String,
        prompt: String,
        branch: String,
        status: String,
        startTimeMs: Long,
        projectName: String?,
        projectPath: String?
    ) {
        val database = db ?: return
        val now = System.currentTimeMillis()
        transaction(database) {
            SessionsTable.insert {
                it[id] = sessionId
                it[SessionsTable.agent] = agent
                it[SessionsTable.prompt] = prompt
                it[SessionsTable.branch] = branch
                it[SessionsTable.status] = status
                it[SessionsTable.startTimeMs] = startTimeMs
                it[SessionsTable.projectName] = projectName
                it[SessionsTable.projectPath] = projectPath
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    fun updateConversationId(sessionId: String, conversationId: String) {
        val database = db ?: return
        transaction(database) {
            SessionsTable.update({ SessionsTable.id eq sessionId }) {
                it[SessionsTable.conversationId] = conversationId
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    fun updateStatus(sessionId: String, status: String, durationMs: Long, errorMessage: String? = null) {
        val database = db ?: return
        transaction(database) {
            SessionsTable.update({ SessionsTable.id eq sessionId }) {
                it[SessionsTable.status] = status
                it[SessionsTable.durationMs] = durationMs
                it[SessionsTable.errorMessage] = errorMessage
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    fun insertMessage(sessionId: String, role: String, content: String, sequenceNum: Int, timestampMs: Long) {
        val database = db ?: return
        transaction(database) {
            MessagesTable.insert {
                it[MessagesTable.sessionId] = sessionId
                it[MessagesTable.role] = role
                it[MessagesTable.content] = content
                it[MessagesTable.sequenceNum] = sequenceNum
                it[MessagesTable.timestampMs] = timestampMs
            }
        }
    }

    fun insertToolCall(
        sessionId: String,
        toolName: String,
        input: String,
        output: String,
        status: String,
        durationMs: Long,
        sequenceNum: Int,
        timestampMs: Long
    ) {
        val database = db ?: return
        transaction(database) {
            ToolCallsTable.insert {
                it[ToolCallsTable.sessionId] = sessionId
                it[ToolCallsTable.toolName] = toolName
                it[ToolCallsTable.input] = input
                it[ToolCallsTable.output] = output
                it[ToolCallsTable.status] = status
                it[ToolCallsTable.durationMs] = durationMs
                it[ToolCallsTable.sequenceNum] = sequenceNum
                it[ToolCallsTable.timestampMs] = timestampMs
            }
        }
    }

    fun loadRecentSessions(limit: Int = 100): List<ChatRun> {
        val database = db ?: return emptyList()
        return transaction(database) {
            SessionsTable.selectAll()
                .orderBy(SessionsTable.startTimeMs, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    ChatRun(
                        agent = row[SessionsTable.agent],
                        prompt = row[SessionsTable.prompt],
                        branch = row[SessionsTable.branch],
                        startTimeMillis = row[SessionsTable.startTimeMs]
                    ).also { run ->
                        run.sessionId = row[SessionsTable.id]
                        run.status = ChatRunStatus.valueOf(row[SessionsTable.status])
                        run.durationMs = row[SessionsTable.durationMs]
                        run.errorMessage = row[SessionsTable.errorMessage]
                    }
                }
        }
    }

    fun getSession(sessionId: String): SessionRow? {
        val database = db ?: return null
        return transaction(database) {
            SessionsTable.selectAll()
                .where { SessionsTable.id eq sessionId }
                .firstOrNull()
                ?.let { row ->
                    SessionRow(
                        id = row[SessionsTable.id],
                        conversationId = row[SessionsTable.conversationId],
                        agent = row[SessionsTable.agent],
                        prompt = row[SessionsTable.prompt],
                        branch = row[SessionsTable.branch],
                        status = row[SessionsTable.status],
                        errorMessage = row[SessionsTable.errorMessage],
                        startTimeMs = row[SessionsTable.startTimeMs],
                        durationMs = row[SessionsTable.durationMs],
                        projectName = row[SessionsTable.projectName],
                        projectPath = row[SessionsTable.projectPath],
                        createdAt = row[SessionsTable.createdAt],
                        updatedAt = row[SessionsTable.updatedAt]
                    )
                }
        }
    }

    fun getMessages(sessionId: String): List<MessageRow> {
        val database = db ?: return emptyList()
        return transaction(database) {
            MessagesTable.selectAll()
                .where { MessagesTable.sessionId eq sessionId }
                .orderBy(MessagesTable.sequenceNum, SortOrder.ASC)
                .map { row ->
                    MessageRow(
                        role = row[MessagesTable.role],
                        content = row[MessagesTable.content],
                        sequenceNum = row[MessagesTable.sequenceNum],
                        timestampMs = row[MessagesTable.timestampMs]
                    )
                }
        }
    }

    fun getToolCalls(sessionId: String): List<ToolCallRow> {
        val database = db ?: return emptyList()
        return transaction(database) {
            ToolCallsTable.selectAll()
                .where { ToolCallsTable.sessionId eq sessionId }
                .orderBy(ToolCallsTable.sequenceNum, SortOrder.ASC)
                .map { row ->
                    ToolCallRow(
                        toolName = row[ToolCallsTable.toolName],
                        input = row[ToolCallsTable.input],
                        output = row[ToolCallsTable.output],
                        status = row[ToolCallsTable.status],
                        durationMs = row[ToolCallsTable.durationMs],
                        sequenceNum = row[ToolCallsTable.sequenceNum],
                        timestampMs = row[ToolCallsTable.timestampMs]
                    )
                }
        }
    }

    override fun dispose() {
        // Database connections are managed by Exposed; no explicit close needed for SQLite
    }
}

data class SessionRow(
    val id: String,
    val conversationId: String?,
    val agent: String,
    val prompt: String,
    val branch: String,
    val status: String,
    val errorMessage: String?,
    val startTimeMs: Long,
    val durationMs: Long,
    val projectName: String?,
    val projectPath: String?,
    val createdAt: Long,
    val updatedAt: Long
)

data class MessageRow(
    val role: String,
    val content: String,
    val sequenceNum: Int,
    val timestampMs: Long
)

data class ToolCallRow(
    val toolName: String,
    val input: String,
    val output: String,
    val status: String,
    val durationMs: Long,
    val sequenceNum: Int,
    val timestampMs: Long
)
