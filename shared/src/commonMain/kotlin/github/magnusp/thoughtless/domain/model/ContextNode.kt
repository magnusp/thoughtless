package github.magnusp.thoughtless.domain.model

enum class NodeType {
    REQUIREMENT,
    ENTITY,
    ENDPOINT,
    TABLE,
    SPEC,
    EXPLORATION,
}

data class ContextNode(
    val id: String,
    val type: NodeType,
    val label: String,
    val body: String,
    val embedding: FloatArray? = null,
    val filePath: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContextNode) return false

        if (id != other.id) return false
        if (type != other.type) return false
        if (label != other.label) return false
        if (body != other.body) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false
        if (filePath != other.filePath) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + (filePath?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
