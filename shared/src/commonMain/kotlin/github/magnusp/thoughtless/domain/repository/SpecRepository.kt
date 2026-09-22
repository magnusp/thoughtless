package github.magnusp.thoughtless.domain.repository

import github.magnusp.thoughtless.domain.model.Spec
import kotlinx.coroutines.flow.Flow

interface SpecRepository {
    fun getSpecs(): Flow<List<Spec>>
    fun getSpecsByProject(projectId: String): Flow<List<Spec>>
    fun getSpecById(id: String): Flow<Spec?>
    suspend fun saveSpec(spec: Spec): Spec
    suspend fun deleteSpec(id: String)
}
