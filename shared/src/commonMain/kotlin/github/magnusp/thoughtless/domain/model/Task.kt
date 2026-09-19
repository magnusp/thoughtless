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
)
