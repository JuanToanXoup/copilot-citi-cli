package com.speckit.plugin.persistence

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.io.File

@Service(Service.Level.APP)
class UserDatabaseService : Disposable {

    private var db: Database? = null

    fun initialize() {
        if (db != null) return
        val home = System.getProperty("user.home")
        val dbFile = File(home, ".speckit/sessions.db")
        db = DatabaseFactory.connect(dbFile)
        SchemaManager(db!!).migrate()
    }

    /**
     * Syncs a completed session (and its messages/tool calls) from a project DB
     * into the user-level DB. Uses INSERT OR REPLACE keyed on Copilot session ID.
     */
    fun syncSession(session: SessionRow, messages: List<MessageRow>, toolCalls: List<ToolCallRow>, sourceProject: String?) {
        val database = db ?: return
        transaction(database) {
            // Upsert session — delete + re-insert since Exposed doesn't have native upsert for all DBs
            SessionsTable.deleteWhere { id eq session.id }
            SessionsTable.insert {
                it[id] = session.id
                it[conversationId] = session.conversationId
                it[agent] = session.agent
                it[prompt] = session.prompt
                it[branch] = session.branch
                it[status] = session.status
                it[errorMessage] = session.errorMessage
                it[startTimeMs] = session.startTimeMs
                it[durationMs] = session.durationMs
                it[projectName] = session.projectName
                it[projectPath] = session.projectPath
                it[SessionsTable.sourceProject] = sourceProject
                it[createdAt] = session.createdAt
                it[updatedAt] = session.updatedAt
            }

            // Replace messages
            MessagesTable.deleteWhere { sessionId eq session.id }
            for (msg in messages) {
                MessagesTable.insert {
                    it[sessionId] = session.id
                    it[role] = msg.role
                    it[content] = msg.content
                    it[sequenceNum] = msg.sequenceNum
                    it[timestampMs] = msg.timestampMs
                }
            }

            // Replace tool calls
            ToolCallsTable.deleteWhere { sessionId eq session.id }
            for (tc in toolCalls) {
                ToolCallsTable.insert {
                    it[sessionId] = session.id
                    it[toolName] = tc.toolName
                    it[input] = tc.input
                    it[output] = tc.output
                    it[ToolCallsTable.status] = tc.status
                    it[durationMs] = tc.durationMs
                    it[sequenceNum] = tc.sequenceNum
                    it[timestampMs] = tc.timestampMs
                }
            }
        }
    }

    override fun dispose() {}
}
