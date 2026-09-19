package github.magnusp.thoughtless.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import github.magnusp.thoughtless.db.ThoughtlessDatabase
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.domain.repository.TaskRepository
import github.magnusp.thoughtless.util.currentTimeMillis
import github.magnusp.thoughtless.util.randomId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SqlDelightTaskRepository(
    private val database: ThoughtlessDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TaskRepository {

    private val queries = database.taskQueries

    override fun getTasks(): Flow<List<Task>> {
        return queries.selectAllTasks()
            .asFlow()
            .mapToList(dispatcher)
            .map { list -> list.map { it.toDomain() } }
    }

    override fun getTasksByProject(projectId: String?): Flow<List<Task>> {
        return if (projectId == null) {
            getInboxTasks()
        } else {
            queries.selectTasksByProject(projectId)
                .asFlow()
                .mapToList(dispatcher)
                .map { list -> list.map { it.toDomain() } }
        }
    }

    override fun getInboxTasks(): Flow<List<Task>> {
        return queries.selectInboxTasks()
            .asFlow()
            .mapToList(dispatcher)
            .map { list -> list.map { it.toDomain() } }
    }

    override fun getTaskById(id: String): Flow<Task?> {
        return queries.selectTaskById(id)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map { it?.toDomain() }
    }

    override suspend fun createTask(
        title: String,
        description: String?,
        projectId: String?,
        priority: TaskPriority,
        dueDate: Long?,
    ): Task = withContext(dispatcher) {
        val now = currentTimeMillis()
        val id = randomId()
        val task = Task(
            id = id,
            projectId = projectId,
            title = title,
            description = description,
            status = TaskStatus.TODO,
            priority = priority,
            dueDate = dueDate,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
        )
        queries.insertTask(
            id = task.id,
            project_id = task.projectId,
            title = task.title,
            description = task.description,
            status = task.status.value,
            priority = task.priority.level,
            due_date = task.dueDate,
            created_at = task.createdAt,
            updated_at = task.updatedAt,
            completed_at = task.completedAt,
        )
        task
    }

    override suspend fun updateTaskStatus(id: String, status: TaskStatus): Unit = withContext(dispatcher) {
        val now = currentTimeMillis()
        val completedAt = if (status == TaskStatus.DONE) now else null
        queries.updateTaskStatus(
            status = status.value,
            completed_at = completedAt,
            updated_at = now,
            id = id,
        )
    }

    override suspend fun updateTask(task: Task): Unit = withContext(dispatcher) {
        val now = currentTimeMillis()
        queries.updateTask(
            project_id = task.projectId,
            title = task.title,
            description = task.description,
            status = task.status.value,
            priority = task.priority.level,
            due_date = task.dueDate,
            updated_at = now,
            completed_at = if (task.status == TaskStatus.DONE) (task.completedAt ?: now) else null,
            id = task.id,
        )
    }

    override suspend fun deleteTask(id: String): Unit = withContext(dispatcher) {
        queries.deleteTask(id)
    }

    override suspend fun deleteTasksByProject(projectId: String?): Unit = withContext(dispatcher) {
        queries.deleteTasksByProject(projectId)
    }
}
