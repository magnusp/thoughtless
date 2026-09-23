package github.magnusp.thoughtless.ui

import github.magnusp.thoughtless.data.arcadedb.ArcadeDBContextGraphRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBEngine
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBProjectRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBTaskProposalRepository
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceViewModelTest {

    private lateinit var tempDir: File
    private lateinit var engine: ArcadeDBEngine
    private lateinit var taskRepo: ArcadeDBTaskRepository
    private lateinit var projectRepo: ArcadeDBProjectRepository
    private lateinit var graphRepo: ArcadeDBContextGraphRepository
    private lateinit var proposalRepo: ArcadeDBTaskProposalRepository
    private lateinit var proposalService: github.magnusp.thoughtless.service.TaskProposalService
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
        proposalRepo = ArcadeDBTaskProposalRepository(engine)
        proposalService = github.magnusp.thoughtless.service.TaskProposalService(proposalRepo, taskRepo, graphRepo)
        markdownService = MarkdownIngestionService(graphRepo)
        dagService = DAGDecomposerService(taskRepo, graphRepo)
        val credDir = File(tempDir, ".thoughtless")
        val credStore = github.magnusp.thoughtless.identity.OperatorCredentialStore(
            configDir = credDir,
            envGetter = { null },
            processRunner = { Pair(-1, "disabled in tests") }
        )

        viewModel = WorkspaceViewModel(
            taskRepository = taskRepo,
            projectRepository = projectRepo,
            contextGraphRepository = graphRepo,
            markdownIngestionService = markdownService,
            dagDecomposerService = dagService,
            taskProposalRepository = proposalRepo,
            taskProposalService = proposalService,
            operatorCredentialStore = credStore,
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

    @Test
    fun testProposalsAndScratchpadIntegration() = runBlocking {
        // 1. Propose task
        val proposal = proposalService.proposeTask(
            title = "Discovered Memory Leak in Cache",
            rationale = "Cache lacks eviction policy",
            suggestedTargetFile = "Cache.kt",
            acceptanceCriteria = listOf("Evicts LRU entries"),
        )
        kotlinx.coroutines.delay(100)

        val pending = viewModel.pendingProposals.value
        assertEquals(1, pending.size)
        assertEquals(proposal.id, pending.first().id)

        // 2. Accept proposal via ViewModel
        viewModel.acceptProposal(proposal.id)
        kotlinx.coroutines.delay(150)

        assertEquals(0, viewModel.pendingProposals.value.size)
        val allTasks = viewModel.tasks.value
        assertEquals(1, allTasks.size)
        val createdTask = allTasks.first()
        assertEquals("Discovered Memory Leak in Cache", createdTask.title)

        // 3. Update task progress / scratchpad
        val scratchpad = github.magnusp.thoughtless.domain.model.AgentScratchpad(
            currentStep = "Implementing LRU map wrapper",
            notes = "Using LinkedHashMap with accessOrder = true",
            touchedFiles = listOf("Cache.kt"),
            completedCriteria = listOf("Evicts LRU entries"),
            lastUpdated = 1700000010000L,
        )
        viewModel.updateTaskProgress(createdTask.id, scratchpad)
        kotlinx.coroutines.delay(100)

        val updatedTask = taskRepo.getTaskById(createdTask.id).first()
        assertNotNull(updatedTask)
        val savedScratchpad = updatedTask.agentScratchpad
        assertNotNull(savedScratchpad)
        assertEquals("Implementing LRU map wrapper", savedScratchpad.currentStep)
        assertEquals(listOf("Cache.kt"), savedScratchpad.touchedFiles)
    }

    @Test
    fun testOperatorIdentityAndWorkspaceAssistance() = runBlocking(Dispatchers.Default) {
        val initialIdentity = viewModel.operatorIdentity.value
        assertNotNull(initialIdentity)

        // Save local operator token
        viewModel.saveLocalOperatorToken("ghp_secret_operator_token_987", "operator_magnus")
        val updatedIdentity = viewModel.operatorIdentity.value
        assertEquals("operator_magnus", updatedIdentity.username)
        assertEquals("ghp_secret_operator_token_987", updatedIdentity.token)
        assertTrue(updatedIdentity.isAuthenticated)

        // Create project with workspace
        viewModel.createProject("Workspace Alpha", null, "github.com/org/alpha-service")
        kotlinx.coroutines.delay(100)

        // Check readiness
        viewModel.checkWorkspacesReadiness()
        val statuses = viewModel.workspaceStatuses.value
        assertTrue(statuses.containsKey("github.com/org/alpha-service"))
        val alphaStatus = statuses["github.com/org/alpha-service"]
        assertNotNull(alphaStatus)

        // Clear credentials
        viewModel.clearLocalOperatorToken()
        val clearedIdentity = viewModel.operatorIdentity.value
        assertEquals(github.magnusp.thoughtless.identity.OperatorAuthType.NONE, clearedIdentity.authType)
    }

    @Test
    fun testWikilinkNavigation() = runBlocking(Dispatchers.Default) {
        // Create two documents
        viewModel.createNewDocument("Doc Alpha")
        kotlinx.coroutines.delay(100)
        val alphaDocId = viewModel.selectedDocumentId.value
        assertNotNull(alphaDocId)

        viewModel.createNewDocument("Doc Beta")
        kotlinx.coroutines.delay(100)
        val betaDocId = viewModel.selectedDocumentId.value
        assertNotNull(betaDocId)
        assertEquals(betaDocId, viewModel.selectedDocumentId.value)

        // Navigate to Alpha via wikilink
        viewModel.navigateToWikilink(alphaDocId)
        assertEquals(alphaDocId, viewModel.selectedDocumentId.value)
        assertTrue(viewModel.navigationFeedback.value?.contains("Navigated to $alphaDocId") == true)

        // Navigate to a non-existent document
        viewModel.navigateToWikilink("doc-does-not-exist")
        assertTrue(viewModel.navigationFeedback.value?.contains("not found") == true)
        // Should not have switched away from Alpha
        assertEquals(alphaDocId, viewModel.selectedDocumentId.value)
    }

    @Test
    fun testDeleteDocument() = runBlocking(Dispatchers.Default) {
        // Create two documents
        viewModel.createNewDocument("Document One")
        kotlinx.coroutines.delay(100)
        val doc1Id = viewModel.selectedDocumentId.value
        assertNotNull(doc1Id)

        viewModel.createNewDocument("Document Two")
        kotlinx.coroutines.delay(100)
        val doc2Id = viewModel.selectedDocumentId.value
        assertNotNull(doc2Id)
        assertEquals(doc2Id, viewModel.selectedDocumentId.value)

        // Drop Document Two
        viewModel.deleteDocument(doc2Id)
        kotlinx.coroutines.delay(100)

        // Feedback should report dropped
        assertTrue(viewModel.navigationFeedback.value?.contains("dropped") == true)

        // Selection should fallback to the remaining document (doc1)
        assertEquals(doc1Id, viewModel.selectedDocumentId.value)

        // Dropping the last document
        viewModel.deleteDocument(doc1Id)
        kotlinx.coroutines.delay(100)

        // Selection and editor content should be cleared
        assertNull(viewModel.selectedDocumentId.value)
        assertEquals("", viewModel.editorContent.value)

        // Verify nodes were completely removed from repository
        assertNull(graphRepo.getNodeById(doc1Id).first())
        assertNull(graphRepo.getNodeById(doc2Id).first())
    }
}
