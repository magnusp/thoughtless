package github.magnusp.thoughtless.data.arcadedb

import com.arcadedb.database.Database
import com.arcadedb.graph.MutableVertex
import com.arcadedb.graph.Vertex
import github.magnusp.thoughtless.domain.model.Spec
import github.magnusp.thoughtless.domain.repository.SpecRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow

class ArcadeDBSpecRepository(
    private val engine: ArcadeDBEngine,
) : SpecRepository {

    private val mutationFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

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

    override fun getSpecs(): Flow<List<Spec>> = flow {
        emit(loadAllSpecs())
        mutationFlow.asSharedFlow().collect {
            emit(loadAllSpecs())
        }
    }

    override fun getSpecsByProject(projectId: String): Flow<List<Spec>> = flow {
        emit(loadSpecsByProject(projectId))
        mutationFlow.asSharedFlow().collect {
            emit(loadSpecsByProject(projectId))
        }
    }

    override fun getSpecById(id: String): Flow<Spec?> = flow {
        emit(loadSpecById(id))
        mutationFlow.asSharedFlow().collect {
            emit(loadSpecById(id))
        }
    }

    private fun loadAllSpecs(): List<Spec> {
        ensureDatabase()
        return engine.transaction { db ->
            val specs = mutableListOf<Spec>()
            db.scanType("Spec", true) { record ->
                val vertex = record.asVertex()
                specs.add(vertexToSpec(vertex))
                true
            }
            specs.sortedBy { it.title }
        }
    }

    private fun loadSpecsByProject(projectId: String): List<Spec> {
        ensureDatabase()
        return engine.transaction { db ->
            val specs = mutableListOf<Spec>()
            db.scanType("Spec", true) { record ->
                val vertex = record.asVertex()
                if (vertex.getString("projectId") == projectId) {
                    specs.add(vertexToSpec(vertex))
                }
                true
            }
            specs.sortedBy { it.title }
        }
    }

    private fun loadSpecById(id: String): Spec? {
        ensureDatabase()
        return engine.transaction { db ->
            val cursor = db.lookupByKey("Spec", "id", id)
            if (cursor.hasNext()) {
                vertexToSpec(cursor.next().asVertex())
            } else {
                null
            }
        }
    }

    override suspend fun saveSpec(spec: Spec): Spec {
        ensureDatabase()
        engine.transaction { db ->
            val cursor = db.lookupByKey("Spec", "id", spec.id)
            val vertex = if (cursor.hasNext()) {
                cursor.next().asVertex().modify()
            } else {
                db.newVertex("Spec")
            }
            setVertexProperties(vertex, spec)
            vertex.save()
        }
        notifyMutation()
        return spec
    }

    override suspend fun deleteSpec(id: String) {
        ensureDatabase()
        engine.transaction { db ->
            val cursor = db.lookupByKey("Spec", "id", id)
            if (cursor.hasNext()) {
                val vertex = cursor.next().asVertex()
                vertex.delete()
            }
        }
        notifyMutation()
    }

    private fun setVertexProperties(vertex: MutableVertex, spec: Spec) {
        vertex.set("id", spec.id)
        vertex.set("projectId", spec.projectId)
        vertex.set("title", spec.title)
        vertex.set("systemSpec", spec.systemSpec)
        vertex.set("nonGoals", spec.nonGoals)
        vertex.set("rfcDocument", spec.rfcDocument)
        vertex.set("frozenAt", spec.frozenAt)
    }

    private fun vertexToSpec(vertex: Vertex): Spec {
        return Spec(
            id = vertex.getString("id"),
            projectId = vertex.getString("projectId") ?: "",
            title = vertex.getString("title") ?: "",
            systemSpec = vertex.getString("systemSpec") ?: "",
            nonGoals = vertex.getString("nonGoals"),
            rfcDocument = vertex.getString("rfcDocument"),
            frozenAt = vertex.getLong("frozenAt"),
        )
    }
}
