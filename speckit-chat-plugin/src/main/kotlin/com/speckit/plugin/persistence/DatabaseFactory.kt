package com.speckit.plugin.persistence

import org.jetbrains.exposed.sql.Database
import java.io.File

object DatabaseFactory {

    fun connect(dbFile: File): Database {
        dbFile.parentFile?.mkdirs()
        // Set WAL mode via connection property — must be outside a transaction
        val url = "jdbc:sqlite:${dbFile.absolutePath}?journal_mode=WAL"
        val db = Database.connect(url, driver = "org.sqlite.JDBC")

        org.jetbrains.exposed.sql.transactions.transaction(db) {
            val conn = (connection as org.jetbrains.exposed.sql.statements.jdbc.JdbcConnectionImpl).connection
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA foreign_keys=ON;")
                stmt.execute("PRAGMA busy_timeout=5000;")
            }
        }

        return db
    }
}
