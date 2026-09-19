package github.magnusp.thoughtless.domain.repository

import github.magnusp.thoughtless.domain.model.Project
import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    fun getProjects(): Flow<List<Project>>
    fun getProjectById(id: String): Flow<Project?>
    suspend fun createProject(
        name: String,
        description: String? = null,
        color: String? = null,
    ): Project
    suspend fun updateProject(project: Project)
    suspend fun deleteProject(id: String)
}
