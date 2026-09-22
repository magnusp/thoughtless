package github.magnusp.thoughtless.domain.repository

import github.magnusp.thoughtless.domain.model.ContextEdge
import github.magnusp.thoughtless.domain.model.ContextNode
import kotlinx.coroutines.flow.Flow

interface ContextGraphRepository {
    fun getNodes(): Flow<List<ContextNode>>
    fun getNodeById(id: String): Flow<ContextNode?>
    suspend fun saveNode(node: ContextNode): ContextNode
    suspend fun deleteNode(id: String)

    fun getEdges(): Flow<List<ContextEdge>>
    fun getEdgesForNode(nodeId: String): Flow<List<ContextEdge>>
    suspend fun saveEdge(edge: ContextEdge): ContextEdge
    suspend fun deleteEdge(id: String)

    suspend fun findRelated(nodeId: String, hops: Int = 1): List<ContextNode>
    suspend fun findSimilar(embedding: FloatArray, topK: Int = 5): List<ContextNode>
    suspend fun getBacklinks(nodeId: String): List<ContextNode>
    suspend fun analyzeImpact(nodeId: String): List<ContextNode>
}
