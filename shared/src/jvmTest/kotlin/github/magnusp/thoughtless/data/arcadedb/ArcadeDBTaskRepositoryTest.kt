package github.magnusp.thoughtless.data.arcadedb

import github.magnusp.thoughtless.domain.model.AgentPermissions
import github.magnusp.thoughtless.domain.model.AgentTaskStatus
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.domain.model.TaskType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArcadeDBTaskRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var engine: ArcadeDBEngine
    private lateinit var taskRepo: ArcadeDBTaskRepository
    private lateinit var projectRepo: ArcadeDBProjectRepository

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("task-repo-test").toFile()
        engine = ArcadeDBEngine(tempDir.absolutePath)
        taskRepo = ArcadeDBTaskRepository(engine)
        projectRepo = ArcadeDBProjectRepository(engine)
    }

    @AfterTest
    fun tearDown() {
        engine.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun testTaskCrud() = runBlocking {
        // Create task
        val created = taskRepo.createTask(
            title = "Build Task Tracker",
            description = "Implement ArcadeDB backing store",
            priority = TaskPriority.HIGH,
            dueDate = 1800000000000L,
        )

        assertNotNull(created.id)
        assertEquals("Build Task Tracker", created.title)
        assertEquals("Implement ArcadeDB backing store", created.description)
        assertEquals(TaskStatus.TODO, created.status)
        assertEquals(TaskPriority.HIGH, created.priority)
        assertEquals(1800000000000L, created.dueDate)
        assertNull(created.completedAt)

        // Read by ID
        val fetched = taskRepo.getTaskById(created.id).first()
        assertNotNull(fetched)
        assertEquals(created.id, fetched.id)
        assertEquals(created.title, fetched.title)

        // Update task status
        taskRepo.updateTaskStatus(created.id, TaskStatus.DONE)
        val doneTask = taskRepo.getTaskById(created.id).first()
        assertNotNull(doneTask)
        assertEquals(TaskStatus.DONE, doneTask.status)
        assertNotNull(doneTask.completedAt)

        // Update other properties
        val updatedModel = doneTask.copy(
            title = "Build Task Tracker (Done)",
            type = TaskType.SPIKE,
            targetFile = "TaskRepo.kt",
            contextFiles = listOf("ArcadeDBEngine.kt"),
            acceptanceCriteria = listOf("Passes tests"),
            dependsOn = listOf("task-0"),
            agentPermissions = AgentPermissions(canInstallPackages = true, allowedCommands = listOf("gradle test")),
            agentStatus = AgentTaskStatus.MERGED,
        )
        taskRepo.updateTask(updatedModel)

        val updatedTask = taskRepo.getTaskById(created.id).first()
        assertNotNull(updatedTask)
        assertEquals("Build Task Tracker (Done)", updatedTask.title)
        assertEquals(TaskType.SPIKE, updatedTask.type)
        assertEquals("TaskRepo.kt", updatedTask.targetFile)
        assertEquals(listOf("ArcadeDBEngine.kt"), updatedTask.contextFiles)
        assertEquals(listOf("Passes tests"), updatedTask.acceptanceCriteria)
        assertEquals(listOf("task-0"), updatedTask.dependsOn)
        val permissions = updatedTask.agentPermissions
        assertNotNull(permissions)
        assertTrue(permissions.canInstallPackages)
        assertEquals(listOf("gradle test"), permissions.allowedCommands)
        assertEquals(AgentTaskStatus.MERGED, updatedTask.agentStatus)

        // Delete task
        taskRepo.deleteTask(created.id)
        val deleted = taskRepo.getTaskById(created.id).first()
        assertNull(deleted)
    }

    @Test
    fun testTaskProjectFilteringAndInbox() = runBlocking {
        val proj1 = projectRepo.createProject("Project 1")
        val proj2 = projectRepo.createProject("Project 2")

        val t1 = taskRepo.createTask(title = "Inbox Task")
        val t2 = taskRepo.createTask(title = "Project 1 Task", projectId = proj1.id)
        val t3 = taskRepo.createTask(title = "Project 2 Task", projectId = proj2.id)

        val inbox = taskRepo.getInboxTasks().first()
        assertEquals(1, inbox.size)
        assertEquals(t1.id, inbox.first().id)

        val p1Tasks = taskRepo.getTasksByProject(proj1.id).first()
        assertEquals(1, p1Tasks.size)
        assertEquals(t2.id, p1Tasks.first().id)

        val allTasks = taskRepo.getTasks().first()
        assertEquals(3, allTasks.size)

        // Delete by project
        taskRepo.deleteTasksByProject(proj1.id)
        val p1After = taskRepo.getTasksByProject(proj1.id).first()
        assertEquals(0, p1After.size)

        val remaining = taskRepo.getTasks().first()
        assertEquals(2, remaining.size)
    }

    @Test
    fun testReactiveFlowUpdates() = runBlocking {
        val collectedLists = mutableListOf<List<Task>>()
        val flowStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            taskRepo.getTasks().take(4).collect {
                collectedLists.add(it)
                if (collectedLists.size == 1) {
                    flowStarted.complete(Unit)
                }
            }
        }

        flowStarted.await()
        kotlinx.coroutines.delay(50)

        val t1 = taskRepo.createTask("Task 1")
        kotlinx.coroutines.delay(50)
        val t2 = taskRepo.createTask("Task 2")
        kotlinx.coroutines.delay(50)
        taskRepo.deleteTask(t1.id)

        job.join()

        // 4 emissions: initial (empty), after t1 (1), after t2 (2), after delete (1)
        assertEquals(4, collectedLists.size)
        assertEquals(0, collectedLists[0].size)
        assertEquals(1, collectedLists[1].size)
        assertEquals(2, collectedLists[2].size)
        assertEquals(1, collectedLists[3].size)
        assertEquals(t2.id, collectedLists[3][0].id)
    }
}
