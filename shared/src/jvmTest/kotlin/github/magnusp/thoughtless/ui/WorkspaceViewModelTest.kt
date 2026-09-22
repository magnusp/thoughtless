package github.magnusp.thoughtless.ui

import github.magnusp.thoughtless.data.arcadedb.ArcadeDBContextGraphRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBEngine
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBProjectRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBTaskRepository
import github.magnusp.thoughtless.domain.model.AgentTaskStatus
import github.magnusp.thoughtless.domain.model.NodeType
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.service.DAGDecomposerService
import github.magnusp.thoughtless.service.MarkdownIngestionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceViewModelTest {

    private lateinit var tempDir: File
    private lateinit var engine: ArcadeDBEngine
    private lateinit var taskRepo: ArcadeDBTaskRepository
    private lateinit var projectRepo: ArcadeDBProjectRepository
    private lateinit var graphRepo: ArcadeDBContextGraphRepository
    private lateinit var markdownService: MarkdownIngestionService
    private lateinit var dagService: DAGDecomposerService
    private lateinit var viewModel: WorkspaceViewModel

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("workspace-vm-test").toFile()
        engine = ArcadeDBEngine(tempDir.absolutePath)
        taskRepo = ArcadeDBTaskRepository(engine)
        projectRepo = ArcadeDBProjectRepository(engine)
        graphRepo = ArcadeDBContextGraphRepository(engine)
        markdownService = MarkdownIngestionService(graphRepo)
        dagService = DAGDecomposerService(taskRepo, graphRepo)

        viewModel = WorkspaceViewModel(
            taskRepository = taskRepo,
            projectRepository = projectRepo,
            contextGraphRepository = graphRepo,
            markdownIngestionService = markdownService,
            dagDecomposerService = dagService,
        )
    }

    @AfterTest
    fun tearDown() {
        engine.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun testNavigationTabSelection() {
        assertEquals(WorkspaceNavTab.DOCUMENTS, viewModel.activeTab.value)
        viewModel.selectTab(WorkspaceNavTab.TASKS)
        assertEquals(WorkspaceNavTab.TASKS, viewModel.activeTab.value)
        viewModel.selectTab(WorkspaceNavTab.AGENT_QUEUE)
        assertEquals(WorkspaceNavTab.AGENT_QUEUE, viewModel.activeTab.value)
    }

    @Test
    fun testCreateAndSelectDocument() = runBlocking {
        viewModel.createNewDocument("Auth Architecture", projectId = "proj-sec")
        kotlinx.coroutines.delay(100)

        val selectedDocId = viewModel.selectedDocumentId.value
        assertNotNull(selectedDocId)
        assertTrue(selectedDocId.contains("auth-architecture"))

        val editorContent = viewModel.editorContent.value
        assertTrue(editorContent.contains("Auth Architecture"))

        // Update content and save
        val updatedContent = """
            ---
            id: $selectedDocId
            type: SPEC
            title: Auth Architecture
            ---
            # Auth Architecture
            System details referencing [[doc-storage]].
        """.trimIndent()
        viewModel.updateEditorContent(updatedContent)
        viewModel.saveCurrentDocument()
        kotlinx.coroutines.delay(100)

        // Verify document node updated in ArcadeDB
        val node = graphRepo.getNodeById(selectedDocId).first()
        assertNotNull(node)
        assertEquals(NodeType.SPEC, node.type)
    }

    @Test
    fun testAgentQueueTopologicalTiersAndStatusTransitions() = runBlocking {
        // Create 3 tasks: t1 -> t2 -> t3
        val t1 = taskRepo.createTask(title = "Base Task")
        val t2 = taskRepo.createTask(title = "Dependent Task")
        val t3 = taskRepo.createTask(title = "Final Task")

        taskRepo.updateTask(t2.copy(dependsOn = listOf(t1.id)))
        taskRepo.updateTask(t3.copy(dependsOn = listOf(t2.id)))
        kotlinx.coroutines.delay(100)

        val tiers = viewModel.executionTiers.value
        assertEquals(3, tiers.size)
        assertEquals(t1.id, tiers[0][0].id)
        assertEquals(t2.id, tiers[1][0].id)
        assertEquals(t3.id, tiers[2][0].id)

        // Advance agent status to MERGED
        viewModel.updateAgentTaskStatus(t1, AgentTaskStatus.MERGED)
        kotlinx.coroutines.delay(100)

        val updatedT1 = taskRepo.getTaskById(t1.id).first()
        assertNotNull(updatedT1)
        assertEquals(AgentTaskStatus.MERGED, updatedT1.agentStatus)
        assertEquals(TaskStatus.DONE, updatedT1.status)

        // Export DAG JSON
        val exportedJson = viewModel.exportAgentDagJson()
        assertNotNull(exportedJson)
        assertTrue(exportedJson.contains(t1.id))
        assertTrue(exportedJson.contains(t2.id))
    }
}
