package com.speckit.plugin.persistence

import org.jetbrains.exposed.sql.Table

object SchemaVersionTable : Table("schema_version") {
    val version = integer("version")
    val appliedAt = long("applied_at")
}

object SessionsTable : Table("sessions") {
    val id = text("id")                           // Copilot's session.id (UUID string)
    val conversationId = text("conversation_id").nullable()
    val agent = text("agent")
    val prompt = text("prompt")
    val branch = text("branch")
    val status = text("status")                   // RUNNING, COMPLETED, FAILED, CANCELLED
    val errorMessage = text("error_message").nullable()
    val startTimeMs = long("start_time_ms")
    val durationMs = long("duration_ms").default(0)
    val projectName = text("project_name").nullable()
    val projectPath = text("project_path").nullable()
    val sourceProject = text("source_project").nullable()  // used in user-level DB
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object MessagesTable : Table("messages") {
    val id = integer("id").autoIncrement()
    val sessionId = text("session_id").references(SessionsTable.id)
    val role = text("role")                       // USER, ASSISTANT, SYSTEM
    val content = text("content")
    val sequenceNum = integer("sequence_num")
    val timestampMs = long("timestamp_ms")

    override val primaryKey = PrimaryKey(id)
}

object ToolCallsTable : Table("tool_calls") {
    val id = integer("id").autoIncrement()
    val sessionId = text("session_id").references(SessionsTable.id)
    val toolName = text("tool_name")
    val input = text("input")                     // JSON
    val output = text("output")                   // JSON
    val status = text("status")
    val durationMs = long("duration_ms").default(0)
    val sequenceNum = integer("sequence_num")
    val timestampMs = long("timestamp_ms")

    override val primaryKey = PrimaryKey(id)
}

object ArtifactsTable : Table("artifacts") {
    val id = integer("id").autoIncrement()
    val sessionId = text("session_id").references(SessionsTable.id)
    val filePath = text("file_path")
    val artifactType = text("artifact_type")
    val contentHash = text("content_hash").nullable()

    override val primaryKey = PrimaryKey(id)
}

object SessionTagsTable : Table("session_tags") {
    val sessionId = text("session_id").references(SessionsTable.id)
    val key = text("key")
    val value = text("value")

    override val primaryKey = PrimaryKey(sessionId, key)
}
