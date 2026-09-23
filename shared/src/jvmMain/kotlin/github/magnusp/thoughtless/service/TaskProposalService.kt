package github.magnusp.thoughtless.service

import github.magnusp.thoughtless.domain.model.ContextEdge
import github.magnusp.thoughtless.domain.model.ProposalStatus
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskProposal
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.domain.model.TaskType
import github.magnusp.thoughtless.domain.repository.ContextGraphRepository
import github.magnusp.thoughtless.domain.repository.TaskProposalRepository
import github.magnusp.thoughtless.domain.repository.TaskRepository
import github.magnusp.thoughtless.util.currentTimeMillis
import github.magnusp.thoughtless.util.randomId
import kotlinx.coroutines.flow.first

class TaskProposalService(
    private val proposalRepository: TaskProposalRepository,
    private val taskRepository: TaskRepository,
    private val contextGraphRepository: ContextGraphRepository? = null,
) {

    /**
     * Proposes a new task/spike/rfc finding discovered during agent execution or planning.
     * Does NOT mutate active execution tiers.
     */
    suspend fun proposeTask(
        title: String,
        rationale: String,
        type: TaskType = TaskType.TASK,
        suggestedTargetFile: String? = null,
        suggestedContextFiles: List<String> = emptyList(),
        suggestedDependsOn: List<String> = emptyList(),
        acceptanceCriteria: List<String> = emptyList(),
        priority: TaskPriority = TaskPriority.MEDIUM,
        suggestedWorkspace: String? = null,
        sourceTaskId: String? = null,
        projectId: String? = null,
    ): TaskProposal {
        github.magnusp.thoughtless.domain.validation.WorkspaceValidator.validateWorkspace(suggestedWorkspace)
        github.magnusp.thoughtless.domain.validation.WorkspaceValidator.validateRelativePath(suggestedTargetFile, "suggestedTargetFile")
        suggestedContextFiles.forEach {
            github.magnusp.thoughtless.domain.validation.WorkspaceValidator.validateRelativePath(it, "suggestedContextFile")
        }
        val now = currentTimeMillis()
        val proposal = TaskProposal(
            id = "prop-${randomId()}",
            title = title.trim(),
            rationale = rationale.trim(),
            type = type,
            status = ProposalStatus.PROPOSED,
            suggestedTargetFile = suggestedTargetFile?.trim(),
            suggestedContextFiles = suggestedContextFiles.map { it.trim() }.filter { it.isNotBlank() },
            suggestedDependsOn = suggestedDependsOn.map { it.trim() }.filter { it.isNotBlank() },
            acceptanceCriteria = acceptanceCriteria.map { it.trim() }.filter { it.isNotBlank() },
            priority = priority,
            suggestedWorkspace = suggestedWorkspace?.trim(),
            sourceTaskId = sourceTaskId,
            projectId = projectId,
            createdAt = now,
            reviewedAt = null,
        )
        return proposalRepository.saveProposal(proposal)
    }

    /**
     * Accepts a proposal, promoting it to a first-class Task in the active execution DAG.
     * Optionally takes a [refine] transformation to edit title, criteria, dependencies, or priority before promotion.
     */
    suspend fun acceptProposal(
        proposalId: String,
        refine: ((TaskProposal) -> TaskProposal)? = null,
    ): Task {
        val originalProposal = proposalRepository.getProposalById(proposalId).first()
            ?: error("Proposal with id '$proposalId' not found")

        val proposal = if (refine != null) refine(originalProposal) else originalProposal
        val now = currentTimeMillis()

        // 1. Create first-class Task
        val taskId = "task-${randomId()}"
        val task = Task(
            id = taskId,
            projectId = proposal.projectId,
            title = proposal.title,
            description = proposal.rationale,
            status = TaskStatus.TODO,
            priority = proposal.priority,
            createdAt = now,
            updatedAt = now,
            type = proposal.type,
            workspace = proposal.suggestedWorkspace,
            contextFiles = proposal.suggestedContextFiles,
            targetFile = proposal.suggestedTargetFile,
            acceptanceCriteria = proposal.acceptanceCriteria,
            dependsOn = proposal.suggestedDependsOn,
        )
        taskRepository.updateTask(task)

        // 2. Persist DEPENDS_ON edges into context graph if available
        if (contextGraphRepository != null) {
            for (depId in task.dependsOn) {
                val edgeId = "${task.id}_DEPENDS_ON_$depId"
                contextGraphRepository.saveEdge(
                    ContextEdge(
                        id = edgeId,
                        fromId = task.id,
                        toId = depId,
                        relation = "DEPENDS_ON",
                        createdAt = now,
                    )
                )
            }
        }

        // 3. Mark proposal as ACCEPTED
        proposalRepository.updateProposalStatus(proposalId, ProposalStatus.ACCEPTED)

        return task
    }

    /**
     * Rejects/dismisses a proposal.
     */
    suspend fun rejectProposal(proposalId: String) {
        proposalRepository.updateProposalStatus(proposalId, ProposalStatus.REJECTED)
    }
}
