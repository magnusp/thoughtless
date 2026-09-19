package github.magnusp.thoughtless.ui

import github.magnusp.thoughtless.data.DatabaseDriverFactory
import github.magnusp.thoughtless.data.DatabaseFactory
import github.magnusp.thoughtless.data.SqlDelightProjectRepository
import github.magnusp.thoughtless.data.SqlDelightTaskRepository
import github.magnusp.thoughtless.data.getDefaultDatabaseDriverFactory

class AppDependencies(
    driverFactory: DatabaseDriverFactory = getDefaultDatabaseDriverFactory()
) {
    val database = DatabaseFactory(driverFactory).createDatabase()
    val taskRepository = SqlDelightTaskRepository(database)
    val projectRepository = SqlDelightProjectRepository(database)
    val viewModel = TaskListViewModel(taskRepository, projectRepository)
}
