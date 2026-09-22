package github.magnusp.thoughtless.data.arcadedb

import com.arcadedb.database.Database
import com.arcadedb.graph.MutableEdge
import com.arcadedb.graph.MutableVertex
import com.arcadedb.graph.Vertex
import github.magnusp.thoughtless.domain.model.ContextEdge
import github.magnusp.thoughtless.domain.model.ContextNode
import github.magnusp.thoughtless.domain.model.NodeType
import github.magnusp.thoughtless.domain.repository.ContextGraphRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow

class ArcadeDBContextGraphRepository(
    private val engine: ArcadeDBEngine,
) : ContextGraphRepository {

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

    override fun getNodes(): Flow<List<ContextNode>> = flow {
        emit(loadAllNodes())
        mutationFlow.asSharedFlow().collect {
            emit(loadAllNodes())
        }
    }

    override fun getNodeById(id: String): Flow<ContextNode?> = flow {
        emit(loadNodeById(id))
        mutationFlow.asSharedFlow().collect {
            emit(loadNodeById(id))
        }
    }

    private fun loadAllNodes(): List<ContextNode> {
        ensureDatabase()
        return engine.transaction { db ->
            val nodes = mutableListOf<ContextNode>()
            db.scanType("ContextNode", true) { record ->
                val vertex = record.asVertex()
                nodes.add(vertexToNode(vertex))
                true
            }
            nodes.sortedByDescending { it.createdAt }
        }
    }

    private fun loadNodeById(id: String): ContextNode? {
        ensureDatabase()
        return engine.transaction { db ->
            val cursor = db.lookupByKey("ContextNode", "id", id)
            if (cursor.hasNext()) {
                vertexToNode(cursor.next().asVertex())
            } else {
                null
            }
        }
    }

    override suspend fun saveNode(node: ContextNode): ContextNode {
        ensureDatabase()
        engine.transaction { db ->
            val cursor = db.lookupByKey("ContextNode", "id", node.id)
            val vertex = if (cursor.hasNext()) {
                cursor.next().asVertex().modify()
            } else {
                db.newVertex("ContextNode")
            }
            setNodeProperties(vertex, node)
            vertex.save()
        }
        notifyMutation()
        return node
    }

    override suspend fun deleteNode(id: String) {
        ensureDatabase()
        engine.transaction { db ->
            val cursor = db.lookupByKey("ContextNode", "id", id)
            if (cursor.hasNext()) {
                val vertex = cursor.next().asVertex()
                vertex.delete()
            }
        }
        notifyMutation()
    }

    override fun getEdges(): Flow<List<ContextEdge>> = flow {
        emit(loadAllEdges())
        mutationFlow.asSharedFlow().collect {
            emit(loadAllEdges())
        }
    }

    override fun getEdgesForNode(nodeId: String): Flow<List<ContextEdge>> = flow {
        emit(loadEdgesForNode(nodeId))
        mutationFlow.asSharedFlow().collect {
            emit(loadEdgesForNode(nodeId))
        }
    }

    private fun loadAllEdges(): List<ContextEdge> {
        ensureDatabase()
        return engine.transaction { db ->
            val edges = mutableListOf<ContextEdge>()
            val supportedEdgeTypes = listOf("REFERENCES", "IMPLEMENTS", "MUTATES", "DEPENDS_ON")
            for (edgeType in supportedEdgeTypes) {
                db.scanType(edgeType, true) { record ->
                    val edge = record.asEdge()
                    val parsed = edgeToContextEdge(edge)
                    if (parsed != null) {
                        edges.add(parsed)
                    }
                    true
                }
            }
            edges.sortedByDescending { it.createdAt }
        }
    }

    private fun loadEdgesForNode(nodeId: String): List<ContextEdge> {
        ensureDatabase()
        return engine.transaction { db ->
            val cursor = db.lookupByKey("ContextNode", "id", nodeId)
            if (!cursor.hasNext()) return@transaction emptyList()
            val vertex = cursor.next().asVertex()
            val edges = mutableListOf<ContextEdge>()
            for (edge in vertex.getEdges(Vertex.DIRECTION.BOTH)) {
                val parsed = edgeToContextEdge(edge)
                if (parsed != null) {
                    edges.add(parsed)
                }
            }
            edges.distinctBy { it.id }.sortedByDescending { it.createdAt }
        }
    }

    override suspend fun saveEdge(edge: ContextEdge): ContextEdge {
        ensureDatabase()
        engine.transaction { db ->
            val fromCursor = db.lookupByKey("ContextNode", "id", edge.fromId)
            val fromVertex = if (fromCursor.hasNext()) {
                fromCursor.next().asVertex()
            } else {
                val placeholder = db.newVertex("ContextNode")
                placeholder.set("id", edge.fromId)
                placeholder.set("type", NodeType.ENTITY.name)
                placeholder.set("label", edge.fromId)
                placeholder.set("body", "")
                placeholder.set("createdAt", edge.createdAt)
                placeholder.set("updatedAt", edge.createdAt)
                placeholder.save()
                placeholder
            }

            val toCursor = db.lookupByKey("ContextNode", "id", edge.toId)
            val toVertex = if (toCursor.hasNext()) {
                toCursor.next().asVertex()
            } else {
                val placeholder = db.newVertex("ContextNode")
                placeholder.set("id", edge.toId)
                placeholder.set("type", NodeType.ENTITY.name)
                placeholder.set("label", edge.toId)
                placeholder.set("body", "")
                placeholder.set("createdAt", edge.createdAt)
                placeholder.set("updatedAt", edge.createdAt)
                placeholder.save()
                placeholder
            }

            val normalizedRelation = edge.relation.uppercase()
            db.schema.getOrCreateEdgeType(normalizedRelation)

            // Check if edge with same ID exists
            var existingEdge: MutableEdge? = null
            for (e in fromVertex.getEdges(Vertex.DIRECTION.OUT, normalizedRelation)) {
                if (e.has("id") && e.getString("id") == edge.id) {
                    existingEdge = e.modify()
                    break
                }
            }

            val edgeRecord = existingEdge ?: fromVertex.modify().newEdge(normalizedRelation, toVertex, true)
            edgeRecord.set("id", edge.id)
            edgeRecord.set("relation", edge.relation)
            edgeRecord.set("createdAt", edge.createdAt)
            edgeRecord.save()
        }
        notifyMutation()
        return edge
    }

    override suspend fun deleteEdge(id: String) {
        ensureDatabase()
        engine.transaction { db ->
            val supportedEdgeTypes = listOf("REFERENCES", "IMPLEMENTS", "MUTATES", "DEPENDS_ON")
            for (edgeType in supportedEdgeTypes) {
                db.scanType(edgeType, true) { record ->
                    val edge = record.asEdge()
                    if (edge.has("id") && edge.getString("id") == id) {
                        edge.delete()
                    }
                    true
                }
            }
        }
        notifyMutation()
    }

    override suspend fun findRelated(nodeId: String, hops: Int): List<ContextNode> {
        ensureDatabase()
        val validHops = if (hops < 1) 1 else hops
        val query = "MATCH (n:ContextNode {id: \$id})-[*1..$validHops]-(m:ContextNode) RETURN DISTINCT m"
        return engine.transaction { db ->
            val resultSet = db.query("cypher", query, mapOf("id" to nodeId))
            val result = mutableListOf<ContextNode>()
            while (resultSet.hasNext()) {
                val row = resultSet.next()
                val vertex = row.getProperty<Vertex>("m")
                if (vertex != null) {
                    result.add(vertexToNode(vertex))
                }
            }
            result
        }
    }

    override suspend fun findSimilar(embedding: FloatArray, topK: Int): List<ContextNode> {
        ensureDatabase()
        val k = if (topK < 1) 5 else topK
        return try {
            val query = "CALL db.index.vector.queryNodes('ContextNode[embedding]', \$k, \$vec) YIELD node, score RETURN node, score ORDER BY score DESC"
            val result = mutableListOf<ContextNode>()
            engine.transaction { db ->
                val resultSet = db.query("cypher", query, mapOf("k" to k, "vec" to embedding.toList()))
                while (resultSet.hasNext()) {
                    val row = resultSet.next()
                    val vertex = row.getProperty<Vertex>("node")
                    if (vertex != null) {
                        result.add(vertexToNode(vertex))
                    }
                }
            }
            result
        } catch (_: Exception) {
            // Fallback: Cosine similarity calculation across scanned nodes with embeddings
            engine.transaction { db ->
                val allWithEmbeddings = mutableListOf<Pair<ContextNode, Float>>()
                db.scanType("ContextNode", true) { record ->
                    val vertex = record.asVertex()
                    val node = vertexToNode(vertex)
                    if (node.embedding != null && node.embedding.size == embedding.size) {
                        val sim = cosineSimilarity(embedding, node.embedding)
                        allWithEmbeddings.add(node to sim)
                    }
                    true
                }
                allWithEmbeddings.sortedByDescending { it.second }.take(k).map { it.first }
            }
        }
    }

    override suspend fun getBacklinks(nodeId: String): List<ContextNode> {
        ensureDatabase()
        val query = "MATCH (m:ContextNode)-[r]->(n:ContextNode {id: \$id}) RETURN DISTINCT m"
        return engine.transaction { db ->
            val resultSet = db.query("cypher", query, mapOf("id" to nodeId))
            val result = mutableListOf<ContextNode>()
            while (resultSet.hasNext()) {
                val row = resultSet.next()
                val vertex = row.getProperty<Vertex>("m")
                if (vertex != null) {
                    result.add(vertexToNode(vertex))
                }
            }
            result
        }
    }

    override suspend fun analyzeImpact(nodeId: String): List<ContextNode> {
        ensureDatabase()
        val query = "MATCH (n:ContextNode {id: \$id})<-[:IMPLEMENTS|MUTATES|REFERENCES*1..10]-(m:ContextNode) RETURN DISTINCT m"
        return engine.transaction { db ->
            val resultSet = db.query("cypher", query, mapOf("id" to nodeId))
            val result = mutableListOf<ContextNode>()
            while (resultSet.hasNext()) {
                val row = resultSet.next()
                val vertex = row.getProperty<Vertex>("m")
                if (vertex != null) {
                    result.add(vertexToNode(vertex))
                }
            }
            result
        }
    }

    private fun setNodeProperties(vertex: MutableVertex, node: ContextNode) {
        vertex.set("id", node.id)
        vertex.set("type", node.type.name)
        vertex.set("label", node.label)
        vertex.set("body", node.body)
        vertex.set("embedding", node.embedding)
        vertex.set("filePath", node.filePath)
        vertex.set("createdAt", node.createdAt)
        vertex.set("updatedAt", node.updatedAt)
    }

    private fun vertexToNode(vertex: Vertex): ContextNode {
        val typeString = vertex.getString("type")
        val type = if (typeString != null) {
            try {
                NodeType.valueOf(typeString)
            } catch (_: Exception) {
                NodeType.ENTITY
            }
        } else NodeType.ENTITY

        val rawEmbedding = vertex.get("embedding")
        val embedding: FloatArray? = when (rawEmbedding) {
            is FloatArray -> rawEmbedding
            is Array<*> -> FloatArray(rawEmbedding.size) { (rawEmbedding[it] as? Number)?.toFloat() ?: 0f }
            is List<*> -> FloatArray(rawEmbedding.size) { (rawEmbedding[it] as? Number)?.toFloat() ?: 0f }
            else -> null
        }

        return ContextNode(
            id = vertex.getString("id"),
            type = type,
            label = vertex.getString("label") ?: "",
            body = vertex.getString("body") ?: "",
            embedding = embedding,
            filePath = vertex.getString("filePath"),
            createdAt = vertex.getLong("createdAt") ?: 0L,
            updatedAt = vertex.getLong("updatedAt") ?: 0L,
        )
    }

    private fun edgeToContextEdge(edge: com.arcadedb.graph.Edge): ContextEdge? {
        val id = if (edge.has("id")) edge.getString("id") else null
        val createdAt = if (edge.has("createdAt")) edge.getLong("createdAt") ?: 0L else 0L
        val relation = if (edge.has("relation")) edge.getString("relation") ?: edge.typeName else edge.typeName
        val outVertex = edge.outVertex
        val inVertex = edge.inVertex
        val fromId = outVertex?.getString("id") ?: return null
        val toId = inVertex?.getString("id") ?: return null

        return ContextEdge(
            id = id ?: "${fromId}_${relation}_$toId",
            fromId = fromId,
            toId = toId,
            relation = relation,
            createdAt = createdAt,
        )
    }

    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        return if (normA > 0 && normB > 0) {
            (dot / (Math.sqrt(normA) * Math.sqrt(normB))).toFloat()
        } else 0f
    }
}
