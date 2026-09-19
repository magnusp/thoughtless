package github.magnusp.thoughtless.domain.model

data class Label(
    val id: String,
    val name: String,
    val color: String? = null,
)
