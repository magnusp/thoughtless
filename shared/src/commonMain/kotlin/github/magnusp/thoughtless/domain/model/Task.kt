package github.magnusp.thoughtless.domain.model

enum class TaskStatus(val value: String) {
    TODO("TODO"),
    IN_PROGRESS("IN_PROGRESS"),
    DONE("DONE"),
    CANCELLED("CANCELLED");

    companion object {
        fun fromValue(value: String): TaskStatus =
            entries.firstOrNull { it.value == value } ?: TODO
    }
}

enum class TaskPriority(val level: Long, val displayName: String) {
    NONE(0, "None"),
    LOW(1, "Low"),
    MEDIUM(2, "Medium"),
    HIGH(3, "High"),
    URGENT(4, "Urgent");

    companion object {
        fun fromLevel(level: Long): TaskPriority =
            entries.firstOrNull { it.level == level } ?: NONE
    }
}

data class Task(
    val id: String,
    val projectId: String? = null,
    val title: String,
    val description: String? = null,
    val status: TaskStatus = TaskStatus.TODO,
    val priority: TaskPriority = TaskPriority.NONE,
    val dueDate: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
    val type: TaskType = TaskType.TASK,
    val contextFiles: List<String> = emptyList(),
    val targetFile: String? = null,
    val acceptanceCriteria: List<String> = emptyList(),
    val dependsOn: List<String> = emptyList(),
    val milestoneId: String? = null,
    val workspace: String? = null,
    val agentPermissions: AgentPermissions? = null,
    val agentStatus: AgentTaskStatus = AgentTaskStatus.PENDING,
    val agentScratchpad: AgentScratchpad? = null,
)

enum class TaskType {
    TASK,
    SPIKE,
    EXPLORATION,
    RFC,
}

enum class AgentTaskStatus {
    PENDING,
    AGENT_RUNNING,
    AWAITING_REVIEW,
    MERGED,
}

data class AgentPermissions(
    val canInstallPackages: Boolean = false,
    val allowedCommands: List<String> = emptyList(),
)

data class AgentScratchpad(
    val currentStep: String? = null,
    val notes: String? = null,
    val touchedFiles: List<String> = emptyList(),
    val completedCriteria: List<String> = emptyList(),
    val lastUpdated: Long = 0L,
)

