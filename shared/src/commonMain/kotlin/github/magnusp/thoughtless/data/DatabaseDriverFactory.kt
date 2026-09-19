package github.magnusp.thoughtless.data

import app.cash.sqldelight.db.SqlDriver
import github.magnusp.thoughtless.db.ThoughtlessDatabase

interface DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}

expect fun getDefaultDatabaseDriverFactory(): DatabaseDriverFactory

class DatabaseFactory(private val driverFactory: DatabaseDriverFactory) {
    fun createDatabase(): ThoughtlessDatabase {
        val driver = driverFactory.createDriver()
        ThoughtlessDatabase.Schema.create(driver)
        return ThoughtlessDatabase(driver)
    }
}

