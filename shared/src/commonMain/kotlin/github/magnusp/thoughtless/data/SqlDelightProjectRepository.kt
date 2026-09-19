package github.magnusp.thoughtless.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import github.magnusp.thoughtless.db.ThoughtlessDatabase
import github.magnusp.thoughtless.domain.model.Project
import github.magnusp.thoughtless.domain.repository.ProjectRepository
import github.magnusp.thoughtless.util.currentTimeMillis
import github.magnusp.thoughtless.util.randomId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SqlDelightProjectRepository(
    private val database: ThoughtlessDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProjectRepository {

    private val queries = database.projectQueries

    override fun getProjects(): Flow<List<Project>> {
        return queries.selectAllProjects()
            .asFlow()
            .mapToList(dispatcher)
            .map { list -> list.map { it.toDomain() } }
    }

    override fun getProjectById(id: String): Flow<Project?> {
        return queries.selectProjectById(id)
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map { it?.toDomain() }
    }

    override suspend fun createProject(
        name: String,
        description: String?,
        color: String?,
    ): Project = withContext(dispatcher) {
        val now = currentTimeMillis()
        val id = randomId()
        val project = Project(
            id = id,
            name = name,
            description = description,
            color = color,
            createdAt = now,
            updatedAt = now,
        )
        queries.insertProject(
            id = project.id,
            name = project.name,
            description = project.description,
            color = project.color,
            created_at = project.createdAt,
            updated_at = project.updatedAt,
        )
        project
    }

    override suspend fun updateProject(project: Project): Unit = withContext(dispatcher) {
        val now = currentTimeMillis()
        queries.updateProject(
            name = project.name,
            description = project.description,
            color = project.color,
            updated_at = now,
            id = project.id,
        )
    }

    override suspend fun deleteProject(id: String): Unit = withContext(dispatcher) {
        queries.deleteProject(id)
    }
}
