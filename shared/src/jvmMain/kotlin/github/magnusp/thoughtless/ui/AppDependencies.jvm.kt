package github.magnusp.thoughtless.ui

import github.magnusp.thoughtless.data.arcadedb.ArcadeDBEngine
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBProjectRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBTaskRepository
import github.magnusp.thoughtless.domain.repository.ProjectRepository
import github.magnusp.thoughtless.domain.repository.TaskRepository

actual fun createDefaultTaskRepository(): TaskRepository {
    return ArcadeDBTaskRepository(ArcadeDBEngine.getDefault())
}

actual fun createDefaultProjectRepository(): ProjectRepository {
    return ArcadeDBProjectRepository(ArcadeDBEngine.getDefault())
}
