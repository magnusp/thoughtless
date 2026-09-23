package github.magnusp.thoughtless.sync.atproto

import github.magnusp.thoughtless.domain.model.AgentTaskStatus
import github.magnusp.thoughtless.domain.model.ContextEdge
import github.magnusp.thoughtless.domain.model.ContextNode
import github.magnusp.thoughtless.domain.model.NodeType
import github.magnusp.thoughtless.domain.model.Project
import github.magnusp.thoughtless.domain.model.Spec
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.domain.model.TaskType
import github.magnusp.thoughtless.util.formatIsoTimestamp
import github.magnusp.thoughtless.util.parseIsoTimestamp

fun Task.toAtProtoRecord(): TaskRecord = TaskRecord(
    type = "thoughtless.task",
    title = title,
    description = description,
    status = status.value,
    priority = priority.level,
    dueDate = dueDate,
    projectId = projectId,
    workspace = workspace,
    createdAt = formatIsoTimestamp(createdAt),
    updatedAt = formatIsoTimestamp(updatedAt),
    completedAt = completedAt?.let { formatIsoTimestamp(it) },
)

fun TaskRecord.toDomain(id: String): Task = Task(
    id = id,
    projectId = projectId,
    title = title,
    description = description,
    status = TaskStatus.fromValue(status),
    priority = TaskPriority.fromLevel(priority),
    dueDate = dueDate,
    workspace = workspace,
    createdAt = parseIsoTimestamp(createdAt),
    updatedAt = updatedAt?.let { parseIsoTimestamp(it) } ?: parseIsoTimestamp(createdAt),
    completedAt = completedAt?.let { parseIsoTimestamp(it) },
)

fun Project.toAtProtoRecord(): ProjectRecord = ProjectRecord(
    type = "thoughtless.project",
    name = name,
    description = description,
    color = color,
    workspace = workspace,
    createdAt = formatIsoTimestamp(createdAt),
    updatedAt = formatIsoTimestamp(updatedAt),
)

fun ProjectRecord.toDomain(id: String): Project = Project(
    id = id,
    name = name,
    description = description,
    color = color,
    workspace = workspace,
    createdAt = parseIsoTimestamp(createdAt),
    updatedAt = updatedAt?.let { parseIsoTimestamp(it) } ?: parseIsoTimestamp(createdAt),
)

fun Spec.toAtProtoRecord(createdAtTimestamp: Long? = null, updatedAtTimestamp: Long? = null): SpecRecord = SpecRecord(
    type = "thoughtless.spec",
    projectId = projectId,
    title = title,
    systemSpec = systemSpec,
    nonGoals = nonGoals,
    rfcDocument = rfcDocument,
    frozenAt = frozenAt,
    createdAt = formatIsoTimestamp(createdAtTimestamp ?: frozenAt ?: 0L),
    updatedAt = updatedAtTimestamp?.let { formatIsoTimestamp(it) },
)

fun SpecRecord.toDomain(id: String): Spec = Spec(
    id = id,
    projectId = projectId,
    title = title,
    systemSpec = systemSpec,
    nonGoals = nonGoals,
    rfcDocument = rfcDocument,
    frozenAt = frozenAt,
)

fun ContextNode.toAtProtoRecord(): ContextNodeRecord = ContextNodeRecord(
    type = "thoughtless.contextNode",
    nodeId = id,
    nodeType = type.name,
    label = label,
    body = body,
    filePath = filePath,
    createdAt = formatIsoTimestamp(createdAt),
    updatedAt = formatIsoTimestamp(updatedAt),
)

fun ContextNodeRecord.toDomain(): ContextNode = ContextNode(
    id = nodeId,
    type = try { NodeType.valueOf(nodeType) } catch (_: Exception) { NodeType.REQUIREMENT },
    label = label,
    body = body,
    filePath = filePath,
    createdAt = parseIsoTimestamp(createdAt),
    updatedAt = updatedAt?.let { parseIsoTimestamp(it) } ?: parseIsoTimestamp(createdAt),
)

fun ContextEdge.toAtProtoRecord(): ContextEdgeRecord = ContextEdgeRecord(
    type = "thoughtless.contextEdge",
    edgeId = id,
    fromId = fromId,
    toId = toId,
    relation = relation,
    createdAt = formatIsoTimestamp(createdAt),
)

fun ContextEdgeRecord.toDomain(): ContextEdge = ContextEdge(
    id = edgeId,
    fromId = fromId,
    toId = toId,
    relation = relation,
    createdAt = parseIsoTimestamp(createdAt),
)

fun Task.toAgentTaskRecord(): AgentTaskRecord = AgentTaskRecord(
    type = "thoughtless.agentTask",
    title = title,
    description = description,
    agentStatus = agentStatus.name,
    projectId = projectId,
    workspace = workspace,
    targetFile = targetFile,
    contextFiles = contextFiles,
    dependsOn = dependsOn,
    acceptanceCriteria = acceptanceCriteria,
    createdAt = formatIsoTimestamp(createdAt),
    updatedAt = formatIsoTimestamp(updatedAt),
)

fun AgentTaskRecord.toDomain(id: String): Task = Task(
    id = id,
    projectId = projectId,
    title = title,
    description = description,
    type = TaskType.TASK,
    workspace = workspace,
    agentStatus = try { AgentTaskStatus.valueOf(agentStatus) } catch (_: Exception) { AgentTaskStatus.PENDING },
    status = if (agentStatus == AgentTaskStatus.MERGED.name) TaskStatus.DONE else TaskStatus.TODO,
    targetFile = targetFile,
    contextFiles = contextFiles,
    dependsOn = dependsOn,
    acceptanceCriteria = acceptanceCriteria,
    createdAt = parseIsoTimestamp(createdAt),
    updatedAt = updatedAt?.let { parseIsoTimestamp(it) } ?: parseIsoTimestamp(createdAt),
)
