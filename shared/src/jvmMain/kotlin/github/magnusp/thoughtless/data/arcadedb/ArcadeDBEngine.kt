package github.magnusp.thoughtless.data.arcadedb

import com.arcadedb.database.Database
import com.arcadedb.database.DatabaseFactory
import com.arcadedb.schema.Schema
import com.arcadedb.schema.Type
import java.io.File

/**
 * ArcadeDB embedded database engine manager and schema initializer.
 */
class ArcadeDBEngine(
    val databasePath: String = DEFAULT_DATABASE_PATH,
) : AutoCloseable {

    companion object {
        val DEFAULT_DATABASE_PATH: String =
            System.getProperty("user.home") + File.separator + ".thoughtless" + File.separator + "graph"

        private var defaultInstance: ArcadeDBEngine? = null

        @Synchronized
        fun getDefault(): ArcadeDBEngine {
            return defaultInstance ?: ArcadeDBEngine().also { defaultInstance = it }
        }
    }

    private var factory: DatabaseFactory? = null
    private var db: Database? = null

    /**
     * Checks if the database is open.
     */
    fun isOpen(): Boolean = synchronized(this) {
        db?.isOpen == true
    }

    /**
     * Returns the active [Database] instance. Throws [IllegalStateException] if the database is not open.
     */
    val database: Database
        get() = synchronized(this) {
            val current = db
            if (current == null || !current.isOpen) {
                error("Database at '$databasePath' is not open. Call open() first.")
            }
            current
        }

    /**
     * Opens or creates the embedded ArcadeDB database and ensures the schema is initialized.
     */
    @Synchronized
    fun open(): Database {
        if (db?.isOpen == true) {
            return db!!
        }

        val dbDir = File(databasePath)
        if (!dbDir.exists()) {
            dbDir.mkdirs()
        }

        val factory = DatabaseFactory(databasePath)
        this.factory = factory

        val database = if (factory.exists()) {
            factory.open()
        } else {
            factory.create()
        }
        this.db = database

        initSchema(database)

        return database
    }

    /**
     * Executes the given [block] within a database transaction.
     */
    inline fun <T> transaction(crossinline block: (Database) -> T): T {
        val db = database
        return if (db.isTransactionActive) {
            block(db)
        } else {
            db.begin()
            try {
                val result = block(db)
                db.commit()
                result
            } catch (t: Throwable) {
                try {
                    db.rollback()
                } catch (rollbackEx: Throwable) {
                    t.addSuppressed(rollbackEx)
                }
                throw t
            }
        }
    }

    /**
     * Closes the database and its factory.
     */
    @Synchronized
    override fun close() {
        try {
            db?.let {
                if (it.isOpen) {
                    it.close()
                }
            }
        } finally {
            db = null
            try {
                factory?.close()
            } finally {
                factory = null
            }
        }
    }

    private fun initSchema(database: Database) {
        val schema = database.schema

        // 1. Task vertex type
        val taskType = schema.getOrCreateVertexType("Task")
        taskType.getOrCreateProperty("id", Type.STRING)
        taskType.getOrCreateProperty("projectId", Type.STRING)
        taskType.getOrCreateProperty("title", Type.STRING)
        taskType.getOrCreateProperty("description", Type.STRING)
        taskType.getOrCreateProperty("status", Type.STRING)
        taskType.getOrCreateProperty("priority", Type.LONG)
        taskType.getOrCreateProperty("dueDate", Type.LONG)
        taskType.getOrCreateProperty("createdAt", Type.LONG)
        taskType.getOrCreateProperty("updatedAt", Type.LONG)
        taskType.getOrCreateProperty("completedAt", Type.LONG)
        taskType.getOrCreateProperty("type", Type.STRING)
        taskType.getOrCreateProperty("contextFiles", Type.LIST)
        taskType.getOrCreateProperty("targetFile", Type.STRING)
        taskType.getOrCreateProperty("acceptanceCriteria", Type.LIST)
        taskType.getOrCreateProperty("dependsOn", Type.LIST)
        taskType.getOrCreateProperty("milestoneId", Type.STRING)
        taskType.getOrCreateProperty("canInstallPackages", Type.BOOLEAN)
        taskType.getOrCreateProperty("allowedCommands", Type.LIST)
        taskType.getOrCreateProperty("agentStatus", Type.STRING)
        taskType.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "id")

        // 2. Project vertex type
        val projectType = schema.getOrCreateVertexType("Project")
        projectType.getOrCreateProperty("id", Type.STRING)
        projectType.getOrCreateProperty("name", Type.STRING)
        projectType.getOrCreateProperty("description", Type.STRING)
        projectType.getOrCreateProperty("color", Type.STRING)
        projectType.getOrCreateProperty("createdAt", Type.LONG)
        projectType.getOrCreateProperty("updatedAt", Type.LONG)
        projectType.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "id")

        // 3. Label vertex type
        val labelType = schema.getOrCreateVertexType("Label")
        labelType.getOrCreateProperty("id", Type.STRING)
        labelType.getOrCreateProperty("name", Type.STRING)
        labelType.getOrCreateProperty("color", Type.STRING)
        labelType.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "id")

        // 4. Milestone vertex type
        val milestoneType = schema.getOrCreateVertexType("Milestone")
        milestoneType.getOrCreateProperty("id", Type.STRING)
        milestoneType.getOrCreateProperty("name", Type.STRING)
        milestoneType.getOrCreateProperty("version", Type.STRING)
        milestoneType.getOrCreateProperty("description", Type.STRING)
        milestoneType.getOrCreateProperty("projectId", Type.STRING)
        milestoneType.getOrCreateProperty("createdAt", Type.LONG)
        milestoneType.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "id")

        // 5. ContextNode vertex type
        val contextNodeType = schema.getOrCreateVertexType("ContextNode")
        contextNodeType.getOrCreateProperty("id", Type.STRING)
        contextNodeType.getOrCreateProperty("type", Type.STRING)
        contextNodeType.getOrCreateProperty("label", Type.STRING)
        contextNodeType.getOrCreateProperty("body", Type.STRING)
        contextNodeType.getOrCreateProperty("embedding", Type.ARRAY_OF_FLOATS)
        contextNodeType.getOrCreateProperty("filePath", Type.STRING)
        contextNodeType.getOrCreateProperty("createdAt", Type.LONG)
        contextNodeType.getOrCreateProperty("updatedAt", Type.LONG)
        contextNodeType.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "id")

        // Vector index on ContextNode.embedding if not already present
        val existingVectorIndex = contextNodeType.getAllIndexes(true).any { index ->
            index.type == Schema.INDEX_TYPE.LSM_VECTOR && index.propertyNames.contains("embedding")
        }
        if (!existingVectorIndex) {
            try {
                schema.buildTypeIndex("ContextNode", arrayOf("embedding"))
                    .withLSMVectorType()
                    .withDimensions(384)
                    .withSimilarity("Cosine")
                    .withIgnoreIfExists(true)
                    .create()
            } catch (ignored: Exception) {
                // Ignore if vector index cannot be built or already exists
            }
        }

        // 6. Spec vertex type
        val specType = schema.getOrCreateVertexType("Spec")
        specType.getOrCreateProperty("id", Type.STRING)
        specType.getOrCreateProperty("projectId", Type.STRING)
        specType.getOrCreateProperty("title", Type.STRING)
        specType.getOrCreateProperty("systemSpec", Type.STRING)
        specType.getOrCreateProperty("nonGoals", Type.STRING)
        specType.getOrCreateProperty("rfcDocument", Type.STRING)
        specType.getOrCreateProperty("frozenAt", Type.LONG)
        specType.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "id")

        // Edge types
        schema.getOrCreateEdgeType("HAS_TASK")
        schema.getOrCreateEdgeType("HAS_LABEL")
        schema.getOrCreateEdgeType("REFERENCES")
        schema.getOrCreateEdgeType("IMPLEMENTS")
        schema.getOrCreateEdgeType("MUTATES")
        schema.getOrCreateEdgeType("DEPENDS_ON")
    }
}
