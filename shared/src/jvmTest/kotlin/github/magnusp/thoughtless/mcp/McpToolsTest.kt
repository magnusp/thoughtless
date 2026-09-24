package github.magnusp.thoughtless.mcp

import github.magnusp.thoughtless.data.InMemoryTaskProposalRepository
import github.magnusp.thoughtless.data.InMemoryTaskRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBContextGraphRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBEngine
import github.magnusp.thoughtless.domain.model.AgentTaskStatus
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.mcp.tools.ThoughtlessMcpTools
import github.magnusp.thoughtless.service.DAGDecomposerService
import github.magnusp.thoughtless.service.TaskProposalService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpToolsTest {
    private lateinit var tempDir: File
    private lateinit var engine: ArcadeDBEngine
    private lateinit var taskRepo: InMemoryTaskRepository
    private lateinit var proposalRepo: InMemoryTaskProposalRepository
    private lateinit var graphRepo: ArcadeDBContextGraphRepository
    private lateinit var proposalService: TaskProposalService
    private lateinit var dagService: DAGDecomposerService
    private lateinit var tools: ThoughtlessMcpTools
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        tempDir = File.createTempFile("arcadedb-test", "").apply {
            delete()
            mkdirs()
        }
        engine = ArcadeDBEngine(tempDir.absolutePath)
        engine.open()

        taskRepo = InMemoryTaskRepository()
        proposalRepo = InMemoryTaskProposalRepository()
        graphRepo = ArcadeDBContextGraphRepository(engine)
        proposalService = TaskProposalService(proposalRepo, taskRepo, graphRepo)
        dagService = DAGDecomposerService(taskRepo, graphRepo)

        tools = ThoughtlessMcpTools(
            taskRepository = taskRepo,
            taskProposalRepository = proposalRepo,
            contextGraphRepository = graphRepo,
            taskProposalService = proposalService,
            dagDecomposerService = dagService,
            arcadeDBEngine = engine
        )
    }

    @AfterTest
    fun tearDown() {
        try {
            engine.close()
            tempDir.deleteRecursively()
        } catch (_: Exception) {}
    }

    @Test
    fun testToolDefinitionsListed() {
        val defs = tools.listToolDefinitions()
        val names = defs.map { it.name }.toSet()
        assertTrue("propose_task" in names)
        assertTrue("refine_task" in names)
        assertTrue("decompose_task" in names)
        assertTrue("validate_task_dag" in names)
        assertTrue("get_active_dag" in names)
        assertTrue("claim_next_task" in names)
        assertTrue("update_task_progress" in names)
        assertTrue("submit_task_for_review" in names)
    }

    @Test
    fun testProposeTaskValidAndInvalid() = runBlocking {
        // 1. Valid proposal
        val validArgs = buildJsonObject {
            put("title", "Fix memory leak in parser")
            put("rationale", "Observed out of memory on large files")
            put("suggestedWorkspace", "github.com/org/repo")
            put("suggestedTargetFile", "src/Parser.kt")
        }
        val result = tools.executeTool("propose_task", validArgs)
        assertFalse(result.isError)
        val body = json.parseToJsonElement(result.content[0].text).jsonObject
        assertNotNull(body["proposalId"])
        assertEquals("PROPOSED", body["status"]?.jsonPrimitive?.content)

        // 2. Invalid proposal with absolute path should fail validation
        val invalidArgs = buildJsonObject {
            put("title", "Forbidden path")
            put("rationale", "Testing invalid path")
            put("suggestedWorkspace", "/var/projects/repo")
        }
        val invalidResult = tools.executeTool("propose_task", invalidArgs)
        assertTrue(invalidResult.isError)
        assertTrue(invalidResult.content[0].text.contains("Validation error"))
    }

    @Test
    fun testClaimTaskAndProgressLifecycle() = runBlocking {
        // Setup a task in repo
        val task = Task(
            id = "task-100",
            title = "Implement storage engine",
            workspace = "github.com/org/repo",
            targetFile = "src/Storage.kt",
            contextFiles = listOf("docs/spec.md"),
            acceptanceCriteria = listOf("Passes integration tests"),
            status = TaskStatus.TODO,
            priority = TaskPriority.HIGH,
            agentStatus = AgentTaskStatus.PENDING,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        taskRepo.updateTask(task)

        // 1. Claim task
        val claimArgs = buildJsonObject {
            put("agentId", "agent-alpha-42")
        }
        val claimResult = tools.executeTool("claim_next_task", claimArgs)
        assertFalse(claimResult.isError)
        val claimBody = json.parseToJsonElement(claimResult.content[0].text).jsonObject
        assertTrue(claimBody["taskClaimed"]?.jsonPrimitive?.content == "true")
        assertEquals("task-100", claimBody["taskId"]?.jsonPrimitive?.content)
        assertEquals("AGENT_RUNNING", claimBody["agentStatus"]?.jsonPrimitive?.content)

        // 2. Update scratchpad progress
        val progressArgs = buildJsonObject {
            put("taskId", "task-100")
            put("currentStep", "Compiling classes")
            put("notes", "No errors so far")
            putJsonArray("touchedFiles") { add(kotlinx.serialization.json.JsonPrimitive("src/Storage.kt")) }
            putJsonArray("completedCriteria") { add(kotlinx.serialization.json.JsonPrimitive("Passes integration tests")) }
        }
        val progressResult = tools.executeTool("update_task_progress", progressArgs)
        assertFalse(progressResult.isError)

        // Verify task scratchpad updated in repo
        val savedTask = taskRepo.getTaskById("task-100").first()!!
        assertNotNull(savedTask.agentScratchpad)
        assertEquals("Compiling classes", savedTask.agentScratchpad?.currentStep)
        assertEquals(listOf("src/Storage.kt"), savedTask.agentScratchpad?.touchedFiles)

        // 3. Submit for review
        val reviewArgs = buildJsonObject {
            put("taskId", "task-100")
            put("reviewSummary", "Implementation complete with all unit tests green")
            put("testsPassed", true)
            put("diffUrlOrBranch", "agent/task-100")
        }
        val reviewResult = tools.executeTool("submit_task_for_review", reviewArgs)
        assertFalse(reviewResult.isError)

        val completedTask = taskRepo.getTaskById("task-100").first()!!
        assertEquals(AgentTaskStatus.AWAITING_REVIEW, completedTask.agentStatus)
        assertTrue(completedTask.agentScratchpad?.notes?.contains("Implementation complete") == true)
    }

    @Test
    fun testValidateTaskDagDisjointTargetInvariant() = runBlocking {
        // Two tasks targeting the same file should be sequenced into separate tiers
        val t1 = Task(
            id = "t1",
            title = "Task 1",
            workspace = "github.com/org/repo",
            targetFile = "src/Shared.kt",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val t2 = Task(
            id = "t2",
            title = "Task 2",
            workspace = "github.com/org/repo",
            targetFile = "src/Shared.kt",
            createdAt = 2000L,
            updatedAt = 2000L
        )
        taskRepo.updateTask(t1)
        taskRepo.updateTask(t2)

        val result = tools.executeTool("validate_task_dag", buildJsonObject {})
        assertFalse(result.isError)
        val body = json.parseToJsonElement(result.content[0].text).jsonObject
        assertEquals("true", body["isValid"]?.jsonPrimitive?.content)
        val tiers = body["executionTiers"]?.jsonArray!!
        assertEquals(2, tiers.size) // Disjoint target invariant forces 2 distinct tiers
    }
}
