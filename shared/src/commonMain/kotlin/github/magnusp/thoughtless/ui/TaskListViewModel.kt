package github.magnusp.thoughtless.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import github.magnusp.thoughtless.domain.model.Project
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.domain.repository.ProjectRepository
import github.magnusp.thoughtless.domain.repository.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class TaskListViewModel(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    private val _selectedProjectId = MutableStateFlow<String?>(null)
    val selectedProjectId: StateFlow<String?> = _selectedProjectId.asStateFlow()

    val projects: StateFlow<List<Project>> = projectRepository.getProjects()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    val tasks: StateFlow<List<Task>> = _selectedProjectId
        .flatMapLatest { projectId ->
            if (projectId == null) {
                taskRepository.getTasks()
            } else {
                taskRepository.getTasksByProject(projectId)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    fun selectProject(projectId: String?) {
        _selectedProjectId.value = projectId
    }

    fun createTask(
        title: String,
        description: String? = null,
        projectId: String? = _selectedProjectId.value,
        priority: TaskPriority = TaskPriority.NONE,
        workspace: String? = null,
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val project = projectId?.let { pid -> projects.value.firstOrNull { it.id == pid } }
            val resolvedWorkspace = workspace ?: project?.defaultWorkspace
            taskRepository.createTask(
                title = title.trim(),
                description = description?.trim()?.ifBlank { null },
                projectId = projectId,
                priority = priority,
                workspace = resolvedWorkspace,
            )
        }
    }

    fun toggleTaskCompletion(task: Task) {
        viewModelScope.launch {
            val newStatus = if (task.status == TaskStatus.DONE) TaskStatus.TODO else TaskStatus.DONE
            taskRepository.updateTaskStatus(task.id, newStatus)
        }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            taskRepository.deleteTask(taskId)
        }
    }

    fun createProject(
        name: String,
        description: String? = null,
        color: String? = null,
        defaultWorkspace: String? = null,
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            projectRepository.createProject(
                name = name.trim(),
                description = description?.trim()?.ifBlank { null },
                color = color,
                defaultWorkspace = defaultWorkspace?.trim()?.ifBlank { null },
            )
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            projectRepository.deleteProject(projectId)
            if (_selectedProjectId.value == projectId) {
                _selectedProjectId.value = null
            }
        }
    }
}
