package github.magnusp.thoughtless.data.arcadedb

import github.magnusp.thoughtless.domain.model.ProposalStatus
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskProposal
import github.magnusp.thoughtless.domain.model.TaskType
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

class ArcadeDBTaskProposalRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var engine: ArcadeDBEngine
    private lateinit var proposalRepo: ArcadeDBTaskProposalRepository
    private lateinit var taskRepo: ArcadeDBTaskRepository

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("proposal-repo-test").toFile()
        engine = ArcadeDBEngine(tempDir.absolutePath)
        proposalRepo = ArcadeDBTaskProposalRepository(engine)
        taskRepo = ArcadeDBTaskRepository(engine)
    }

    @AfterTest
    fun tearDown() {
        engine.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun testProposalCrudAndStatusTransitions() = runBlocking {
        // Create source task
        val sourceTask = taskRepo.createTask(title = "Source Task")

        val proposal = TaskProposal(
            id = "prop-1",
            title = "Discovered Auth Token Leak",
            rationale = "Found potential leak when inspecting HTTP client logs",
            type = TaskType.SPIKE,
            status = ProposalStatus.PROPOSED,
            suggestedTargetFile = "AtProtoClient.kt",
            suggestedContextFiles = listOf("docs/security.md"),
            suggestedDependsOn = listOf(sourceTask.id),
            acceptanceCriteria = listOf("Verify auth headers are redacted"),
            priority = TaskPriority.HIGH,
            sourceTaskId = sourceTask.id,
            projectId = "proj-1",
            createdAt = 1700000000000L,
            reviewedAt = null,
        )

        // Save
        val saved = proposalRepo.saveProposal(proposal)
        assertEquals("prop-1", saved.id)

        // Get by ID
        val fetched = proposalRepo.getProposalById("prop-1").first()
        assertNotNull(fetched)
        assertEquals("Discovered Auth Token Leak", fetched.title)
        assertEquals(ProposalStatus.PROPOSED, fetched.status)
        assertEquals(TaskType.SPIKE, fetched.type)
        assertEquals("AtProtoClient.kt", fetched.suggestedTargetFile)
        assertEquals(listOf("docs/security.md"), fetched.suggestedContextFiles)
        assertEquals(listOf(sourceTask.id), fetched.suggestedDependsOn)
        assertEquals(listOf("Verify auth headers are redacted"), fetched.acceptanceCriteria)
        assertEquals(TaskPriority.HIGH, fetched.priority)
        assertEquals(sourceTask.id, fetched.sourceTaskId)
        assertEquals("proj-1", fetched.projectId)

        // Pending proposals query
        val pending = proposalRepo.getPendingProposals().first()
        assertEquals(1, pending.size)
        assertEquals("prop-1", pending.first().id)

        // Update status to ACCEPTED
        proposalRepo.updateProposalStatus("prop-1", ProposalStatus.ACCEPTED)
        val accepted = proposalRepo.getProposalById("prop-1").first()
        assertNotNull(accepted)
        assertEquals(ProposalStatus.ACCEPTED, accepted.status)
        assertNotNull(accepted.reviewedAt)

        val pendingAfterAccept = proposalRepo.getPendingProposals().first()
        assertEquals(0, pendingAfterAccept.size)

        // Delete proposal
        proposalRepo.deleteProposal("prop-1")
        val deleted = proposalRepo.getProposalById("prop-1").first()
        assertNull(deleted)
    }
}
