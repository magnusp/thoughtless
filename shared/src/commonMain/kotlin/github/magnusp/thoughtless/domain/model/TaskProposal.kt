package github.magnusp.thoughtless.domain.model

enum class ProposalStatus {
    PROPOSED,
    ACCEPTED,
    REJECTED,
}

data class TaskProposal(
    val id: String,
    val title: String,
    val rationale: String,
    val type: TaskType = TaskType.TASK,
    val status: ProposalStatus = ProposalStatus.PROPOSED,
    val suggestedTargetFile: String? = null,
    val suggestedContextFiles: List<String> = emptyList(),
    val suggestedDependsOn: List<String> = emptyList(),
    val acceptanceCriteria: List<String> = emptyList(),
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val sourceTaskId: String? = null,
    val projectId: String? = null,
    val createdAt: Long,
    val reviewedAt: Long? = null,
)
