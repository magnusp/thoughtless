package github.magnusp.thoughtless.data

import github.magnusp.thoughtless.domain.model.Label
import github.magnusp.thoughtless.domain.model.Project
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.db.Task as DbTask
import github.magnusp.thoughtless.db.Project as DbProject
import github.magnusp.thoughtless.db.Label as DbLabel

fun DbTask.toDomain(): Task = Task(
    id = id,
    projectId = project_id,
    title = title,
    description = description,
    status = TaskStatus.fromValue(status),
    priority = TaskPriority.fromLevel(priority),
    dueDate = due_date,
    createdAt = created_at,
    updatedAt = updated_at,
    completedAt = completed_at,
)

fun DbProject.toDomain(): Project = Project(
    id = id,
    name = name,
    description = description,
    color = color,
    createdAt = created_at,
    updatedAt = updated_at,
)

fun DbLabel.toDomain(): Label = Label(
    id = id,
    name = name,
    color = color,
)
