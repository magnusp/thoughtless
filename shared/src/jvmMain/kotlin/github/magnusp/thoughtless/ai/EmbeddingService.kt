package github.magnusp.thoughtless.ai

import github.magnusp.thoughtless.domain.model.ContextNode
import github.magnusp.thoughtless.domain.repository.ContextGraphRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service responsible for computing embeddings for [ContextNode] instances
 * and updating the graph repository asynchronously with graceful offline fallback.
 */
class EmbeddingService(
    private val ollamaClient: OllamaClient,
    private val graphRepository: ContextGraphRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Attempts to compute and persist embedding for a single [node].
     * If Ollama is offline or unavailable, returns the node unmodified and logs/fails gracefully.
     */
    suspend fun embedNode(node: ContextNode): ContextNode = withContext(dispatcher) {
        val textToEmbed = buildEmbeddingPrompt(node)
        val embedding = ollamaClient.generateEmbedding(textToEmbed)

        if (embedding != null) {
            val updated = node.copy(
                embedding = embedding,
                updatedAt = node.updatedAt,
            )
            graphRepository.saveNode(updated)
            updated
        } else {
            // Graceful offline fallback: keep node without embedding
            node
        }
    }

    /**
     * Processes a batch of [nodes], embedding any node that lacks an embedding.
     */
    suspend fun embedMissing(nodes: List<ContextNode>): List<ContextNode> = withContext(dispatcher) {
        val results = mutableListOf<ContextNode>()
        for (node in nodes) {
            if (node.embedding == null) {
                results.add(embedNode(node))
            } else {
                results.add(node)
            }
        }
        results
    }

    private fun buildEmbeddingPrompt(node: ContextNode): String {
        return buildString {
            append("Type: ${node.type}\n")
            append("Title: ${node.label}\n")
            if (node.body.isNotBlank()) {
                append("Content:\n${node.body}\n")
            }
        }.trim()
    }
}
