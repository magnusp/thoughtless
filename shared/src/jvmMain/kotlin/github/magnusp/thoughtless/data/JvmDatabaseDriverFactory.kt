package github.magnusp.thoughtless.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

class JvmDatabaseDriverFactory(
    private val dbPath: String? = null
) : DatabaseDriverFactory {

    override fun createDriver(): SqlDriver {
        val path = dbPath
            ?: System.getProperty("thoughtless.db.path")
            ?: System.getenv("THOUGHTLESS_DB_PATH")
            ?: defaultDbPath()

        val driver = if (path == ":memory:" || path == JdbcSqliteDriver.IN_MEMORY) {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        } else {
            val dbFile = File(path)
            dbFile.parentFile?.mkdirs()
            JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        }

        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        return driver
    }

    companion object {
        fun defaultDbPath(): String {
            val userHome = System.getProperty("user.home") ?: "."
            val dir = File(userHome, ".thoughtless")
            dir.mkdirs()
            return File(dir, "thoughtless.db").absolutePath
        }

        fun inMemory(): JvmDatabaseDriverFactory =
            JvmDatabaseDriverFactory(dbPath = ":memory:")
    }
}

actual fun getDefaultDatabaseDriverFactory(): DatabaseDriverFactory =
    JvmDatabaseDriverFactory()

