package github.magnusp.thoughtless.ui

import github.magnusp.thoughtless.data.InMemoryProjectRepository
import github.magnusp.thoughtless.data.InMemoryTaskRepository
import github.magnusp.thoughtless.domain.repository.ProjectRepository
import github.magnusp.thoughtless.domain.repository.TaskRepository

class AppDependencies(
    val taskRepository: TaskRepository = InMemoryTaskRepository(),
    val projectRepository: ProjectRepository = InMemoryProjectRepository(),
) {
    val viewModel = TaskListViewModel(taskRepository, projectRepository)
}
