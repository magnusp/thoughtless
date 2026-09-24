package github.magnusp.thoughtless.mcp.tools

import com.arcadedb.database.Database
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBEngine
import github.magnusp.thoughtless.domain.model.AgentScratchpad
import github.magnusp.thoughtless.domain.model.AgentTaskStatus
import github.magnusp.thoughtless.domain.model.ContextEdge
import github.magnusp.thoughtless.domain.model.ProposalStatus
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.domain.model.TaskType
import github.magnusp.thoughtless.domain.repository.ContextGraphRepository
import github.magnusp.thoughtless.domain.repository.TaskProposalRepository
import github.magnusp.thoughtless.domain.repository.TaskRepository
import github.magnusp.thoughtless.domain.validation.WorkspaceValidator
import github.magnusp.thoughtless.mcp.protocol.CallToolResult
import github.magnusp.thoughtless.mcp.protocol.ToolContent
import github.magnusp.thoughtless.mcp.protocol.ToolDefinition
import github.magnusp.thoughtless.service.CycleDetectedException
import github.magnusp.thoughtless.service.DAGDecomposerService
import github.magnusp.thoughtless.service.TaskProposalService
import github.magnusp.thoughtless.util.currentTimeMillis
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject

class ThoughtlessMcpTools(
    private val taskRepository: TaskRepository,
    private val taskProposalRepository: TaskProposalRepository,
    private val contextGraphRepository: ContextGraphRepository,
    private val taskProposalService: TaskProposalService,
    private val dagDecomposerService: DAGDecomposerService,
    private val arcadeDBEngine: ArcadeDBEngine,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun listToolDefinitions(): List<ToolDefinition> {
        return listOf(
            ToolDefinition(
                name = "propose_task",
                description = "Proposes a new task, architectural spike, exploration, or edge case discovered dynamically by an agent during planning or execution.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("title", buildJsonObject { put("type", "string"); put("description", "Clear title describing the discovered requirement") })
                        put("rationale", buildJsonObject { put("type", "string"); put("description", "Why this work is necessary or what was discovered") })
                        put("type", buildJsonObject {
                            put("type", "string")
                            putJsonArray("enum") {
                                add("TASK")
                                add("SPIKE")
                                add("EXPLORATION")
                                add("RFC")
                            }
                        })
                        put("suggestedWorkspace", buildJsonObject { put("type", "string"); put("description", "Canonical repository identifier (e.g. 'github.com/org/repo') or logical name. Host absolute paths are forbidden.") })
                        put("suggestedTargetFile", buildJsonObject { put("type", "string"); put("description", "Relative target file path identified as needing modification") })
                        put("suggestedContextFiles", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                        put("suggestedDependsOn", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                        put("acceptanceCriteria", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                        put("sourceTaskId", buildJsonObject { put("type", "string"); put("description", "The ID of the task the agent was executing when this discovery was made") })
                        put("projectId", buildJsonObject { put("type", "string") })
                    })
                    putJsonArray("required") {
                        add("title")
                        add("rationale")
                    }
                }
            ),
            ToolDefinition(
                name = "refine_task",
                description = "Updates an existing approved task with unambiguous requirements, acceptance criteria, canonical workspace, and relative target files.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("taskId", buildJsonObject { put("type", "string") })
                        put("title", buildJsonObject { put("type", "string") })
                        put("description", buildJsonObject { put("type", "string") })
                        put("workspace", buildJsonObject { put("type", "string"); put("description", "Canonical repository URL or logical workspace name. Host absolute paths forbidden.") })
                        put("targetFile", buildJsonObject { put("type", "string"); put("description", "Primary relative file the autonomous agent is expected to mutate") })
                        put("contextFiles", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                        put("acceptanceCriteria", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                        put("priority", buildJsonObject { put("type", "integer"); put("description", "Task priority (0=None, 1=Low, 2=Medium, 3=High, 4=Urgent)") })
                    })
                    putJsonArray("required") {
                        add("taskId")
                    }
                }
            ),
            ToolDefinition(
                name = "decompose_task",
                description = "Splits an approved parent task or formal specification into atomic, DAG-schedulable subtasks within its scope.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("parentTaskId", buildJsonObject { put("type", "string") })
                        put("specId", buildJsonObject { put("type", "string") })
                        put("workspace", buildJsonObject { put("type", "string") })
                        put("subtasks", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("id", buildJsonObject { put("type", "string") })
                                    put("title", buildJsonObject { put("type", "string") })
                                    put("workspace", buildJsonObject { put("type", "string") })
                                    put("targetFile", buildJsonObject { put("type", "string") })
                                    put("contextFiles", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                                    put("dependsOn", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                                    put("acceptanceCriteria", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                                })
                                putJsonArray("required") {
                                    add("id")
                                    add("title")
                                }
                            })
                        })
                    })
                    putJsonArray("required") {
                        add("subtasks")
                    }
                }
            ),
            ToolDefinition(
                name = "validate_task_dag",
                description = "Executes Kahn's topological sort and cycle detection over tasks. Ensures the Disjoint Target Invariant so identical (workspace, targetFile) tasks run in distinct tiers.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("taskIds", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                    })
                }
            ),
            ToolDefinition(
                name = "get_active_dag",
                description = "Retrieves the active task execution DAG partitioned into parallel execution tiers according to Kahn's algorithm.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("projectId", buildJsonObject { put("type", "string") })
                    })
                }
            ),
            ToolDefinition(
                name = "get_node_context",
                description = "Retrieves a graph node's content, properties, incoming backlinks, and outgoing relations.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("nodeId", buildJsonObject { put("type", "string") })
                    })
                    putJsonArray("required") {
                        add("nodeId")
                    }
                }
            ),
            ToolDefinition(
                name = "analyze_impact",
                description = "Traverses semantic graph edges (MUTATES, DEPENDS_ON, IMPLEMENTS, REFERENCES) to discover downstream nodes impacted by changes.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("nodeId", buildJsonObject { put("type", "string") })
                        put("maxHops", buildJsonObject { put("type", "integer") })
                    })
                    putJsonArray("required") {
                        add("nodeId")
                    }
                }
            ),
            ToolDefinition(
                name = "query_graph",
                description = "Executes an OpenCypher query directly against the embedded ArcadeDB property graph.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject { put("type", "string") })
                    })
                    putJsonArray("required") {
                        add("query")
                    }
                }
            ),
            ToolDefinition(
                name = "claim_next_task",
                description = "Claims the highest priority task ready for execution from Tier 1 (dependencies satisfied), marking it AGENT_RUNNING.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("agentId", buildJsonObject { put("type", "string") })
                        put("projectId", buildJsonObject { put("type", "string") })
                    })
                    putJsonArray("required") {
                        add("agentId")
                    }
                }
            ),
            ToolDefinition(
                name = "update_task_progress",
                description = "Updates the agent execution scratchpad while in AGENT_RUNNING state (current step, notes, touched files, completed criteria).",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("taskId", buildJsonObject { put("type", "string") })
                        put("currentStep", buildJsonObject { put("type", "string") })
                        put("notes", buildJsonObject { put("type", "string") })
                        put("touchedFiles", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                        put("completedCriteria", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                    })
                    putJsonArray("required") {
                        add("taskId")
                    }
                }
            ),
            ToolDefinition(
                name = "submit_task_for_review",
                description = "Submits completed agent work and transitions task status to AWAITING_REVIEW with verification results.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("taskId", buildJsonObject { put("type", "string") })
                        put("reviewSummary", buildJsonObject { put("type", "string") })
                        put("diffUrlOrBranch", buildJsonObject { put("type", "string") })
                        put("testsPassed", buildJsonObject { put("type", "boolean") })
                        put("verificationOutput", buildJsonObject { put("type", "string") })
                    })
                    putJsonArray("required") {
                        add("taskId")
                        add("reviewSummary")
                        add("testsPassed")
                    }
                }
            )
        )
    }

    suspend fun executeTool(name: String, args: JsonObject): CallToolResult {
        return try {
            when (name) {
                "propose_task" -> handleProposeTask(args)
                "refine_task" -> handleRefineTask(args)
                "decompose_task" -> handleDecomposeTask(args)
                "validate_task_dag" -> handleValidateTaskDag(args)
                "get_active_dag" -> handleGetActiveDag(args)
                "get_node_context" -> handleGetNodeContext(args)
                "analyze_impact" -> handleAnalyzeImpact(args)
                "query_graph" -> handleQueryGraph(args)
                "claim_next_task" -> handleClaimNextTask(args)
                "update_task_progress" -> handleUpdateTaskProgress(args)
                "submit_task_for_review" -> handleSubmitTaskForReview(args)
                else -> errorResult("Unknown tool '$name'")
            }
        } catch (e: IllegalArgumentException) {
            errorResult("Validation error: ${e.message}")
        } catch (e: CycleDetectedException) {
            errorResult("DAG cycle error: ${e.message}")
        } catch (e: Exception) {
            errorResult("Internal tool error: ${e.message}")
        }
    }

    private suspend fun handleProposeTask(args: JsonObject): CallToolResult {
        val title = args["title"]?.jsonPrimitive?.content ?: return errorResult("Missing required parameter 'title'")
        val rationale = args["rationale"]?.jsonPrimitive?.content ?: return errorResult("Missing required parameter 'rationale'")
        val typeStr = args["type"]?.jsonPrimitive?.content ?: "TASK"
        val type = try { TaskType.valueOf(typeStr) } catch (_: Exception) { TaskType.TASK }
        val suggestedWorkspace = args["suggestedWorkspace"]?.jsonPrimitive?.content
        val suggestedTargetFile = args["suggestedTargetFile"]?.jsonPrimitive?.content
        val suggestedContextFiles = args["suggestedContextFiles"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val suggestedDependsOn = args["suggestedDependsOn"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val acceptanceCriteria = args["acceptanceCriteria"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val sourceTaskId = args["sourceTaskId"]?.jsonPrimitive?.content
        val projectId = args["projectId"]?.jsonPrimitive?.content

        val proposal = taskProposalService.proposeTask(
            title = title,
            rationale = rationale,
            type = type,
            suggestedWorkspace = suggestedWorkspace,
            suggestedTargetFile = suggestedTargetFile,
            suggestedContextFiles = suggestedContextFiles,
            suggestedDependsOn = suggestedDependsOn,
            acceptanceCriteria = acceptanceCriteria,
            sourceTaskId = sourceTaskId,
            projectId = projectId
        )

        return successResult(buildJsonObject {
            put("proposalId", proposal.id)
            put("status", proposal.status.name)
            put("title", proposal.title)
            put("workspace", proposal.suggestedWorkspace ?: "")
            put("suggestedTargetFile", proposal.suggestedTargetFile ?: "")
            putJsonArray("suggestedContextFiles") { proposal.suggestedContextFiles.forEach { add(it) } }
            putJsonArray("suggestedDependsOn") { proposal.suggestedDependsOn.forEach { add(it) } }
            putJsonArray("acceptanceCriteria") { proposal.acceptanceCriteria.forEach { add(it) } }
            put("createdAt", proposal.createdAt)
        })
    }

    private suspend fun handleRefineTask(args: JsonObject): CallToolResult {
        val taskId = args["taskId"]?.jsonPrimitive?.content ?: return errorResult("Missing required parameter 'taskId'")
        val task = taskRepository.getTaskById(taskId).first() ?: return errorResult("Task '$taskId' not found")

        val title = args["title"]?.jsonPrimitive?.content ?: task.title
        val description = args["description"]?.jsonPrimitive?.content ?: task.description
        val workspace = if (args.containsKey("workspace")) args["workspace"]?.jsonPrimitive?.content else task.workspace
        val targetFile = if (args.containsKey("targetFile")) args["targetFile"]?.jsonPrimitive?.content else task.targetFile
        val contextFiles = args["contextFiles"]?.jsonArray?.map { it.jsonPrimitive.content } ?: task.contextFiles
        val acceptanceCriteria = args["acceptanceCriteria"]?.jsonArray?.map { it.jsonPrimitive.content } ?: task.acceptanceCriteria
        val priorityLevel = args["priority"]?.jsonPrimitive?.intOrNull
        val priority = if (priorityLevel != null) TaskPriority.fromLevel(priorityLevel.toLong()) else task.priority

        WorkspaceValidator.validateWorkspace(workspace)
        WorkspaceValidator.validateRelativePath(targetFile, "targetFile")
        contextFiles.forEach { WorkspaceValidator.validateRelativePath(it, "contextFile") }

        val updated = task.copy(
            title = title,
            description = description,
            workspace = workspace,
            targetFile = targetFile,
            contextFiles = contextFiles,
            acceptanceCriteria = acceptanceCriteria,
            priority = priority,
            updatedAt = currentTimeMillis()
        )
        taskRepository.updateTask(updated)

        return successResult(buildJsonObject {
            put("taskId", updated.id)
            put("title", updated.title)
            put("workspace", updated.workspace ?: "")
            put("targetFile", updated.targetFile ?: "")
            put("priority", updated.priority.displayName)
            putJsonArray("contextFiles") { updated.contextFiles.forEach { add(it) } }
            putJsonArray("acceptanceCriteria") { updated.acceptanceCriteria.forEach { add(it) } }
        })
    }

    private suspend fun handleDecomposeTask(args: JsonObject): CallToolResult {
        val parentTaskId = args["parentTaskId"]?.jsonPrimitive?.content
        val defaultWorkspace = args["workspace"]?.jsonPrimitive?.content
        WorkspaceValidator.validateWorkspace(defaultWorkspace)

        val subtasksJson = args["subtasks"]?.jsonArray ?: return errorResult("Missing required 'subtasks' array")
        val now = currentTimeMillis()
        val createdTasks = mutableListOf<Task>()

        for (item in subtasksJson) {
            val obj = item.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return errorResult("Subtask missing required 'id'")
            val title = obj["title"]?.jsonPrimitive?.content ?: return errorResult("Subtask missing required 'title'")
            val ws = obj["workspace"]?.jsonPrimitive?.content ?: defaultWorkspace
            val targetFile = obj["targetFile"]?.jsonPrimitive?.content
            val contextFiles = obj["contextFiles"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val dependsOn = obj["dependsOn"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val criteria = obj["acceptanceCriteria"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

            WorkspaceValidator.validateWorkspace(ws)
            WorkspaceValidator.validateRelativePath(targetFile, "targetFile")
            contextFiles.forEach { WorkspaceValidator.validateRelativePath(it, "contextFile") }

            val task = Task(
                id = id,
                title = title,
                workspace = ws,
                targetFile = targetFile,
                contextFiles = contextFiles,
                dependsOn = dependsOn,
                acceptanceCriteria = criteria,
                status = TaskStatus.TODO,
                createdAt = now,
                updatedAt = now,
            )
            createdTasks.add(task)
        }

        // Validate topological acyclicity
        dagDecomposerService.topologicalSort(createdTasks)

        for (task in createdTasks) {
            taskRepository.updateTask(task)
            for (dep in task.dependsOn) {
                val edge = ContextEdge(
                    id = "${task.id}_DEPENDS_ON_$dep",
                    fromId = task.id,
                    toId = dep,
                    relation = "DEPENDS_ON",
                    createdAt = now
                )
                contextGraphRepository.saveEdge(edge)
            }
            if (parentTaskId != null) {
                contextGraphRepository.saveEdge(
                    ContextEdge(
                        id = "${task.id}_CHILD_OF_$parentTaskId",
                        fromId = task.id,
                        toId = parentTaskId,
                        relation = "IMPLEMENTS",
                        createdAt = now
                    )
                )
            }
        }

        return successResult(buildJsonObject {
            put("createdTasksCount", createdTasks.size)
            putJsonArray("tasks") {
                createdTasks.forEach { t ->
                    addJsonObject {
                        put("id", t.id)
                        put("title", t.title)
                        put("workspace", t.workspace ?: "")
                        put("targetFile", t.targetFile ?: "")
                        putJsonArray("dependsOn") { t.dependsOn.forEach { add(it) } }
                    }
                }
            }
        })
    }

    private suspend fun handleValidateTaskDag(args: JsonObject): CallToolResult {
        val taskIds = args["taskIds"]?.jsonArray?.map { it.jsonPrimitive.content }
        val allTasks = taskRepository.getTasks().first()
        val tasksToValidate = if (!taskIds.isNullOrEmpty()) {
            allTasks.filter { it.id in taskIds }
        } else {
            allTasks.filter { it.status != TaskStatus.DONE && it.status != TaskStatus.CANCELLED }
        }

        return try {
            val (topologicalOrder, executionTiers) = dagDecomposerService.topologicalSort(tasksToValidate)
            successResult(buildJsonObject {
                put("isValid", true)
                putJsonArray("topologicalOrder") { topologicalOrder.forEach { add(it) } }
                putJsonArray("executionTiers") {
                    executionTiers.forEach { tier ->
                        add(buildJsonArray(tier))
                    }
                }
            })
        } catch (e: CycleDetectedException) {
            successResult(buildJsonObject {
                put("isValid", false)
                put("cycleError", e.message ?: "Cycle detected")
            })
        }
    }

    private suspend fun handleGetActiveDag(args: JsonObject): CallToolResult {
        val projectId = args["projectId"]?.jsonPrimitive?.content
        val tasks = if (projectId != null) {
            taskRepository.getTasksByProject(projectId).first()
        } else {
            taskRepository.getTasks().first()
        }.filter { it.status != TaskStatus.DONE && it.status != TaskStatus.CANCELLED }

        val (topologicalOrder, executionTiers) = dagDecomposerService.topologicalSort(tasks)
        return successResult(buildJsonObject {
            put("totalTasks", tasks.size)
            putJsonArray("topologicalOrder") { topologicalOrder.forEach { add(it) } }
            putJsonArray("executionTiers") {
                executionTiers.forEach { tier ->
                    add(buildJsonArray(tier))
                }
            }
        })
    }

    private fun buildJsonArray(strings: List<String>): JsonArray {
        return kotlinx.serialization.json.buildJsonArray {
            strings.forEach { add(it) }
        }
    }

    private suspend fun handleGetNodeContext(args: JsonObject): CallToolResult {
        val nodeId = args["nodeId"]?.jsonPrimitive?.content ?: return errorResult("Missing required 'nodeId'")
        val node = contextGraphRepository.getNodeById(nodeId).first()
            ?: return errorResult("Node '$nodeId' not found in context graph")
        val backlinks = contextGraphRepository.getBacklinks(nodeId)
        val outgoingEdges = contextGraphRepository.getEdges().first().filter { it.fromId == nodeId }

        return successResult(buildJsonObject {
            put("id", node.id)
            put("label", node.label)
            put("type", node.type.name)
            put("body", node.body)
            put("filePath", node.filePath ?: "")
            putJsonArray("backlinks") {
                backlinks.forEach { b ->
                    addJsonObject {
                        put("id", b.id)
                        put("label", b.label)
                        put("type", b.type.name)
                    }
                }
            }
            putJsonArray("outgoing") {
                outgoingEdges.forEach { e ->
                    addJsonObject {
                        put("toId", e.toId)
                        put("relation", e.relation)
                    }
                }
            }
        })
    }

    private suspend fun handleAnalyzeImpact(args: JsonObject): CallToolResult {
        val nodeId = args["nodeId"]?.jsonPrimitive?.content ?: return errorResult("Missing required 'nodeId'")
        val impacted = contextGraphRepository.analyzeImpact(nodeId)
        return successResult(buildJsonObject {
            put("nodeId", nodeId)
            put("impactedCount", impacted.size)
            putJsonArray("impactedNodes") {
                impacted.forEach { n ->
                    addJsonObject {
                        put("id", n.id)
                        put("label", n.label)
                        put("type", n.type.name)
                        put("filePath", n.filePath ?: "")
                    }
                }
            }
        })
    }

    private fun handleQueryGraph(args: JsonObject): CallToolResult {
        val query = args["query"]?.jsonPrimitive?.content ?: return errorResult("Missing required 'query'")
        return try {
            val results = arcadeDBEngine.transaction { db ->
                val rs = db.query("cypher", query)
                val rows = mutableListOf<JsonObject>()
                while (rs.hasNext()) {
                    val row = rs.next()
                    val map = mutableMapOf<String, JsonElement>()
                    for (prop in row.propertyNames) {
                        val v = row.getProperty<Any>(prop)
                        map[prop] = when (v) {
                            null -> JsonNull
                            is Number -> JsonPrimitive(v)
                            is Boolean -> JsonPrimitive(v)
                            else -> JsonPrimitive(v.toString())
                        }
                    }
                    rows.add(JsonObject(map))
                }
                rows
            }
            successResult(buildJsonObject {
                putJsonArray("records") { results.forEach { add(it) } }
            })
        } catch (e: Exception) {
            errorResult("OpenCypher query execution failed: ${e.message}")
        }
    }

    private suspend fun handleClaimNextTask(args: JsonObject): CallToolResult {
        val agentId = args["agentId"]?.jsonPrimitive?.content ?: return errorResult("Missing required 'agentId'")
        val projectId = args["projectId"]?.jsonPrimitive?.content

        val tasks = if (projectId != null) {
            taskRepository.getTasksByProject(projectId).first()
        } else {
            taskRepository.getTasks().first()
        }

        // Active/pending tasks
        val pendingTasks = tasks.filter {
            it.status != TaskStatus.DONE && it.status != TaskStatus.CANCELLED && it.agentStatus == AgentTaskStatus.PENDING
        }
        if (pendingTasks.isEmpty()) {
            return successResult(buildJsonObject {
                put("taskClaimed", false)
                put("message", "No pending tasks available to claim.")
            })
        }

        val (_, tiers) = dagDecomposerService.topologicalSort(tasks.filter { it.status != TaskStatus.DONE && it.status != TaskStatus.CANCELLED })
        val tier1Ids = tiers.firstOrNull()?.toSet() ?: emptySet()
        val claimable = pendingTasks.filter { it.id in tier1Ids }
            .sortedByDescending { it.priority.level }

        val taskToClaim = claimable.firstOrNull()
            ?: return successResult(buildJsonObject {
                put("taskClaimed", false)
                put("message", "No Tier 1 tasks currently unblocked.")
            })

        val updatedTask = taskToClaim.copy(
            agentStatus = AgentTaskStatus.AGENT_RUNNING,
            agentScratchpad = AgentScratchpad(
                currentStep = "Claimed by agent $agentId",
                lastUpdated = currentTimeMillis()
            )
        )
        taskRepository.updateTask(updatedTask)

        return successResult(buildJsonObject {
            put("taskClaimed", true)
            put("taskId", updatedTask.id)
            put("title", updatedTask.title)
            put("workspace", updatedTask.workspace ?: "")
            put("targetFile", updatedTask.targetFile ?: "")
            putJsonArray("contextFiles") { updatedTask.contextFiles.forEach { add(it) } }
            putJsonArray("acceptanceCriteria") { updatedTask.acceptanceCriteria.forEach { add(it) } }
            put("agentStatus", updatedTask.agentStatus.name)
            put("worktreeHint", "git worktree add -B agent/${updatedTask.id} /tmp/workspaces/${updatedTask.id} origin/main")
        })
    }

    private suspend fun handleUpdateTaskProgress(args: JsonObject): CallToolResult {
        val taskId = args["taskId"]?.jsonPrimitive?.content ?: return errorResult("Missing required 'taskId'")
        val task = taskRepository.getTaskById(taskId).first() ?: return errorResult("Task '$taskId' not found")

        val currentStep = args["currentStep"]?.jsonPrimitive?.content ?: task.agentScratchpad?.currentStep
        val notes = args["notes"]?.jsonPrimitive?.content ?: task.agentScratchpad?.notes
        val touchedFiles = args["touchedFiles"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: task.agentScratchpad?.touchedFiles ?: emptyList()
        val completedCriteria = args["completedCriteria"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: task.agentScratchpad?.completedCriteria ?: emptyList()

        touchedFiles.forEach { WorkspaceValidator.validateRelativePath(it, "touchedFile") }

        val scratchpad = AgentScratchpad(
            currentStep = currentStep,
            notes = notes,
            touchedFiles = touchedFiles,
            completedCriteria = completedCriteria,
            lastUpdated = currentTimeMillis()
        )
        taskRepository.updateAgentScratchpad(taskId, scratchpad)

        return successResult(buildJsonObject {
            put("taskId", taskId)
            put("lastUpdated", scratchpad.lastUpdated)
            put("currentStep", scratchpad.currentStep ?: "")
            put("notes", scratchpad.notes ?: "")
            putJsonArray("touchedFiles") { scratchpad.touchedFiles.forEach { add(it) } }
            putJsonArray("completedCriteria") { scratchpad.completedCriteria.forEach { add(it) } }
        })
    }

    private suspend fun handleSubmitTaskForReview(args: JsonObject): CallToolResult {
        val taskId = args["taskId"]?.jsonPrimitive?.content ?: return errorResult("Missing required 'taskId'")
        val reviewSummary = args["reviewSummary"]?.jsonPrimitive?.content ?: return errorResult("Missing required 'reviewSummary'")
        val testsPassed = args["testsPassed"]?.jsonPrimitive?.booleanOrNull ?: return errorResult("Missing required 'testsPassed'")
        val diffUrlOrBranch = args["diffUrlOrBranch"]?.jsonPrimitive?.content
        val verificationOutput = args["verificationOutput"]?.jsonPrimitive?.content

        val task = taskRepository.getTaskById(taskId).first() ?: return errorResult("Task '$taskId' not found")
        val updatedNotes = buildString {
            if (!task.agentScratchpad?.notes.isNullOrBlank()) {
                append(task.agentScratchpad?.notes).append("\n\n")
            }
            append("### Review Summary\n").append(reviewSummary).append("\n")
            if (diffUrlOrBranch != null) append("Branch/Diff: ").append(diffUrlOrBranch).append("\n")
            append("Tests Passed: ").append(testsPassed).append("\n")
            if (verificationOutput != null) append("Verification Output:\n").append(verificationOutput).append("\n")
        }

        val updatedScratchpad = (task.agentScratchpad ?: AgentScratchpad()).copy(
            notes = updatedNotes,
            lastUpdated = currentTimeMillis()
        )

        val updatedTask = task.copy(
            agentStatus = AgentTaskStatus.AWAITING_REVIEW,
            agentScratchpad = updatedScratchpad,
            updatedAt = currentTimeMillis()
        )
        taskRepository.updateTask(updatedTask)
        taskRepository.updateAgentScratchpad(taskId, updatedScratchpad)

        return successResult(buildJsonObject {
            put("taskId", taskId)
            put("agentStatus", updatedTask.agentStatus.name)
            put("readyForReview", true)
            put("testsPassed", testsPassed)
        })
    }

    private fun successResult(data: JsonObject): CallToolResult {
        return CallToolResult(
            content = listOf(ToolContent(text = json.encodeToString(JsonObject.serializer(), data))),
            isError = false
        )
    }

    private fun errorResult(message: String): CallToolResult {
        return CallToolResult(
            content = listOf(ToolContent(text = message)),
            isError = true
        )
    }
}
