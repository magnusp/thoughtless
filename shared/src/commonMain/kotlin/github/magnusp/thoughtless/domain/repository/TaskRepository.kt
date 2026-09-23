package github.magnusp.thoughtless.domain.repository

import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    fun getTasks(): Flow<List<Task>>
    fun getTasksByProject(projectId: String?): Flow<List<Task>>
    fun getInboxTasks(): Flow<List<Task>>
    fun getTaskById(id: String): Flow<Task?>
    suspend fun createTask(
        title: String,
        description: String? = null,
        projectId: String? = null,
        priority: TaskPriority = TaskPriority.NONE,
        dueDate: Long? = null,
    ): Task
    suspend fun updateTaskStatus(id: String, status: TaskStatus)
    suspend fun updateTask(task: Task)
    suspend fun updateAgentScratchpad(taskId: String, scratchpad: github.magnusp.thoughtless.domain.model.AgentScratchpad)
    suspend fun deleteTask(id: String)
    suspend fun deleteTasksByProject(projectId: String?)
}
