package com.speckit.plugin.persistence

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class SchemaManager(private val db: Database) {

    private val migrations: List<Migration> = listOf(
        Migration(1, ::migrationV1)
    )

    fun migrate() {
        transaction(db) {
            SchemaUtils.create(SchemaVersionTable)
        }
        val currentVersion = transaction(db) {
            SchemaVersionTable.selectAll()
                .maxByOrNull { it[SchemaVersionTable.version] }
                ?.get(SchemaVersionTable.version) ?: 0
        }
        for (migration in migrations) {
            if (migration.version > currentVersion) {
                transaction(db) {
                    migration.action()
                    SchemaVersionTable.insert {
                        it[version] = migration.version
                        it[appliedAt] = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    private fun migrationV1() {
        SchemaUtils.create(
            SessionsTable,
            MessagesTable,
            ToolCallsTable,
            ArtifactsTable,
            SessionTagsTable
        )
    }

    private class Migration(val version: Int, val action: () -> Unit)
}
