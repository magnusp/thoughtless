package github.magnusp.thoughtless.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import github.magnusp.thoughtless.db.ThoughtlessDatabase
import github.magnusp.thoughtless.domain.model.Label
import github.magnusp.thoughtless.domain.repository.LabelRepository
import github.magnusp.thoughtless.util.randomId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SqlDelightLabelRepository(
    private val database: ThoughtlessDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LabelRepository {

    private val queries = database.labelQueries

    override fun getLabels(): Flow<List<Label>> {
        return queries.selectAllLabels()
            .asFlow()
            .mapToList(dispatcher)
            .map { list -> list.map { it.toDomain() } }
    }

    override fun getLabelsForTask(taskId: String): Flow<List<Label>> {
        return queries.selectLabelsForTask(taskId)
            .asFlow()
            .mapToList(dispatcher)
            .map { list -> list.map { it.toDomain() } }
    }

    override suspend fun createLabel(name: String, color: String?): Label = withContext(dispatcher) {
        val id = randomId()
        val label = Label(id = id, name = name, color = color)
        queries.insertLabel(id = label.id, name = label.name, color = label.color)
        label
    }

    override suspend fun deleteLabel(id: String): Unit = withContext(dispatcher) {
        queries.deleteLabel(id)
    }

    override suspend fun addLabelToTask(taskId: String, labelId: String): Unit = withContext(dispatcher) {
        queries.insertTaskLabel(task_id = taskId, label_id = labelId)
    }

    override suspend fun removeLabelFromTask(taskId: String, labelId: String): Unit = withContext(dispatcher) {
        queries.deleteTaskLabel(task_id = taskId, label_id = labelId)
    }

    override suspend fun clearTaskLabels(taskId: String): Unit = withContext(dispatcher) {
        queries.clearTaskLabels(taskId)
    }
}
