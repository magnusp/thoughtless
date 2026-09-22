package github.magnusp.thoughtless.data.arcadedb

import com.arcadedb.schema.Schema
import com.arcadedb.schema.Type
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArcadeDBEngineTest {

    private lateinit var tempDir: File
    private var engine: ArcadeDBEngine? = null

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("thoughtless-arcadedb-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        engine?.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun testDatabaseCreationInTempFolder() {
        val arcadeEngine = ArcadeDBEngine(tempDir.absolutePath)
        engine = arcadeEngine

        assertFalse(arcadeEngine.isOpen())

        val db = arcadeEngine.open()
        assertTrue(arcadeEngine.isOpen())
        assertTrue(db.isOpen)
        assertEquals(tempDir.absolutePath, arcadeEngine.databasePath)

        arcadeEngine.close()
        assertFalse(arcadeEngine.isOpen())
    }

    @Test
    fun testSchemaVerification() {
        val arcadeEngine = ArcadeDBEngine(tempDir.absolutePath)
        engine = arcadeEngine
        val db = arcadeEngine.open()

        val schema = db.schema

        // Check vertex types
        val expectedVertexTypes = listOf("Task", "Project", "Label", "Milestone", "ContextNode", "Spec")
        for (typeName in expectedVertexTypes) {
            assertTrue(schema.existsType(typeName), "Vertex type $typeName should exist")
            val type = schema.getType(typeName)
            assertNotNull(type, "Type $typeName must not be null")
            assertTrue(type.existsProperty("id"), "Type $typeName must have 'id' property")
        }

        // Check Task properties
        val taskType = schema.getType("Task")
        val expectedTaskProps = mapOf(
            "id" to Type.STRING,
            "projectId" to Type.STRING,
            "title" to Type.STRING,
            "description" to Type.STRING,
            "status" to Type.STRING,
            "priority" to Type.LONG,
            "dueDate" to Type.LONG,
            "createdAt" to Type.LONG,
            "updatedAt" to Type.LONG,
            "completedAt" to Type.LONG,
            "type" to Type.STRING,
            "contextFiles" to Type.LIST,
            "targetFile" to Type.STRING,
            "acceptanceCriteria" to Type.LIST,
            "dependsOn" to Type.LIST,
            "milestoneId" to Type.STRING,
            "canInstallPackages" to Type.BOOLEAN,
            "allowedCommands" to Type.LIST,
            "agentStatus" to Type.STRING,
        )
        for ((propName, propType) in expectedTaskProps) {
            assertTrue(taskType.existsProperty(propName), "Task property $propName must exist")
            assertEquals(propType, taskType.getProperty(propName).type, "Task property $propName type mismatch")
        }

        // Check Project properties
        val projectType = schema.getType("Project")
        val expectedProjectProps = mapOf(
            "id" to Type.STRING,
            "name" to Type.STRING,
            "description" to Type.STRING,
            "color" to Type.STRING,
            "createdAt" to Type.LONG,
            "updatedAt" to Type.LONG,
        )
        for ((propName, propType) in expectedProjectProps) {
            assertTrue(projectType.existsProperty(propName), "Project property $propName must exist")
            assertEquals(propType, projectType.getProperty(propName).type, "Project property $propName type mismatch")
        }

        // Check Label properties
        val labelType = schema.getType("Label")
        val expectedLabelProps = mapOf(
            "id" to Type.STRING,
            "name" to Type.STRING,
            "color" to Type.STRING,
        )
        for ((propName, propType) in expectedLabelProps) {
            assertTrue(labelType.existsProperty(propName), "Label property $propName must exist")
            assertEquals(propType, labelType.getProperty(propName).type, "Label property $propName type mismatch")
        }

        // Check Milestone properties
        val milestoneType = schema.getType("Milestone")
        val expectedMilestoneProps = mapOf(
            "id" to Type.STRING,
            "name" to Type.STRING,
            "version" to Type.STRING,
            "description" to Type.STRING,
            "projectId" to Type.STRING,
            "createdAt" to Type.LONG,
        )
        for ((propName, propType) in expectedMilestoneProps) {
            assertTrue(milestoneType.existsProperty(propName), "Milestone property $propName must exist")
            assertEquals(propType, milestoneType.getProperty(propName).type, "Milestone property $propName type mismatch")
        }

        // Check ContextNode properties
        val contextNodeType = schema.getType("ContextNode")
        val expectedContextProps = mapOf(
            "id" to Type.STRING,
            "type" to Type.STRING,
            "label" to Type.STRING,
            "body" to Type.STRING,
            "embedding" to Type.ARRAY_OF_FLOATS,
            "filePath" to Type.STRING,
            "createdAt" to Type.LONG,
            "updatedAt" to Type.LONG,
        )
        for ((propName, propType) in expectedContextProps) {
            assertTrue(contextNodeType.existsProperty(propName), "ContextNode property $propName must exist")
            assertEquals(propType, contextNodeType.getProperty(propName).type, "ContextNode property $propName type mismatch")
        }

        // Check Spec properties
        val specType = schema.getType("Spec")
        val expectedSpecProps = mapOf(
            "id" to Type.STRING,
            "projectId" to Type.STRING,
            "title" to Type.STRING,
            "systemSpec" to Type.STRING,
            "nonGoals" to Type.STRING,
            "rfcDocument" to Type.STRING,
            "frozenAt" to Type.LONG,
        )
        for ((propName, propType) in expectedSpecProps) {
            assertTrue(specType.existsProperty(propName), "Spec property $propName must exist")
            assertEquals(propType, specType.getProperty(propName).type, "Spec property $propName type mismatch")
        }

        // Check Edge types
        val expectedEdgeTypes = listOf("HAS_TASK", "HAS_LABEL", "REFERENCES", "IMPLEMENTS", "MUTATES", "DEPENDS_ON")
        for (edgeName in expectedEdgeTypes) {
            assertTrue(schema.existsType(edgeName), "Edge type $edgeName should exist")
        }

        // Check vector index on ContextNode
        val vectorIndex = contextNodeType.getAllIndexes(true).firstOrNull {
            it.type == Schema.INDEX_TYPE.LSM_VECTOR && it.propertyNames.contains("embedding")
        }
        assertNotNull(vectorIndex, "LSM_VECTOR index on ContextNode.embedding should exist")
    }

    @Test
    fun testTransactionalWriteAndRead() {
        val arcadeEngine = ArcadeDBEngine(tempDir.absolutePath)
        engine = arcadeEngine
        arcadeEngine.open()

        arcadeEngine.transaction { db ->
            val vertex = db.newVertex("Task")
            vertex.set("id", "task-100")
            vertex.set("title", "Implement embedded DB")
            vertex.set("status", "IN_PROGRESS")
            vertex.set("priority", 2L)
            vertex.set("createdAt", 1700000000000L)
            vertex.set("updatedAt", 1700000000000L)
            vertex.set("contextFiles", listOf("ArcadeDBEngine.kt"))
            vertex.save()
        }

        arcadeEngine.transaction { db ->
            val cursor = db.lookupByKey("Task", "id", "task-100")
            assertTrue(cursor.hasNext(), "Should find saved vertex by key")
            val record = cursor.next().asVertex()
            assertEquals("task-100", record.getString("id"))
            assertEquals("Implement embedded DB", record.getString("title"))
            assertEquals("IN_PROGRESS", record.getString("status"))
            assertEquals(2L, record.getLong("priority"))
            assertEquals(listOf("ArcadeDBEngine.kt"), record.getList<String>("contextFiles"))
        }
    }

    @Test
    fun testDatabaseRestartPersistence() {
        var arcadeEngine = ArcadeDBEngine(tempDir.absolutePath)
        arcadeEngine.open()

        arcadeEngine.transaction { db ->
            val project = db.newVertex("Project")
            project.set("id", "proj-1")
            project.set("name", "Thoughtless Core")
            project.set("description", "Core project")
            project.set("color", "#FF5733")
            project.set("createdAt", 1700000000000L)
            project.set("updatedAt", 1700000000000L)
            project.save()
        }

        arcadeEngine.close()
        assertFalse(arcadeEngine.isOpen())

        // Re-open with a new ArcadeDBEngine instance pointing to the same directory
        arcadeEngine = ArcadeDBEngine(tempDir.absolutePath)
        engine = arcadeEngine
        val db = arcadeEngine.open()
        assertTrue(arcadeEngine.isOpen())

        // Verify schema is intact
        assertTrue(db.schema.existsType("Project"))
        assertTrue(db.schema.existsType("Task"))
        assertTrue(db.schema.existsType("HAS_TASK"))

        // Verify data persists
        arcadeEngine.transaction { txDb ->
            val cursor = txDb.lookupByKey("Project", "id", "proj-1")
            assertTrue(cursor.hasNext(), "Project proj-1 should persist after restart")
            val vertex = cursor.next().asVertex()
            assertEquals("proj-1", vertex.getString("id"))
            assertEquals("Thoughtless Core", vertex.getString("name"))
            assertEquals("#FF5733", vertex.getString("color"))
        }
    }
}
