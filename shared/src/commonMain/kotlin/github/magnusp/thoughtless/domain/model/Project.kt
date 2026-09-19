package github.magnusp.thoughtless.domain.model

data class Project(
    val id: String,
    val name: String,
    val description: String? = null,
    val color: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
