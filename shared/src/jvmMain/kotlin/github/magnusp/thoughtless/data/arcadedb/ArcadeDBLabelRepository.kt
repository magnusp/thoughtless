package github.magnusp.thoughtless.data.arcadedb

import com.arcadedb.database.Database
import com.arcadedb.graph.MutableVertex
import com.arcadedb.graph.Vertex
import github.magnusp.thoughtless.domain.model.Label
import github.magnusp.thoughtless.domain.repository.LabelRepository
import github.magnusp.thoughtless.util.randomId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow

class ArcadeDBLabelRepository(
    private val engine: ArcadeDBEngine,
) : LabelRepository {

    private val mutationFlow = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 64)

    private fun ensureDatabase(): Database {
        return if (engine.isOpen()) {
            engine.database
        } else {
            engine.open()
        }
    }

    private fun notifyMutation() {
        mutationFlow.tryEmit(Unit)
    }

    override fun getLabels(): Flow<List<Label>> = flow {
        emit(loadAllLabels())
        mutationFlow.asSharedFlow().collect {
            emit(loadAllLabels())
        }
    }

    override fun getLabelsForTask(taskId: String): Flow<List<Label>> = flow {
        emit(loadLabelsForTask(taskId))
        mutationFlow.asSharedFlow().collect {
            emit(loadLabelsForTask(taskId))
        }
    }

    private fun loadAllLabels(): List<Label> {
        ensureDatabase()
        return engine.transaction { db ->
            val labels = mutableListOf<Label>()
            db.scanType("Label", true) { record ->
                val vertex = record.asVertex()
                labels.add(vertexToLabel(vertex))
                true
            }
            labels.sortedBy { it.name }
        }
    }

    private fun loadLabelsForTask(taskId: String): List<Label> {
        ensureDatabase()
        return engine.transaction { db ->
            val cursor = db.lookupByKey("Task", "id", taskId)
            if (!cursor.hasNext()) return@transaction emptyList()
            val taskVertex = cursor.next().asVertex()
            val labels = mutableListOf<Label>()
            for (edge in taskVertex.getEdges(Vertex.DIRECTION.OUT, "HAS_LABEL")) {
                labels.add(vertexToLabel(edge.inVertex))
            }
            labels.sortedBy { it.name }
        }
    }

    override suspend fun createLabel(name: String, color: String?): Label {
        val label = Label(
            id = randomId(),
            name = name,
            color = color,
        )
        ensureDatabase()
        engine.transaction { db ->
            val vertex = db.newVertex("Label")
            setVertexProperties(vertex, label)
            vertex.save()
        }
        notifyMutation()
        return label
    }

    override suspend fun deleteLabel(id: String) {
        ensureDatabase()
        engine.transaction { db ->
            val cursor = db.lookupByKey("Label", "id", id)
            if (cursor.hasNext()) {
                val vertex = cursor.next().asVertex()
                vertex.delete()
            }
        }
        notifyMutation()
    }

    override suspend fun addLabelToTask(taskId: String, labelId: String) {
        ensureDatabase()
        engine.transaction { db ->
            val taskCursor = db.lookupByKey("Task", "id", taskId)
            val labelCursor = db.lookupByKey("Label", "id", labelId)
            if (taskCursor.hasNext() && labelCursor.hasNext()) {
                val taskVertex = taskCursor.next().asVertex()
                val labelVertex = labelCursor.next().asVertex()

                val alreadyConnected = taskVertex.getEdges(Vertex.DIRECTION.OUT, "HAS_LABEL").any { edge ->
                    edge.inVertex.getString("id") == labelId
                }
                if (!alreadyConnected) {
                    val modifiableTask = taskVertex.modify()
                    modifiableTask.newEdge("HAS_LABEL", labelVertex, true)
                }
            }
        }
        notifyMutation()
    }

    override suspend fun removeLabelFromTask(taskId: String, labelId: String) {
        ensureDatabase()
        engine.transaction { db ->
            val taskCursor = db.lookupByKey("Task", "id", taskId)
            if (taskCursor.hasNext()) {
                val taskVertex = taskCursor.next().asVertex()
                for (edge in taskVertex.getEdges(Vertex.DIRECTION.OUT, "HAS_LABEL")) {
                    if (edge.inVertex.getString("id") == labelId) {
                        edge.delete()
                    }
                }
            }
        }
        notifyMutation()
    }

    override suspend fun clearTaskLabels(taskId: String) {
        ensureDatabase()
        engine.transaction { db ->
            val taskCursor = db.lookupByKey("Task", "id", taskId)
            if (taskCursor.hasNext()) {
                val taskVertex = taskCursor.next().asVertex()
                for (edge in taskVertex.getEdges(Vertex.DIRECTION.OUT, "HAS_LABEL")) {
                    edge.delete()
                }
            }
        }
        notifyMutation()
    }

    private fun setVertexProperties(vertex: MutableVertex, label: Label) {
        vertex.set("id", label.id)
        vertex.set("name", label.name)
        vertex.set("color", label.color)
    }

    private fun vertexToLabel(vertex: Vertex): Label {
        return Label(
            id = vertex.getString("id"),
            name = vertex.getString("name") ?: "",
            color = vertex.getString("color"),
        )
    }
}
