package github.magnusp.thoughtless.sync.atproto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AtProtoSession(
    val did: String,
    val handle: String,
    val email: String? = null,
    val accessJwt: String,
    val refreshJwt: String,
)

@Serializable
data class CreateSessionRequest(
    val identifier: String,
    val password: String,
)

@Serializable
data class CreateRecordRequest<T>(
    val repo: String,
    val collection: String,
    val rkey: String? = null,
    val validate: Boolean = true,
    val record: T,
)

@Serializable
data class CreateRecordResponse(
    val uri: String,
    val cid: String,
    val commit: CommitMeta? = null,
)

@Serializable
data class CommitMeta(
    val cid: String,
    val rev: String? = null,
)

@Serializable
data class GetRecordResponse<T>(
    val uri: String,
    val cid: String,
    val value: T,
)

@Serializable
data class ListRecordsResponse<T>(
    val cursor: String? = null,
    val records: List<RecordItem<T>> = emptyList(),
)

@Serializable
data class RecordItem<T>(
    val uri: String,
    val cid: String,
    val value: T,
)

@Serializable
data class DeleteRecordRequest(
    val repo: String,
    val collection: String,
    val rkey: String,
)

@Serializable
data class TaskRecord(
    @SerialName("\$type")
    val type: String = "thoughtless.task",
    val title: String,
    val description: String? = null,
    val status: String = "TODO",
    val priority: Long = 0,
    val dueDate: Long? = null,
    val projectId: String? = null,
    val createdAt: String,
    val updatedAt: String? = null,
    val completedAt: String? = null,
)

@Serializable
data class ProjectRecord(
    @SerialName("\$type")
    val type: String = "thoughtless.project",
    val name: String,
    val description: String? = null,
    val color: String? = null,
    val createdAt: String,
    val updatedAt: String? = null,
)
