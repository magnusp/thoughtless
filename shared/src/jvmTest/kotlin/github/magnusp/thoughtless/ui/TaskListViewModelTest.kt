package github.magnusp.thoughtless.ui

import github.magnusp.thoughtless.data.InMemoryProjectRepository
import github.magnusp.thoughtless.data.InMemoryTaskRepository
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.domain.repository.ProjectRepository
import github.magnusp.thoughtless.domain.repository.TaskRepository
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
    private lateinit var taskRepository: TaskRepository
    private lateinit var projectRepository: ProjectRepository
    private lateinit var viewModel: TaskListViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        taskRepository = InMemoryTaskRepository()
        projectRepository = InMemoryProjectRepository()
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
            description = "Working compose UI",
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
}
