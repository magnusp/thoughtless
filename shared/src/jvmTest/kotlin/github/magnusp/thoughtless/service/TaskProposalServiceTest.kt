package github.magnusp.thoughtless.service

import github.magnusp.thoughtless.data.InMemoryTaskProposalRepository
import github.magnusp.thoughtless.data.InMemoryTaskRepository
import github.magnusp.thoughtless.domain.model.ProposalStatus
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.domain.model.TaskType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskProposalServiceTest {

    private lateinit var taskRepo: InMemoryTaskRepository
    private lateinit var proposalRepo: InMemoryTaskProposalRepository
    private lateinit var proposalService: TaskProposalService

    @BeforeTest
    fun setup() {
        taskRepo = InMemoryTaskRepository()
        proposalRepo = InMemoryTaskProposalRepository()
        proposalService = TaskProposalService(
            proposalRepository = proposalRepo,
            taskRepository = taskRepo,
        )
    }

    @Test
    fun testProposeAndAcceptWorkflow() = runBlocking {
        // 1. Propose task
        val proposal = proposalService.proposeTask(
            title = "Handle token expiry in client",
            rationale = "Requests fail with 401 without automatic refresh",
            type = TaskType.SPIKE,
            suggestedTargetFile = "AtProtoClient.kt",
            suggestedContextFiles = listOf("docs/atproto.md"),
            suggestedDependsOn = listOf("task-base"),
            acceptanceCriteria = listOf("Refreshes JWT token on 401"),
            priority = TaskPriority.HIGH,
            sourceTaskId = "task-base",
            projectId = "proj-1",
        )

        assertNotNull(proposal.id)
        assertEquals(ProposalStatus.PROPOSED, proposal.status)

        val pending = proposalRepo.getPendingProposals().first()
        assertEquals(1, pending.size)

        // Ensure active tasks not mutated
        val activeTasks = taskRepo.getTasks().first()
        assertEquals(0, activeTasks.size)

        // 2. Accept proposal into active tasks
        val createdTask = proposalService.acceptProposal(proposal.id)
        assertNotNull(createdTask.id)
        assertEquals("Handle token expiry in client", createdTask.title)
        assertEquals("Requests fail with 401 without automatic refresh", createdTask.description)
        assertEquals(TaskStatus.TODO, createdTask.status)
        assertEquals(TaskPriority.HIGH, createdTask.priority)
        assertEquals(TaskType.SPIKE, createdTask.type)
        assertEquals("AtProtoClient.kt", createdTask.targetFile)
        assertEquals(listOf("docs/atproto.md"), createdTask.contextFiles)
        assertEquals(listOf("task-base"), createdTask.dependsOn)
        assertEquals(listOf("Refreshes JWT token on 401"), createdTask.acceptanceCriteria)
        assertEquals("proj-1", createdTask.projectId)

        // Proposal marked as ACCEPTED
        val updatedProposal = proposalRepo.getProposalById(proposal.id).first()
        assertEquals(ProposalStatus.ACCEPTED, updatedProposal?.status)
        assertEquals(0, proposalRepo.getPendingProposals().first().size)

        // Active tasks now has 1 task
        val tasksAfterAccept = taskRepo.getTasks().first()
        assertEquals(1, tasksAfterAccept.size)
        assertEquals(createdTask.id, tasksAfterAccept.first().id)
    }

    @Test
    fun testRejectProposalWorkflow() = runBlocking {
        val proposal = proposalService.proposeTask(
            title = "Unneeded Refactor",
            rationale = "Exploratory finding not aligned with goals",
        )

        proposalService.rejectProposal(proposal.id)

        val updated = proposalRepo.getProposalById(proposal.id).first()
        assertEquals(ProposalStatus.REJECTED, updated?.status)
        assertEquals(0, proposalRepo.getPendingProposals().first().size)
        assertEquals(0, taskRepo.getTasks().first().size)
    }
}
