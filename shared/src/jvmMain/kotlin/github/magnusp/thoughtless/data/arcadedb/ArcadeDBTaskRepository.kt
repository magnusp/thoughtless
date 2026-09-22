package github.magnusp.thoughtless.data.arcadedb

import com.arcadedb.database.Database
import com.arcadedb.graph.MutableVertex
import com.arcadedb.graph.Vertex
import github.magnusp.thoughtless.domain.model.AgentPermissions
import github.magnusp.thoughtless.domain.model.AgentTaskStatus
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.domain.model.TaskType
import github.magnusp.thoughtless.domain.repository.TaskRepository
import github.magnusp.thoughtless.util.currentTimeMillis
import github.magnusp.thoughtless.util.randomId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow

class ArcadeDBTaskRepository(
    private val engine: ArcadeDBEngine,
) : TaskRepository {

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

    override fun getTasks(): Flow<List<Task>> = flow {
        emit(loadAllTasks())
        mutationFlow.asSharedFlow().collect {
            emit(loadAllTasks())
        }
    }

    override fun getTasksByProject(projectId: String?): Flow<List<Task>> = flow {
        emit(loadTasksByProject(projectId))
        mutationFlow.asSharedFlow().collect {
            emit(loadTasksByProject(projectId))
        }
    }

    override fun getInboxTasks(): Flow<List<Task>> {
        return getTasksByProject(null)
    }

    override fun getTaskById(id: String): Flow<Task?> = flow {
        emit(loadTaskById(id))
        mutationFlow.asSharedFlow().collect {
            emit(loadTaskById(id))
        }
    }

    private fun loadAllTasks(): List<Task> {
        ensureDatabase()
        return engine.transaction { db ->
            val tasks = mutableListOf<Task>()
            db.scanType("Task", true) { record ->
                val vertex = record.asVertex()
                tasks.add(vertexToTask(vertex))
                true
            }
            tasks.sortedByDescending { it.createdAt }
        }
    }

    private fun loadTasksByProject(projectId: String?): List<Task> {
        ensureDatabase()
        return engine.transaction { db ->
            if (projectId != null) {
                val cursor = db.lookupByKey("Project", "id", projectId)
                if (cursor.hasNext()) {
                    val projectVertex = cursor.next().asVertex()
                    val tasks = mutableListOf<Task>()
                    for (edge in projectVertex.getEdges(Vertex.DIRECTION.OUT, "HAS_TASK")) {
                        tasks.add(vertexToTask(edge.inVertex))
                    }
                    return@transaction tasks.sortedByDescending { it.createdAt }
                }
            }
            val tasks = mutableListOf<Task>()
            db.scanType("Task", true) { record ->
                val vertex = record.asVertex()
                if (vertex.getString("projectId") == projectId) {
                    tasks.add(vertexToTask(vertex))
                }
                true
            }
            tasks.sortedByDescending { it.createdAt }
        }
    }

    private fun loadTaskById(id: String): Task? {
        ensureDatabase()
        return engine.transaction { db ->
            val cursor = db.lookupByKey("Task", "id", id)
            if (cursor.hasNext()) {
                vertexToTask(cursor.next().asVertex())
            } else {
                null
            }
        }
    }

    override suspend fun createTask(
        title: String,
        description: String?,
        projectId: String?,
        priority: TaskPriority,
        dueDate: Long?,
    ): Task {
        val now = currentTimeMillis()
        val task = Task(
            id = randomId(),
            projectId = projectId,
            title = title,
            description = description,
            status = TaskStatus.TODO,
            priority = priority,
            dueDate = dueDate,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
        )

        ensureDatabase()
        engine.transaction { db ->
            val vertex = db.newVertex("Task")
            setVertexProperties(vertex, task)
            vertex.save()

            if (projectId != null) {
                val projectCursor = db.lookupByKey("Project", "id", projectId)
                if (projectCursor.hasNext()) {
                    val projectVertex = projectCursor.next().asVertex()
                    val modifiableProject = projectVertex.modify()
                    modifiableProject.newEdge("HAS_TASK", vertex, true)
                }
            }
        }

        notifyMutation()
        return task
    }

    override suspend fun updateTaskStatus(id: String, status: TaskStatus) {
        val now = currentTimeMillis()
        ensureDatabase()
        engine.transaction { db ->
            val cursor = db.lookupByKey("Task", "id", id)
            if (cursor.hasNext()) {
                val vertex = cursor.next().asVertex().modify()
                vertex.set("status", status.name)
                vertex.set("updatedAt", now)
                if (status == TaskStatus.DONE) {
                    vertex.set("completedAt", now)
                } else {
                    vertex.set("completedAt", null)
                }
                vertex.save()
            }
        }
        notifyMutation()
    }

    override suspend fun updateTask(task: Task) {
        val now = currentTimeMillis()
        val updated = task.copy(updatedAt = now)
        ensureDatabase()
        engine.transaction { db ->
            val cursor = db.lookupByKey("Task", "id", task.id)
            val vertex = if (cursor.hasNext()) {
                cursor.next().asVertex().modify()
            } else {
                db.newVertex("Task")
            }
            val oldProjectId = if (vertex.has("projectId")) vertex.getString("projectId") else null
            setVertexProperties(vertex, updated)
            vertex.save()

            if (oldProjectId != updated.projectId) {
                // Remove old HAS_TASK edge if present
                for (edge in vertex.getEdges(Vertex.DIRECTION.IN, "HAS_TASK")) {
                    edge.delete()
                }
                // Add new HAS_TASK edge if new projectId is set
                if (updated.projectId != null) {
                    val projectCursor = db.lookupByKey("Project", "id", updated.projectId)
                    if (projectCursor.hasNext()) {
                        val projectVertex = projectCursor.next().asVertex().modify()
                        projectVertex.newEdge("HAS_TASK", vertex, true)
                    }
                }
            }
        }
        notifyMutation()
    }

    override suspend fun deleteTask(id: String) {
        ensureDatabase()
        engine.transaction { db ->
            val cursor = db.lookupByKey("Task", "id", id)
            if (cursor.hasNext()) {
                val vertex = cursor.next().asVertex()
                vertex.delete()
            }
        }
        notifyMutation()
    }

    override suspend fun deleteTasksByProject(projectId: String?) {
        ensureDatabase()
        engine.transaction { db ->
            val toDelete = mutableListOf<Vertex>()
            db.scanType("Task", true) { record ->
                val vertex = record.asVertex()
                if (vertex.getString("projectId") == projectId) {
                    toDelete.add(vertex)
                }
                true
            }
            toDelete.forEach { it.delete() }
        }
        notifyMutation()
    }

    private fun setVertexProperties(vertex: MutableVertex, task: Task) {
        vertex.set("id", task.id)
        vertex.set("projectId", task.projectId)
        vertex.set("title", task.title)
        vertex.set("description", task.description)
        vertex.set("status", task.status.name)
        vertex.set("priority", task.priority.level)
        vertex.set("dueDate", task.dueDate)
        vertex.set("createdAt", task.createdAt)
        vertex.set("updatedAt", task.updatedAt)
        vertex.set("completedAt", task.completedAt)
        vertex.set("type", task.type.name)
        vertex.set("contextFiles", task.contextFiles)
        vertex.set("targetFile", task.targetFile)
        vertex.set("acceptanceCriteria", task.acceptanceCriteria)
        vertex.set("dependsOn", task.dependsOn)
        vertex.set("milestoneId", task.milestoneId)
        vertex.set("canInstallPackages", task.agentPermissions?.canInstallPackages)
        vertex.set("allowedCommands", task.agentPermissions?.allowedCommands)
        vertex.set("agentStatus", task.agentStatus.name)
    }

    private fun vertexToTask(vertex: Vertex): Task {
        val statusString = vertex.getString("status")
        val status = if (statusString != null) TaskStatus.fromValue(statusString) else TaskStatus.TODO
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

        val agentStatusString = vertex.getString("agentStatus")
        val agentStatus = if (agentStatusString != null) {
            try {
                AgentTaskStatus.valueOf(agentStatusString)
            } catch (_: Exception) {
                AgentTaskStatus.PENDING
            }
        } else AgentTaskStatus.PENDING

        val canInstallPackages = vertex.getBoolean("canInstallPackages")
        val allowedCommands = vertex.getList<String>("allowedCommands")
        val agentPermissions = if (canInstallPackages != null || allowedCommands != null) {
            AgentPermissions(
                canInstallPackages = canInstallPackages ?: false,
                allowedCommands = allowedCommands ?: emptyList(),
            )
        } else null

        val contextFiles = vertex.getList<String>("contextFiles") ?: emptyList()
        val acceptanceCriteria = vertex.getList<String>("acceptanceCriteria") ?: emptyList()
        val dependsOn = vertex.getList<String>("dependsOn") ?: emptyList()

        return Task(
            id = vertex.getString("id"),
            projectId = vertex.getString("projectId"),
            title = vertex.getString("title") ?: "",
            description = vertex.getString("description"),
            status = status,
            priority = priority,
            dueDate = vertex.getLong("dueDate"),
            createdAt = vertex.getLong("createdAt") ?: 0L,
            updatedAt = vertex.getLong("updatedAt") ?: 0L,
            completedAt = vertex.getLong("completedAt"),
            type = type,
            contextFiles = contextFiles,
            targetFile = vertex.getString("targetFile"),
            acceptanceCriteria = acceptanceCriteria,
            dependsOn = dependsOn,
            milestoneId = vertex.getString("milestoneId"),
            agentPermissions = agentPermissions,
            agentStatus = agentStatus,
        )
    }
}
