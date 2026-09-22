package github.magnusp.thoughtless.domain.model

data class Spec(
    val id: String,
    val projectId: String,
    val title: String,
    val systemSpec: String,
    val nonGoals: String? = null,
    val rfcDocument: String? = null,
    val frozenAt: Long? = null,
)
