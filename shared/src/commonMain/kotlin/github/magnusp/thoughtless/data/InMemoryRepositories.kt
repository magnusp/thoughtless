package github.magnusp.thoughtless.data

import github.magnusp.thoughtless.domain.model.Project
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.domain.repository.ProjectRepository
import github.magnusp.thoughtless.domain.repository.TaskRepository
import github.magnusp.thoughtless.util.currentTimeMillis
import github.magnusp.thoughtless.util.randomId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class InMemoryTaskRepository : TaskRepository {
    private val tasks = MutableStateFlow<Map<String, Task>>(emptyMap())

    override fun getTasks(): Flow<List<Task>> {
        return tasks.map { it.values.sortedByDescending { task -> task.createdAt } }
    }

    override fun getTasksByProject(projectId: String?): Flow<List<Task>> {
        return tasks.map { map ->
            map.values
                .filter { it.projectId == projectId }
                .sortedByDescending { it.createdAt }
        }
    }

    override fun getInboxTasks(): Flow<List<Task>> {
        return getTasksByProject(null)
    }

    override fun getTaskById(id: String): Flow<Task?> {
        return tasks.map { it[id] }
    }

    override suspend fun createTask(
        title: String,
        description: String?,
        projectId: String?,
        priority: TaskPriority,
        dueDate: Long?,
    ): Task {
        val now = currentTimeMillis()
        val task = Task(
            id = randomId(),
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
        tasks.value = tasks.value + (task.id to task)
        return task
    }

    override suspend fun updateTaskStatus(id: String, status: TaskStatus) {
        val current = tasks.value[id] ?: return
        val now = currentTimeMillis()
        val updated = current.copy(
            status = status,
            completedAt = if (status == TaskStatus.DONE) now else null,
            updatedAt = now,
        )
        tasks.value = tasks.value + (id to updated)
    }

    override suspend fun updateTask(task: Task) {
        val now = currentTimeMillis()
        val updated = task.copy(updatedAt = now)
        tasks.value = tasks.value + (task.id to updated)
    }

    override suspend fun updateAgentScratchpad(taskId: String, scratchpad: github.magnusp.thoughtless.domain.model.AgentScratchpad) {
        val current = tasks.value[taskId] ?: return
        val now = currentTimeMillis()
        val updated = current.copy(
            agentScratchpad = scratchpad,
            updatedAt = now,
        )
        tasks.value = tasks.value + (taskId to updated)
    }

    override suspend fun deleteTask(id: String) {
        tasks.value = tasks.value - id
    }

    override suspend fun deleteTasksByProject(projectId: String?) {
        tasks.value = tasks.value.filterValues { it.projectId != projectId }
    }
}

class InMemoryProjectRepository : ProjectRepository {
    private val projects = MutableStateFlow<Map<String, Project>>(emptyMap())

    override fun getProjects(): Flow<List<Project>> {
        return projects.map { it.values.sortedBy { project -> project.name } }
    }

    override fun getProjectById(id: String): Flow<Project?> {
        return projects.map { it[id] }
    }

    override suspend fun createProject(
        name: String,
        description: String?,
        color: String?,
    ): Project {
        val now = currentTimeMillis()
        val project = Project(
            id = randomId(),
            name = name,
            description = description,
            color = color,
            createdAt = now,
            updatedAt = now,
        )
        projects.value = projects.value + (project.id to project)
        return project
    }

    override suspend fun updateProject(project: Project) {
        val now = currentTimeMillis()
        val updated = project.copy(updatedAt = now)
        projects.value = projects.value + (project.id to updated)
    }

    override suspend fun deleteProject(id: String) {
        projects.value = projects.value - id
    }
}

class InMemoryTaskProposalRepository : github.magnusp.thoughtless.domain.repository.TaskProposalRepository {
    private val proposals = MutableStateFlow<Map<String, github.magnusp.thoughtless.domain.model.TaskProposal>>(emptyMap())

    override fun getProposals(): Flow<List<github.magnusp.thoughtless.domain.model.TaskProposal>> {
        return proposals.map { it.values.sortedByDescending { p -> p.createdAt } }
    }

    override fun getPendingProposals(): Flow<List<github.magnusp.thoughtless.domain.model.TaskProposal>> {
        return proposals.map { map ->
            map.values
                .filter { it.status == github.magnusp.thoughtless.domain.model.ProposalStatus.PROPOSED }
                .sortedByDescending { it.createdAt }
        }
    }

    override fun getProposalsByProject(projectId: String?): Flow<List<github.magnusp.thoughtless.domain.model.TaskProposal>> {
        return proposals.map { map ->
            map.values
                .filter { it.projectId == projectId }
                .sortedByDescending { it.createdAt }
        }
    }

    override fun getProposalById(id: String): Flow<github.magnusp.thoughtless.domain.model.TaskProposal?> {
        return proposals.map { it[id] }
    }

    override suspend fun saveProposal(proposal: github.magnusp.thoughtless.domain.model.TaskProposal): github.magnusp.thoughtless.domain.model.TaskProposal {
        proposals.value = proposals.value + (proposal.id to proposal)
        return proposal
    }

    override suspend fun updateProposalStatus(id: String, status: github.magnusp.thoughtless.domain.model.ProposalStatus) {
        val current = proposals.value[id] ?: return
        val now = currentTimeMillis()
        val updated = current.copy(
            status = status,
            reviewedAt = now,
        )
        proposals.value = proposals.value + (id to updated)
    }

    override suspend fun deleteProposal(id: String) {
        proposals.value = proposals.value - id
    }
}
