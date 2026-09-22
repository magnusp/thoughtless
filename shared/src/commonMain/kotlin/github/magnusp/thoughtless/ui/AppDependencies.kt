package github.magnusp.thoughtless.ui

import github.magnusp.thoughtless.domain.repository.ProjectRepository
import github.magnusp.thoughtless.domain.repository.TaskRepository

expect fun createDefaultTaskRepository(): TaskRepository
expect fun createDefaultProjectRepository(): ProjectRepository

class AppDependencies(
    val taskRepository: TaskRepository = createDefaultTaskRepository(),
    val projectRepository: ProjectRepository = createDefaultProjectRepository(),
) {
    val viewModel = TaskListViewModel(taskRepository, projectRepository)
}
