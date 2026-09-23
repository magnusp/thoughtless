package github.magnusp.thoughtless.data.arcadedb

import github.magnusp.thoughtless.domain.model.Project
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

class ArcadeDBProjectRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var engine: ArcadeDBEngine
    private lateinit var projectRepo: ArcadeDBProjectRepository
    private lateinit var taskRepo: ArcadeDBTaskRepository

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("project-repo-test").toFile()
        engine = ArcadeDBEngine(tempDir.absolutePath)
        projectRepo = ArcadeDBProjectRepository(engine)
        taskRepo = ArcadeDBTaskRepository(engine)
    }

    @AfterTest
    fun tearDown() {
        engine.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun testProjectCrud() = runBlocking {
        // Create project
        val project = projectRepo.createProject(
            name = "Project Alpha",
            description = "Main alpha milestone project",
            color = "#10B981",
            workspace = "github.com/org/alpha-service",
        )

        assertNotNull(project.id)
        assertEquals("Project Alpha", project.name)
        assertEquals("Main alpha milestone project", project.description)
        assertEquals("#10B981", project.color)
        assertEquals("github.com/org/alpha-service", project.workspace)

        // Read by ID
        val fetched = projectRepo.getProjectById(project.id).first()
        assertNotNull(fetched)
        assertEquals("Project Alpha", fetched.name)

        // Update project
        val updatedModel = fetched.copy(name = "Project Alpha v2", color = "#EC4899")
        projectRepo.updateProject(updatedModel)

        val updated = projectRepo.getProjectById(project.id).first()
        assertNotNull(updated)
        assertEquals("Project Alpha v2", updated.name)
        assertEquals("#EC4899", updated.color)

        // Delete project
        projectRepo.deleteProject(project.id)
        val deleted = projectRepo.getProjectById(project.id).first()
        assertNull(deleted)
    }

    @Test
    fun testProjectCascadeDeleteTasks() = runBlocking {
        val proj = projectRepo.createProject("Project to Delete")
        val task1 = taskRepo.createTask(title = "Child Task 1", projectId = proj.id)
        val task2 = taskRepo.createTask(title = "Child Task 2", projectId = proj.id)
        val orphanTask = taskRepo.createTask(title = "Inbox Task")

        assertEquals(2, taskRepo.getTasksByProject(proj.id).first().size)
        assertEquals(3, taskRepo.getTasks().first().size)

        // Delete project -> should cascade delete tasks belonging to project
        projectRepo.deleteProject(proj.id)

        assertNull(projectRepo.getProjectById(proj.id).first())
        val remainingTasks = taskRepo.getTasks().first()
        assertEquals(1, remainingTasks.size)
        assertEquals(orphanTask.id, remainingTasks.first().id)
    }

    @Test
    fun testReactiveFlowUpdates() = runBlocking {
        val collectedLists = mutableListOf<List<Project>>()
        val flowStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            projectRepo.getProjects().take(3).collect {
                collectedLists.add(it)
                if (collectedLists.size == 1) {
                    flowStarted.complete(Unit)
                }
            }
        }

        flowStarted.await()
        kotlinx.coroutines.delay(50)

        val p1 = projectRepo.createProject("Project 1")
        kotlinx.coroutines.delay(50)
        val p2 = projectRepo.createProject("Project 2")

        job.join()

        assertEquals(3, collectedLists.size)
        assertEquals(0, collectedLists[0].size)
        assertEquals(1, collectedLists[1].size)
        assertEquals(2, collectedLists[2].size)
    }
}
