package github.magnusp.thoughtless.data.arcadedb

import com.arcadedb.database.Database
import com.arcadedb.graph.MutableVertex
import com.arcadedb.graph.Vertex
import github.magnusp.thoughtless.domain.model.Project
import github.magnusp.thoughtless.domain.repository.ProjectRepository
import github.magnusp.thoughtless.util.currentTimeMillis
import github.magnusp.thoughtless.util.randomId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow

class ArcadeDBProjectRepository(
    private val engine: ArcadeDBEngine,
) : ProjectRepository {

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

    override fun getProjects(): Flow<List<Project>> = flow {
        emit(loadAllProjects())
        mutationFlow.asSharedFlow().collect {
            emit(loadAllProjects())
        }
    }

    override fun getProjectById(id: String): Flow<Project?> = flow {
        emit(loadProjectById(id))
        mutationFlow.asSharedFlow().collect {
            emit(loadProjectById(id))
        }
    }

    private fun loadAllProjects(): List<Project> {
        ensureDatabase()
        return engine.transaction { db ->
            val projects = mutableListOf<Project>()
            db.scanType("Project", true) { record ->
                val vertex = record.asVertex()
                projects.add(vertexToProject(vertex))
                true
            }
            projects.sortedBy { it.name }
        }
    }

    private fun loadProjectById(id: String): Project? {
        ensureDatabase()
        return engine.transaction { db ->
            val cursor = db.lookupByKey("Project", "id", id)
            if (cursor.hasNext()) {
                vertexToProject(cursor.next().asVertex())
            } else {
                null
            }
        }
    }

    override suspend fun createProject(
        name: String,
        description: String?,
        color: String?,
        workspace: String?,
    ): Project {
        github.magnusp.thoughtless.domain.validation.WorkspaceValidator.validateWorkspace(workspace)
        val now = currentTimeMillis()
        val project = Project(
            id = randomId(),
            name = name,
            description = description,
            color = color,
            workspace = workspace,
            createdAt = now,
            updatedAt = now,
        )

        ensureDatabase()
        engine.transaction { db ->
            val vertex = db.newVertex("Project")
            setVertexProperties(vertex, project)
            vertex.save()
        }

        notifyMutation()
        return project
    }

    override suspend fun updateProject(project: Project) {
        github.magnusp.thoughtless.domain.validation.WorkspaceValidator.validateWorkspace(project.workspace)
        val now = currentTimeMillis()
        val updated = project.copy(updatedAt = now)
        ensureDatabase()
        engine.transaction { db ->
            val cursor = db.lookupByKey("Project", "id", project.id)
            if (cursor.hasNext()) {
                val vertex = cursor.next().asVertex().modify()
                setVertexProperties(vertex, updated)
                vertex.save()
            }
        }
        notifyMutation()
    }

    override suspend fun deleteProject(id: String) {
        ensureDatabase()
        engine.transaction { db ->
            val cursor = db.lookupByKey("Project", "id", id)
            if (cursor.hasNext()) {
                val projectVertex = cursor.next().asVertex()
                // Cascade delete associated tasks or detach
                val tasksToDelete = mutableListOf<Vertex>()
                for (edge in projectVertex.getEdges(Vertex.DIRECTION.OUT, "HAS_TASK")) {
                    tasksToDelete.add(edge.inVertex)
                }
                tasksToDelete.forEach { it.delete() }

                // Also check tasks that have projectId property matching this project id
                val otherTasks = mutableListOf<Vertex>()
                db.scanType("Task", true) { record ->
                    val taskVertex = record.asVertex()
                    if (taskVertex.getString("projectId") == id) {
                        otherTasks.add(taskVertex)
                    }
                    true
                }
                otherTasks.forEach { it.delete() }

                projectVertex.delete()
            }
        }
        notifyMutation()
    }

    private fun setVertexProperties(vertex: MutableVertex, project: Project) {
        vertex.set("id", project.id)
        vertex.set("name", project.name)
        vertex.set("description", project.description)
        vertex.set("color", project.color)
        vertex.set("workspace", project.workspace)
        vertex.set("createdAt", project.createdAt)
        vertex.set("updatedAt", project.updatedAt)
    }

    private fun vertexToProject(vertex: Vertex): Project {
        return Project(
            id = vertex.getString("id"),
            name = vertex.getString("name") ?: "",
            description = vertex.getString("description"),
            color = vertex.getString("color"),
            workspace = vertex.getString("workspace"),
            createdAt = vertex.getLong("createdAt") ?: 0L,
            updatedAt = vertex.getLong("updatedAt") ?: 0L,
        )
    }
}
