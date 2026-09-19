package github.magnusp.thoughtless.data

import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightRepositoryTest {

    private lateinit var tempDbFile: File
    private lateinit var databaseFactory: DatabaseFactory
    private lateinit var taskRepository: SqlDelightTaskRepository
    private lateinit var projectRepository: SqlDelightProjectRepository
    private lateinit var labelRepository: SqlDelightLabelRepository

    @BeforeTest
    fun setup() {
        tempDbFile = File.createTempFile("thoughtless_test", ".db")
        tempDbFile.deleteOnExit()
        val driverFactory = JvmDatabaseDriverFactory(tempDbFile.absolutePath)
        databaseFactory = DatabaseFactory(driverFactory)
        val database = databaseFactory.createDatabase()
        val testDispatcher = kotlinx.coroutines.Dispatchers.Unconfined

        taskRepository = SqlDelightTaskRepository(database, testDispatcher)
        projectRepository = SqlDelightProjectRepository(database, testDispatcher)
        labelRepository = SqlDelightLabelRepository(database, testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        if (tempDbFile.exists()) {
            tempDbFile.delete()
        }
    }

    @Test
    fun testCreateAndFetchTask() = runTest {
        val created = taskRepository.createTask(
            title = "Write tests",
            description = "Ensure everything is covered",
            priority = TaskPriority.HIGH,
        )

        val tasks = taskRepository.getTasks().first()
        assertEquals(1, tasks.size)
        val task = tasks.first()
        assertEquals(created.id, task.id)
        assertEquals("Write tests", task.title)
        assertEquals("Ensure everything is covered", task.description)
        assertEquals(TaskPriority.HIGH, task.priority)
        assertEquals(TaskStatus.TODO, task.status)
        assertNull(task.completedAt)
    }

    @Test
    fun testUpdateTaskStatusAndCompletion() = runTest {
        val created = taskRepository.createTask(title = "Task to complete")
        taskRepository.updateTaskStatus(created.id, TaskStatus.DONE)

        val updated = taskRepository.getTaskById(created.id).first()
        assertNotNull(updated)
        assertEquals(TaskStatus.DONE, updated.status)
        assertNotNull(updated.completedAt)

        // Change back to TODO
        taskRepository.updateTaskStatus(created.id, TaskStatus.TODO)
        val reopened = taskRepository.getTaskById(created.id).first()
        assertNotNull(reopened)
        assertEquals(TaskStatus.TODO, reopened.status)
        assertNull(reopened.completedAt)
    }

    @Test
    fun testDeleteTask() = runTest {
        val created = taskRepository.createTask(title = "To be deleted")
        assertEquals(1, taskRepository.getTasks().first().size)

        taskRepository.deleteTask(created.id)
        assertTrue(taskRepository.getTasks().first().isEmpty())
    }

    @Test
    fun testProjectsAndTaskAssociation() = runTest {
        val project = projectRepository.createProject(
            name = "Project Alpha",
            description = "First test project",
            color = "#3B82F6",
        )

        val task1 = taskRepository.createTask(
            title = "Task in project",
            projectId = project.id,
        )
        val task2 = taskRepository.createTask(
            title = "Inbox task",
            projectId = null,
        )

        val projectTasks = taskRepository.getTasksByProject(project.id).first()
        assertEquals(1, projectTasks.size)
        assertEquals(task1.id, projectTasks.first().id)

        val inboxTasks = taskRepository.getInboxTasks().first()
        assertEquals(1, inboxTasks.size)
        assertEquals(task2.id, inboxTasks.first().id)

        val allTasks = taskRepository.getTasks().first()
        assertEquals(2, allTasks.size)
    }

    @Test
    fun testLabels() = runTest {
        val label = labelRepository.createLabel(name = "Bug", color = "#EF4444")
        val task = taskRepository.createTask(title = "Fix crash")

        labelRepository.addLabelToTask(task.id, label.id)

        val labels = labelRepository.getLabelsForTask(task.id).first()
        assertEquals(1, labels.size)
        assertEquals("Bug", labels.first().name)

        labelRepository.removeLabelFromTask(task.id, label.id)
        assertTrue(labelRepository.getLabelsForTask(task.id).first().isEmpty())
    }

    @Test
    fun testPersistenceAcrossDatabaseRestarts() = runTest {
        // Step 1: Create a task in the first database session
        val task = taskRepository.createTask(
            title = "Persisted Task",
            description = "Should survive across app restarts",
            priority = TaskPriority.URGENT,
        )

        // Step 2: Simulate app restart by creating a new DatabaseFactory pointing to the same file
        val reopenedDriverFactory = JvmDatabaseDriverFactory(tempDbFile.absolutePath)
        val reopenedDb = DatabaseFactory(reopenedDriverFactory).createDatabase()
        val reopenedRepo = SqlDelightTaskRepository(reopenedDb, kotlinx.coroutines.Dispatchers.Unconfined)

        val tasksAfterRestart = reopenedRepo.getTasks().first()
        assertEquals(1, tasksAfterRestart.size)
        val persisted = tasksAfterRestart.first()
        assertEquals(task.id, persisted.id)
        assertEquals("Persisted Task", persisted.title)
        assertEquals("Should survive across app restarts", persisted.description)
        assertEquals(TaskPriority.URGENT, persisted.priority)
    }
}
