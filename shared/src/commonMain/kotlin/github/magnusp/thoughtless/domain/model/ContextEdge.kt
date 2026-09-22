package github.magnusp.thoughtless.domain.model

data class ContextEdge(
    val id: String,
    val fromId: String,
    val toId: String,
    val relation: String,
    val createdAt: Long,
)
