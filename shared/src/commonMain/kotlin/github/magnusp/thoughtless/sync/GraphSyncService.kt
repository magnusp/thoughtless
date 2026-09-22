package github.magnusp.thoughtless.sync

import github.magnusp.thoughtless.domain.model.ContextEdge
import github.magnusp.thoughtless.domain.model.ContextNode
import github.magnusp.thoughtless.domain.model.Spec
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.repository.ContextGraphRepository
import github.magnusp.thoughtless.domain.repository.SpecRepository
import github.magnusp.thoughtless.domain.repository.TaskRepository
import github.magnusp.thoughtless.sync.atproto.AtProtoClient
import github.magnusp.thoughtless.sync.atproto.toAgentTaskRecord
import github.magnusp.thoughtless.sync.atproto.toAtProtoRecord
import github.magnusp.thoughtless.sync.atproto.toDomain
import github.magnusp.thoughtless.util.parseIsoTimestamp
import kotlinx.coroutines.flow.first

data class SyncResult(
    val pushedNodes: Int = 0,
    val pushedEdges: Int = 0,
    val pushedSpecs: Int = 0,
    val pushedTasks: Int = 0,
    val pulledNodes: Int = 0,
    val pulledEdges: Int = 0,
    val pulledSpecs: Int = 0,
    val pulledTasks: Int = 0,
)

class GraphSyncService(
    private val atProtoClient: AtProtoClient,
    private val contextGraphRepository: ContextGraphRepository,
    private val specRepository: SpecRepository,
    private val taskRepository: TaskRepository,
) {

    /**
     * Encodes arbitrary node ID into an ATProto-safe record key (rkey).
     * Disallowed characters like '#' and '/' are replaced with '~'.
     */
    fun toRkey(id: String): String =
        id.replace("#", "~").replace("/", "-").replace(":", "_")

    fun fromRkey(rkey: String): String =
        rkey.replace("~", "#")

    /**
     * Push all local ArcadeDB context graph state to user's PDS.
     */
    suspend fun pushLocalGraphToPds(): SyncResult {
        var pushedNodes = 0
        var pushedEdges = 0
        var pushedSpecs = 0
        var pushedTasks = 0

        // 1. Push Specs
        val specs = specRepository.getSpecs().first()
        for (spec in specs) {
            val rkey = toRkey(spec.id)
            val record = spec.toAtProtoRecord()
            atProtoClient.putSpecRecord(rkey, record)
            pushedSpecs++
        }

        // 2. Push Context Nodes
        val nodes = contextGraphRepository.getNodes().first()
        for (node in nodes) {
            val rkey = toRkey(node.id)
            val record = node.toAtProtoRecord()
            atProtoClient.putContextNodeRecord(rkey, record)
            pushedNodes++
        }

        // 3. Push Context Edges
        val edges = contextGraphRepository.getEdges().first()
        for (edge in edges) {
            val rkey = toRkey(edge.id)
            val record = edge.toAtProtoRecord()
            atProtoClient.putContextEdgeRecord(rkey, record)
            pushedEdges++
        }

        // 4. Push Agent Tasks
        val tasks = taskRepository.getTasks().first()
        for (task in tasks) {
            val rkey = toRkey(task.id)
            val record = task.toAgentTaskRecord()
            atProtoClient.putAgentTaskRecord(rkey, record)
            pushedTasks++
        }

        return SyncResult(
            pushedNodes = pushedNodes,
            pushedEdges = pushedEdges,
            pushedSpecs = pushedSpecs,
            pushedTasks = pushedTasks,
        )
    }

    /**
     * Pull remote records from a peer DID and merge into local ArcadeDB repositories
     * using Last-Write-Wins (LWW) conflict resolution.
     */
    suspend fun pullPeerGraph(peerDid: String): SyncResult {
        var pulledNodes = 0
        var pulledEdges = 0
        var pulledSpecs = 0
        var pulledTasks = 0

        // 1. Pull Specs
        val remoteSpecs = atProtoClient.listSpecRecords(limit = 100, repo = peerDid)
        for (item in remoteSpecs.records) {
            val rkey = item.uri.substringAfterLast("/")
            val domainSpec = item.value.toDomain(fromRkey(rkey))
            val localSpec = specRepository.getSpecById(domainSpec.id).first()

            val remoteUpdatedAt = item.value.updatedAt?.let { parseIsoTimestamp(it) }
                ?: parseIsoTimestamp(item.value.createdAt)

            // LWW comparison
            if (localSpec == null || remoteUpdatedAt >= (localSpec.frozenAt ?: 0L)) {
                specRepository.saveSpec(domainSpec)
                pulledSpecs++
            }
        }

        // 2. Pull Context Nodes
        val remoteNodes = atProtoClient.listContextNodeRecords(limit = 100, repo = peerDid)
        for (item in remoteNodes.records) {
            val domainNode = item.value.toDomain()
            val localNode = contextGraphRepository.getNodeById(domainNode.id).first()

            val remoteUpdatedAt = parseIsoTimestamp(item.value.updatedAt ?: item.value.createdAt)
            if (localNode == null || remoteUpdatedAt >= localNode.updatedAt) {
                contextGraphRepository.saveNode(domainNode)
                pulledNodes++
            }
        }

        // 3. Pull Context Edges
        val remoteEdges = atProtoClient.listContextEdgeRecords(limit = 100, repo = peerDid)
        for (item in remoteEdges.records) {
            val domainEdge = item.value.toDomain()
            contextGraphRepository.saveEdge(domainEdge)
            pulledEdges++
        }

        // 4. Pull Agent Tasks
        val remoteTasks = atProtoClient.listAgentTaskRecords(limit = 100, repo = peerDid)
        for (item in remoteTasks.records) {
            val rkey = item.uri.substringAfterLast("/")
            val domainTask = item.value.toDomain(fromRkey(rkey))
            val localTask = taskRepository.getTaskById(domainTask.id).first()

            val remoteUpdatedAt = parseIsoTimestamp(item.value.updatedAt ?: item.value.createdAt)
            if (localTask == null || remoteUpdatedAt >= localTask.updatedAt) {
                taskRepository.updateTask(domainTask)
                pulledTasks++
            }
        }

        return SyncResult(
            pulledNodes = pulledNodes,
            pulledEdges = pulledEdges,
            pulledSpecs = pulledSpecs,
            pulledTasks = pulledTasks,
        )
    }
}
