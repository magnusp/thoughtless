package github.magnusp.thoughtless.service

import github.magnusp.thoughtless.domain.model.AgentPermissions
import github.magnusp.thoughtless.domain.model.AgentTaskStatus
import github.magnusp.thoughtless.domain.model.ContextEdge
import github.magnusp.thoughtless.domain.model.Spec
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.domain.model.TaskType
import github.magnusp.thoughtless.domain.repository.ContextGraphRepository
import github.magnusp.thoughtless.domain.repository.TaskRepository
import github.magnusp.thoughtless.util.currentTimeMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CycleDetectedException(message: String) : RuntimeException(message)

@Serializable
data class AgentExecutableTask(
    val id: String,
    val title: String,
    val description: String?,
    val type: String,
    val workspace: String? = null,
    val targetFile: String?,
    val contextFiles: List<String>,
    val acceptanceCriteria: List<String>,
    val dependsOn: List<String>,
    val canInstallPackages: Boolean = false,
    val allowedCommands: List<String> = emptyList(),
)

@Serializable
data class AgentTaskDAGExport(
    val specId: String,
    val projectId: String?,
    val generatedAt: Long,
    val totalTasks: Int,
    val topologicalOrder: List<String>,
    val executionTiers: List<List<String>>,
    val tasks: List<AgentExecutableTask>,
)

class DAGDecomposerService(
    private val taskRepository: TaskRepository? = null,
    private val contextGraphRepository: ContextGraphRepository? = null,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * Performs Kahn's algorithm for topological sorting and cycle detection.
     * Enforces the Disjoint Target Scheduling Invariant: tasks sharing the same non-null
     * (workspace, targetFile) are automatically sequenced so they never land in the same
     * parallel execution tier.
     *
     * Returns a pair of (topologicalOrder, executionTiers).
     * Throws [CycleDetectedException] if circular dependency is detected.
     */
    fun topologicalSort(tasks: List<Task>): Pair<List<String>, List<List<String>>> {
        val taskIds = tasks.map { it.id }.toSet()
        val inDegree = mutableMapOf<String, Int>()
        val dependents = mutableMapOf<String, MutableList<String>>()

        for (task in tasks) {
            inDegree[task.id] = 0
            dependents[task.id] = mutableListOf()
        }

        // 1. Explicit user/spec dependsOn constraints
        for (task in tasks) {
            for (dep in task.dependsOn) {
                if (dep in taskIds) {
                    inDegree[task.id] = (inDegree[task.id] ?: 0) + 1
                    dependents[dep]?.add(task.id)
                }
            }
        }

        // 2. Disjoint Target Scheduling Invariant:
        // Group tasks by (workspace, targetFile). For each group with > 1 task, synthesize
        // sequential dependencies between them in order of creation/definition so they do not
        // run concurrently in the same tier.
        val targetGroups = tasks
            .filter { !it.targetFile.isNullOrBlank() }
            .groupBy { (it.workspace ?: "") to it.targetFile!! }

        for ((_, groupTasks) in targetGroups) {
            if (groupTasks.size > 1) {
                for (i in 0 until groupTasks.size - 1) {
                    val prevTask = groupTasks[i]
                    val nextTask = groupTasks[i + 1]
                    // If not already depending on prevTask
                    if (!nextTask.dependsOn.contains(prevTask.id)) {
                        inDegree[nextTask.id] = (inDegree[nextTask.id] ?: 0) + 1
                        dependents[prevTask.id]?.add(nextTask.id)
                    }
                }
            }
        }

        var currentTier = tasks.filter { (inDegree[it.id] ?: 0) == 0 }.map { it.id }
        val topologicalOrder = mutableListOf<String>()
        val executionTiers = mutableListOf<List<String>>()

        while (currentTier.isNotEmpty()) {
            executionTiers.add(currentTier)
            topologicalOrder.addAll(currentTier)
            val nextTier = mutableListOf<String>()

            for (taskId in currentTier) {
                for (dependentId in dependents[taskId] ?: emptyList()) {
                    val deg = (inDegree[dependentId] ?: 1) - 1
                    inDegree[dependentId] = deg
                    if (deg == 0) {
                        nextTier.add(dependentId)
                    }
                }
            }
            currentTier = nextTier
        }

        if (topologicalOrder.size < tasks.size) {
            val cyclicTasks = tasks.filter { (inDegree[it.id] ?: 0) > 0 }.map { it.id }
            throw CycleDetectedException("Circular dependency detected involving tasks: $cyclicTasks")
        }

        return Pair(topologicalOrder, executionTiers)
    }

    /**
     * Decomposes a Spec into discrete tasks, topologically sorts them, persists to repositories,
     * and returns the agent-executable JSON schema bundle.
     */
    suspend fun decomposeAndPersist(
        spec: Spec,
        tasks: List<Task>,
        workspace: String? = null,
    ): AgentTaskDAGExport {
        val now = currentTimeMillis()

        // 1. Verify acyclic & compute topological execution tiers
        val (topologicalOrder, executionTiers) = topologicalSort(tasks)

        // 2. Persist tasks to TaskRepository and DEPENDS_ON edges to ContextGraphRepository
        for (task in tasks) {
            val taskToSave = task.copy(
                projectId = task.projectId ?: spec.projectId,
                workspace = task.workspace ?: workspace,
                createdAt = if (task.createdAt > 0) task.createdAt else now,
                updatedAt = now,
            )
            taskRepository?.updateTask(taskToSave)

            // Save DEPENDS_ON edges into context graph
            if (contextGraphRepository != null) {
                for (depId in task.dependsOn) {
                    val edgeId = "${task.id}_DEPENDS_ON_$depId"
                    contextGraphRepository.saveEdge(
                        ContextEdge(
                            id = edgeId,
                            fromId = task.id,
                            toId = depId,
                            relation = "DEPENDS_ON",
                            createdAt = now,
                        )
                    )
                }
            }
        }

        // 3. Construct export data model
        val executableTasks = tasks.map { task ->
            AgentExecutableTask(
                id = task.id,
                title = task.title,
                description = task.description,
                type = task.type.name,
                workspace = task.workspace ?: workspace,
                targetFile = task.targetFile,
                contextFiles = task.contextFiles,
                acceptanceCriteria = task.acceptanceCriteria,
                dependsOn = task.dependsOn,
                canInstallPackages = task.agentPermissions?.canInstallPackages ?: false,
                allowedCommands = task.agentPermissions?.allowedCommands ?: emptyList(),
            )
        }

        return AgentTaskDAGExport(
            specId = spec.id,
            projectId = spec.projectId,
            generatedAt = now,
            totalTasks = tasks.size,
            topologicalOrder = topologicalOrder,
            executionTiers = executionTiers,
            tasks = executableTasks,
        )
    }

    /**
     * Serializes an [AgentTaskDAGExport] to a JSON formatted string.
     */
    fun exportToJson(dagExport: AgentTaskDAGExport): String {
        return json.encodeToString(dagExport)
    }
}
