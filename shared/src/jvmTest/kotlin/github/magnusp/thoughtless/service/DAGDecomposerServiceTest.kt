package github.magnusp.thoughtless.service

import github.magnusp.thoughtless.data.arcadedb.ArcadeDBContextGraphRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBEngine
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBTaskRepository
import github.magnusp.thoughtless.domain.model.AgentPermissions
import github.magnusp.thoughtless.domain.model.Spec
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DAGDecomposerServiceTest {

    private lateinit var tempDir: File
    private lateinit var engine: ArcadeDBEngine
    private lateinit var taskRepo: ArcadeDBTaskRepository
    private lateinit var graphRepo: ArcadeDBContextGraphRepository
    private lateinit var decomposer: DAGDecomposerService

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("dag-decomposer-test").toFile()
        engine = ArcadeDBEngine(tempDir.absolutePath)
        taskRepo = ArcadeDBTaskRepository(engine)
        graphRepo = ArcadeDBContextGraphRepository(engine)
        decomposer = DAGDecomposerService(taskRepo, graphRepo)
    }

    @AfterTest
    fun tearDown() {
        engine.close()
        tempDir.deleteRecursively()
    }

    private fun createTask(
        id: String,
        title: String,
        dependsOn: List<String> = emptyList(),
        targetFile: String? = null,
        type: TaskType = TaskType.TASK,
    ): Task {
        return Task(
            id = id,
            projectId = "proj-1",
            title = title,
            description = "Description for $title",
            type = type,
            targetFile = targetFile,
            dependsOn = dependsOn,
            createdAt = 1000L,
            updatedAt = 1000L,
            agentPermissions = AgentPermissions(
                canInstallPackages = false,
                allowedCommands = listOf("./gradlew test"),
            ),
        )
    }

    @Test
    fun testTopologicalSortLinearDAG() {
        val t1 = createTask("task-1", "Step 1")
        val t2 = createTask("task-2", "Step 2", dependsOn = listOf("task-1"))
        val t3 = createTask("task-3", "Step 3", dependsOn = listOf("task-2"))

        val (order, tiers) = decomposer.topologicalSort(listOf(t3, t1, t2))

        assertEquals(listOf("task-1", "task-2", "task-3"), order)
        assertEquals(3, tiers.size)
        assertEquals(listOf("task-1"), tiers[0])
        assertEquals(listOf("task-2"), tiers[1])
        assertEquals(listOf("task-3"), tiers[2])
    }

    @Test
    fun testTopologicalSortBranchingDAG() {
        // t1 -> t2, t3
        // t2 -> t4
        // t3 -> t4
        val t1 = createTask("task-1", "Base")
        val t2 = createTask("task-2", "Branch A", dependsOn = listOf("task-1"))
        val t3 = createTask("task-3", "Branch B", dependsOn = listOf("task-1"))
        val t4 = createTask("task-4", "Merge", dependsOn = listOf("task-2", "task-3"))

        val (order, tiers) = decomposer.topologicalSort(listOf(t4, t3, t2, t1))

        assertEquals(3, tiers.size)
        assertEquals(listOf("task-1"), tiers[0])
        assertTrue(tiers[1].contains("task-2") && tiers[1].contains("task-3"))
        assertEquals(listOf("task-4"), tiers[2])

        // Verify order maintains invariants
        assertTrue(order.indexOf("task-1") < order.indexOf("task-2"))
        assertTrue(order.indexOf("task-1") < order.indexOf("task-3"))
        assertTrue(order.indexOf("task-2") < order.indexOf("task-4"))
        assertTrue(order.indexOf("task-3") < order.indexOf("task-4"))
    }

    @Test
    fun testCycleDetection() {
        // Cycle: t1 -> t2 -> t3 -> t1
        val t1 = createTask("task-1", "Step 1", dependsOn = listOf("task-3"))
        val t2 = createTask("task-2", "Step 2", dependsOn = listOf("task-1"))
        val t3 = createTask("task-3", "Step 3", dependsOn = listOf("task-2"))

        assertFailsWith<CycleDetectedException> {
            decomposer.topologicalSort(listOf(t1, t2, t3))
        }
    }

    @Test
    fun testDecomposeAndPersistWithGraphEdgesAndJsonExport() = runBlocking {
        val spec = Spec(
            id = "spec-auth-passkeys",
            projectId = "proj-1",
            title = "Passkey Spec",
            systemSpec = "Passkey auth flow",
            rfcDocument = "# Passkeys",
            frozenAt = 2000L,
        )

        val t1 = createTask("task-1", "Domain Models", targetFile = "User.kt")
        val t2 = createTask("task-2", "Repository", dependsOn = listOf("task-1"), targetFile = "UserRepo.kt")
        val t3 = createTask("task-3", "API Controller", dependsOn = listOf("task-2"), targetFile = "UserController.kt")

        val dagExport = decomposer.decomposeAndPersist(spec, listOf(t3, t1, t2))

        assertEquals("spec-auth-passkeys", dagExport.specId)
        assertEquals("proj-1", dagExport.projectId)
        assertEquals(3, dagExport.totalTasks)
        assertEquals(listOf("task-1", "task-2", "task-3"), dagExport.topologicalOrder)

        // Verify persisted to TaskRepository
        val allTasks = taskRepo.getTasks().first()
        assertEquals(3, allTasks.size)
        assertTrue(allTasks.any { it.id == "task-1" && it.targetFile == "User.kt" })
        assertTrue(allTasks.any { it.id == "task-2" && it.targetFile == "UserRepo.kt" })
        assertTrue(allTasks.any { it.id == "task-3" && it.targetFile == "UserController.kt" })

        // Verify DEPENDS_ON edges in ContextGraphRepository
        val allEdges = graphRepo.getEdges().first()
        assertEquals(2, allEdges.size)
        assertTrue(allEdges.any { it.fromId == "task-2" && it.toId == "task-1" && it.relation == "DEPENDS_ON" })
        assertTrue(allEdges.any { it.fromId == "task-3" && it.toId == "task-2" && it.relation == "DEPENDS_ON" })

        // Verify JSON export
        val jsonString = decomposer.exportToJson(dagExport)
        assertNotNull(jsonString)
        val parsedJson = Json.parseToJsonElement(jsonString).jsonObject
        assertEquals("spec-auth-passkeys", parsedJson["specId"]?.toString()?.replace("\"", ""))
        assertEquals(3, parsedJson["totalTasks"]?.toString()?.toInt())
    }
}
