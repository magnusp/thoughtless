package github.magnusp.thoughtless.sync.atproto

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AtProtoError(
    val error: String? = null,
    val message: String? = null,
)

class AtProtoException(
    val statusCode: Int,
    val errorType: String?,
    override val message: String,
) : RuntimeException("ATProto error ($statusCode - ${errorType ?: "Unknown"}): $message")

class AtProtoClient(
    val pdsUrl: String = "https://bsky.social",
    val httpClient: HttpClient = createDefaultHttpClient(),
) {
    var currentSession: AtProtoSession? = null
        private set

    fun setSession(session: AtProtoSession) {
        currentSession = session
    }

    suspend fun createSession(identifier: String, password: String): AtProtoSession {
        val response = httpClient.post("$pdsUrl/xrpc/com.atproto.server.createSession") {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(identifier = identifier, password = password))
        }

        if (!response.status.isSuccess()) {
            val text = response.bodyAsText()
            val parsedError = runCatching { defaultJson.decodeFromString<AtProtoError>(text) }.getOrNull()
            throw AtProtoException(
                statusCode = response.status.value,
                errorType = parsedError?.error,
                message = parsedError?.message ?: text,
            )
        }

        val session = response.body<AtProtoSession>()
        currentSession = session
        return session
    }

    suspend inline fun <reified T> createRecord(
        collection: String,
        record: T,
        rkey: String? = null,
        validate: Boolean = true,
        repo: String? = null,
    ): CreateRecordResponse {
        val session = currentSession ?: throw IllegalStateException("Not authenticated with ATProto PDS")
        val targetRepo = repo ?: session.did

        val request = CreateRecordRequest(
            repo = targetRepo,
            collection = collection,
            rkey = rkey,
            validate = validate,
            record = record,
        )

        val response = httpClient.post("$pdsUrl/xrpc/com.atproto.repo.createRecord") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${session.accessJwt}")
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            val text = response.bodyAsText()
            val parsedError = runCatching { defaultJson.decodeFromString<AtProtoError>(text) }.getOrNull()
            throw AtProtoException(
                statusCode = response.status.value,
                errorType = parsedError?.error,
                message = parsedError?.message ?: text,
            )
        }

        return response.body<CreateRecordResponse>()
    }

    suspend inline fun <reified T> getRecord(
        collection: String,
        rkey: String,
        repo: String? = null,
    ): GetRecordResponse<T> {
        val targetRepo = repo ?: currentSession?.did
            ?: throw IllegalStateException("Repository DID must be provided or session active")

        val response = httpClient.get("$pdsUrl/xrpc/com.atproto.repo.getRecord") {
            parameter("repo", targetRepo)
            parameter("collection", collection)
            parameter("rkey", rkey)
            currentSession?.accessJwt?.let {
                header(HttpHeaders.Authorization, "Bearer $it")
            }
        }

        if (!response.status.isSuccess()) {
            val text = response.bodyAsText()
            val parsedError = runCatching { defaultJson.decodeFromString<AtProtoError>(text) }.getOrNull()
            throw AtProtoException(
                statusCode = response.status.value,
                errorType = parsedError?.error,
                message = parsedError?.message ?: text,
            )
        }

        return response.body<GetRecordResponse<T>>()
    }

    suspend inline fun <reified T> listRecords(
        collection: String,
        limit: Int = 50,
        cursor: String? = null,
        repo: String? = null,
    ): ListRecordsResponse<T> {
        val targetRepo = repo ?: currentSession?.did
            ?: throw IllegalStateException("Repository DID must be provided or session active")

        val response = httpClient.get("$pdsUrl/xrpc/com.atproto.repo.listRecords") {
            parameter("repo", targetRepo)
            parameter("collection", collection)
            parameter("limit", limit)
            cursor?.let { parameter("cursor", it) }
            currentSession?.accessJwt?.let {
                header(HttpHeaders.Authorization, "Bearer $it")
            }
        }

        if (!response.status.isSuccess()) {
            val text = response.bodyAsText()
            val parsedError = runCatching { defaultJson.decodeFromString<AtProtoError>(text) }.getOrNull()
            throw AtProtoException(
                statusCode = response.status.value,
                errorType = parsedError?.error,
                message = parsedError?.message ?: text,
            )
        }

        return response.body<ListRecordsResponse<T>>()
    }

    suspend fun deleteRecord(
        collection: String,
        rkey: String,
        repo: String? = null,
    ) {
        val session = currentSession ?: throw IllegalStateException("Not authenticated with ATProto PDS")
        val targetRepo = repo ?: session.did

        val request = DeleteRecordRequest(
            repo = targetRepo,
            collection = collection,
            rkey = rkey,
        )

        val response = httpClient.post("$pdsUrl/xrpc/com.atproto.repo.deleteRecord") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${session.accessJwt}")
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            val text = response.bodyAsText()
            val parsedError = runCatching { defaultJson.decodeFromString<AtProtoError>(text) }.getOrNull()
            throw AtProtoException(
                statusCode = response.status.value,
                errorType = parsedError?.error,
                message = parsedError?.message ?: text,
            )
        }
    }

    // High-level Task methods
    suspend fun createTaskRecord(task: TaskRecord, rkey: String? = null): CreateRecordResponse =
        createRecord(collection = COLLECTION_TASK, record = task, rkey = rkey)

    suspend fun getTaskRecord(rkey: String, repo: String? = null): GetRecordResponse<TaskRecord> =
        getRecord(collection = COLLECTION_TASK, rkey = rkey, repo = repo)

    suspend fun listTaskRecords(limit: Int = 50, cursor: String? = null, repo: String? = null): ListRecordsResponse<TaskRecord> =
        listRecords(collection = COLLECTION_TASK, limit = limit, cursor = cursor, repo = repo)

    suspend fun deleteTaskRecord(rkey: String, repo: String? = null) =
        deleteRecord(collection = COLLECTION_TASK, rkey = rkey, repo = repo)

    companion object {
        const val COLLECTION_TASK = "thoughtless.task"
        const val COLLECTION_PROJECT = "thoughtless.project"

        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }

        fun createDefaultHttpClient(): HttpClient = HttpClient {
            install(ContentNegotiation) {
                json(defaultJson)
            }
        }
    }
}
