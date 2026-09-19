package github.magnusp.thoughtless.ui

import github.magnusp.thoughtless.data.DatabaseFactory
import github.magnusp.thoughtless.data.JvmDatabaseDriverFactory
import github.magnusp.thoughtless.data.SqlDelightProjectRepository
import github.magnusp.thoughtless.data.SqlDelightTaskRepository
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TaskListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var taskRepository: SqlDelightTaskRepository
    private lateinit var projectRepository: SqlDelightProjectRepository
    private lateinit var viewModel: TaskListViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val driverFactory = JvmDatabaseDriverFactory.inMemory()
        val db = DatabaseFactory(driverFactory).createDatabase()
        taskRepository = SqlDelightTaskRepository(db, testDispatcher)
        projectRepository = SqlDelightProjectRepository(db, testDispatcher)
        viewModel = TaskListViewModel(taskRepository, projectRepository)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialStateIsEmpty() = runTest(testDispatcher) {
        advanceUntilIdle()
        assertTrue(viewModel.tasks.value.isEmpty())
        assertTrue(viewModel.projects.value.isEmpty())
    }

    @Test
    fun testCreateTaskAndToggle() = runTest(testDispatcher) {
        viewModel.createTask(
            title = "Ship spike 1",
            description = "Working compose UI + sqldelight",
            priority = TaskPriority.HIGH,
        )
        advanceUntilIdle()

        val tasks = taskRepository.getTasks().first()
        assertEquals(1, tasks.size)
        val task = tasks.first()
        assertEquals("Ship spike 1", task.title)
        assertEquals(TaskStatus.TODO, task.status)

        viewModel.toggleTaskCompletion(task)
        advanceUntilIdle()

        val updatedTask = taskRepository.getTaskById(task.id).first()
        assertEquals(TaskStatus.DONE, updatedTask?.status)
    }

    @Test
    fun testProjectFiltering() = runTest(testDispatcher) {
        viewModel.createProject(name = "Thoughtless Core")
        advanceUntilIdle()

        val projects = projectRepository.getProjects().first()
        assertEquals(1, projects.size)
        val project = projects.first()

        viewModel.createTask(title = "Task in project", projectId = project.id)
        viewModel.createTask(title = "Task in inbox", projectId = null)
        advanceUntilIdle()

        // Filter by project
        viewModel.selectProject(project.id)
        advanceUntilIdle()

        val projectTasks = taskRepository.getTasksByProject(project.id).first()
        assertEquals(1, projectTasks.size)
        assertEquals("Task in project", projectTasks.first().title)

        // Delete task
        viewModel.deleteTask(projectTasks.first().id)
        advanceUntilIdle()

        assertTrue(taskRepository.getTasksByProject(project.id).first().isEmpty())
    }

    @Test
    fun testViewModelStatePersistsAcrossRestarts() = runTest(testDispatcher) {
        val tempFile = java.io.File.createTempFile("thoughtless_vm_test", ".db")
        try {
            // First run
            val driverFactory1 = JvmDatabaseDriverFactory(tempFile.absolutePath)
            val db1 = DatabaseFactory(driverFactory1).createDatabase()
            val taskRepo1 = SqlDelightTaskRepository(db1, testDispatcher)
            val projectRepo1 = SqlDelightProjectRepository(db1, testDispatcher)
            val vm1 = TaskListViewModel(taskRepo1, projectRepo1)

            vm1.createTask(
                title = "Must persist across restart",
                description = "Persisted through ViewModel",
                priority = TaskPriority.URGENT,
            )
            advanceUntilIdle()

            // Simulate restart with fresh instances
            val driverFactory2 = JvmDatabaseDriverFactory(tempFile.absolutePath)
            val db2 = DatabaseFactory(driverFactory2).createDatabase()
            val taskRepo2 = SqlDelightTaskRepository(db2, testDispatcher)
            val projectRepo2 = SqlDelightProjectRepository(db2, testDispatcher)
            val vm2 = TaskListViewModel(taskRepo2, projectRepo2)

            advanceUntilIdle()

            val persistedTasks = taskRepo2.getTasks().first()
            assertEquals(1, persistedTasks.size)
            assertEquals("Must persist across restart", persistedTasks.first().title)
            assertEquals("Persisted through ViewModel", persistedTasks.first().description)
            assertEquals(TaskPriority.URGENT, persistedTasks.first().priority)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }
}
