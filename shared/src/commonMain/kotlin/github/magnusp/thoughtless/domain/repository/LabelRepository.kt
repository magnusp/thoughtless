package github.magnusp.thoughtless.domain.repository

import github.magnusp.thoughtless.domain.model.Label
import kotlinx.coroutines.flow.Flow

interface LabelRepository {
    fun getLabels(): Flow<List<Label>>
    fun getLabelsForTask(taskId: String): Flow<List<Label>>
    suspend fun createLabel(name: String, color: String? = null): Label
    suspend fun deleteLabel(id: String)
    suspend fun addLabelToTask(taskId: String, labelId: String)
    suspend fun removeLabelFromTask(taskId: String, labelId: String)
    suspend fun clearTaskLabels(taskId: String)
}
