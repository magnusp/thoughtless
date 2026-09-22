package github.magnusp.thoughtless.domain.model

data class Milestone(
    val id: String,
    val name: String,
    val version: String,
    val description: String? = null,
    val projectId: String,
    val createdAt: Long,
)
