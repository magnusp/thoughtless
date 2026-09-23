package github.magnusp.thoughtless.data.arcadedb

import com.arcadedb.database.Database
import com.arcadedb.graph.MutableVertex
import com.arcadedb.graph.Vertex
import github.magnusp.thoughtless.domain.model.ProposalStatus
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskProposal
import github.magnusp.thoughtless.domain.model.TaskType
import github.magnusp.thoughtless.domain.repository.TaskProposalRepository
import github.magnusp.thoughtless.util.currentTimeMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow

class ArcadeDBTaskProposalRepository(
    private val engine: ArcadeDBEngine,
) : TaskProposalRepository {

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

    override fun getProposals(): Flow<List<TaskProposal>> = flow {
        emit(loadAllProposals())
        mutationFlow.asSharedFlow().collect {
            emit(loadAllProposals())
        }
    }

    override fun getPendingProposals(): Flow<List<TaskProposal>> = flow {
        emit(loadPendingProposals())
        mutationFlow.asSharedFlow().collect {
            emit(loadPendingProposals())
        }
    }

    override fun getProposalsByProject(projectId: String?): Flow<List<TaskProposal>> = flow {
        emit(loadProposalsByProject(projectId))
        mutationFlow.asSharedFlow().collect {
            emit(loadProposalsByProject(projectId))
        }
    }

    override fun getProposalById(id: String): Flow<TaskProposal?> = flow {
        emit(loadProposalById(id))
        mutationFlow.asSharedFlow().collect {
            emit(loadProposalById(id))
        }
    }

    private fun loadAllProposals(): List<TaskProposal> {
        ensureDatabase()
        return engine.transaction { db ->
            val proposals = mutableListOf<TaskProposal>()
            db.scanType("TaskProposal", true) { record ->
                val vertex = record.asVertex()
                proposals.add(vertexToProposal(vertex))
                true
            }
            proposals.sortedByDescending { it.createdAt }
        }
    }

    private fun loadPendingProposals(): List<TaskProposal> {
        ensureDatabase()
        return engine.transaction { db ->
            val proposals = mutableListOf<TaskProposal>()
            db.scanType("TaskProposal", true) { record ->
                val vertex = record.asVertex()
                if (vertex.getString("status") == ProposalStatus.PROPOSED.name) {
                    proposals.add(vertexToProposal(vertex))
                }
                true
            }
            proposals.sortedByDescending { it.createdAt }
        }
    }

    private fun loadProposalsByProject(projectId: String?): List<TaskProposal> {
        ensureDatabase()
        return engine.transaction { db ->
            val proposals = mutableListOf<TaskProposal>()
            db.scanType("TaskProposal", true) { record ->
                val vertex = record.asVertex()
                if (vertex.getString("projectId") == projectId) {
                    proposals.add(vertexToProposal(vertex))
                }
                true
            }
            proposals.sortedByDescending { it.createdAt }
        }
    }

    private fun loadProposalById(id: String): TaskProposal? {
        ensureDatabase()
        return engine.transaction { db ->
            val cursor = db.lookupByKey("TaskProposal", "id", id)
            if (cursor.hasNext()) {
                vertexToProposal(cursor.next().asVertex())
            } else {
                null
            }
        }
    }

    override suspend fun saveProposal(proposal: TaskProposal): TaskProposal {
        github.magnusp.thoughtless.domain.validation.WorkspaceValidator.validateWorkspace(proposal.suggestedWorkspace)
        github.magnusp.thoughtless.domain.validation.WorkspaceValidator.validateRelativePath(proposal.suggestedTargetFile, "suggestedTargetFile")
        proposal.suggestedContextFiles.forEach {
            github.magnusp.thoughtless.domain.validation.WorkspaceValidator.validateRelativePath(it, "suggestedContextFile")
        }
        ensureDatabase()
        engine.transaction { db ->
            val cursor = db.lookupByKey("TaskProposal", "id", proposal.id)
            val vertex = if (cursor.hasNext()) {
                cursor.next().asVertex().modify()
            } else {
                db.newVertex("TaskProposal")
            }
            setVertexProperties(vertex, proposal)
            vertex.save()

            // Connect PROPOSED_FROM edge if sourceTaskId is present
            if (proposal.sourceTaskId != null) {
                val sourceCursor = db.lookupByKey("Task", "id", proposal.sourceTaskId)
                if (sourceCursor.hasNext()) {
                    val sourceVertex = sourceCursor.next().asVertex()
                    val hasEdge = vertex.getEdges(Vertex.DIRECTION.OUT, "PROPOSED_FROM")
                        .any { it.inVertex?.getString("id") == proposal.sourceTaskId }
                    if (!hasEdge) {
                        vertex.newEdge("PROPOSED_FROM", sourceVertex, true)
                    }
                }
            }
        }
        notifyMutation()
        return proposal
    }

    override suspend fun updateProposalStatus(id: String, status: ProposalStatus) {
        val now = currentTimeMillis()
        ensureDatabase()
        engine.transaction { db ->
            val cursor = db.lookupByKey("TaskProposal", "id", id)
            if (cursor.hasNext()) {
                val vertex = cursor.next().asVertex().modify()
                vertex.set("status", status.name)
                vertex.set("reviewedAt", now)
                vertex.save()
            }
        }
        notifyMutation()
    }

    override suspend fun deleteProposal(id: String) {
        ensureDatabase()
        engine.transaction { db ->
            val cursor = db.lookupByKey("TaskProposal", "id", id)
            if (cursor.hasNext()) {
                val vertex = cursor.next().asVertex()
                vertex.delete()
            }
        }
        notifyMutation()
    }

    private fun setVertexProperties(vertex: MutableVertex, proposal: TaskProposal) {
        vertex.set("id", proposal.id)
        vertex.set("title", proposal.title)
        vertex.set("rationale", proposal.rationale)
        vertex.set("type", proposal.type.name)
        vertex.set("status", proposal.status.name)
        vertex.set("suggestedTargetFile", proposal.suggestedTargetFile)
        vertex.set("suggestedContextFiles", proposal.suggestedContextFiles)
        vertex.set("suggestedDependsOn", proposal.suggestedDependsOn)
        vertex.set("acceptanceCriteria", proposal.acceptanceCriteria)
        vertex.set("priority", proposal.priority.level)
        vertex.set("suggestedWorkspace", proposal.suggestedWorkspace)
        vertex.set("sourceTaskId", proposal.sourceTaskId)
        vertex.set("projectId", proposal.projectId)
        vertex.set("createdAt", proposal.createdAt)
        vertex.set("reviewedAt", proposal.reviewedAt)
    }

    private fun vertexToProposal(vertex: Vertex): TaskProposal {
        val statusString = vertex.getString("status")
        val status = if (statusString != null) {
            try {
                ProposalStatus.valueOf(statusString)
            } catch (_: Exception) {
                ProposalStatus.PROPOSED
            }
        } else ProposalStatus.PROPOSED

        val priorityLevel = vertex.getLong("priority") ?: 0L
        val priority = TaskPriority.fromLevel(priorityLevel)

        val typeString = vertex.getString("type")
        val type = if (typeString != null) {
            try {
                TaskType.valueOf(typeString)
            } catch (_: Exception) {
                TaskType.TASK
            }
        } else TaskType.TASK

        val suggestedContextFiles = vertex.getList<String>("suggestedContextFiles") ?: emptyList()
        val suggestedDependsOn = vertex.getList<String>("suggestedDependsOn") ?: emptyList()
        val acceptanceCriteria = vertex.getList<String>("acceptanceCriteria") ?: emptyList()

        return TaskProposal(
            id = vertex.getString("id"),
            title = vertex.getString("title") ?: "",
            rationale = vertex.getString("rationale") ?: "",
            type = type,
            status = status,
            suggestedTargetFile = vertex.getString("suggestedTargetFile"),
            suggestedContextFiles = suggestedContextFiles,
            suggestedDependsOn = suggestedDependsOn,
            acceptanceCriteria = acceptanceCriteria,
            priority = priority,
            suggestedWorkspace = vertex.getString("suggestedWorkspace"),
            sourceTaskId = vertex.getString("sourceTaskId"),
            projectId = vertex.getString("projectId"),
            createdAt = vertex.getLong("createdAt") ?: 0L,
            reviewedAt = vertex.getLong("reviewedAt"),
        )
    }
}
