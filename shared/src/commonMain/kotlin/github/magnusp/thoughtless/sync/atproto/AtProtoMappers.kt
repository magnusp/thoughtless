package github.magnusp.thoughtless.sync.atproto

import github.magnusp.thoughtless.domain.model.Project
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.util.formatIsoTimestamp
import github.magnusp.thoughtless.util.parseIsoTimestamp

fun Task.toAtProtoRecord(): TaskRecord = TaskRecord(
    type = "thoughtless.task",
    title = title,
    description = description,
    status = status.value,
    priority = priority.level,
    dueDate = dueDate,
    projectId = projectId,
    createdAt = formatIsoTimestamp(createdAt),
    updatedAt = formatIsoTimestamp(updatedAt),
    completedAt = completedAt?.let { formatIsoTimestamp(it) },
)

fun TaskRecord.toDomain(id: String): Task = Task(
    id = id,
    projectId = projectId,
    title = title,
    description = description,
    status = TaskStatus.fromValue(status),
    priority = TaskPriority.fromLevel(priority),
    dueDate = dueDate,
    createdAt = parseIsoTimestamp(createdAt),
    updatedAt = updatedAt?.let { parseIsoTimestamp(it) } ?: parseIsoTimestamp(createdAt),
    completedAt = completedAt?.let { parseIsoTimestamp(it) },
)

fun Project.toAtProtoRecord(): ProjectRecord = ProjectRecord(
    type = "thoughtless.project",
    name = name,
    description = description,
    color = color,
    createdAt = formatIsoTimestamp(createdAt),
    updatedAt = formatIsoTimestamp(updatedAt),
)

fun ProjectRecord.toDomain(id: String): Project = Project(
    id = id,
    name = name,
    description = description,
    color = color,
    createdAt = parseIsoTimestamp(createdAt),
    updatedAt = updatedAt?.let { parseIsoTimestamp(it) } ?: parseIsoTimestamp(createdAt),
)
