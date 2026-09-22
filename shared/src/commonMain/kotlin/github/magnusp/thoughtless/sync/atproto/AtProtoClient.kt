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

    suspend inline fun <reified T> putRecord(
        collection: String,
        rkey: String,
        record: T,
        validate: Boolean = true,
        repo: String? = null,
    ): PutRecordResponse {
        val session = currentSession ?: throw IllegalStateException("Not authenticated with ATProto PDS")
        val targetRepo = repo ?: session.did

        val request = PutRecordRequest(
            repo = targetRepo,
            collection = collection,
            rkey = rkey,
            validate = validate,
            record = record,
        )

        val response = httpClient.post("$pdsUrl/xrpc/com.atproto.repo.putRecord") {
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

        return response.body<PutRecordResponse>()
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

    suspend fun putTaskRecord(rkey: String, task: TaskRecord): PutRecordResponse =
        putRecord(collection = COLLECTION_TASK, rkey = rkey, record = task)

    suspend fun getTaskRecord(rkey: String, repo: String? = null): GetRecordResponse<TaskRecord> =
        getRecord(collection = COLLECTION_TASK, rkey = rkey, repo = repo)

    suspend fun listTaskRecords(limit: Int = 50, cursor: String? = null, repo: String? = null): ListRecordsResponse<TaskRecord> =
        listRecords(collection = COLLECTION_TASK, limit = limit, cursor = cursor, repo = repo)

    suspend fun deleteTaskRecord(rkey: String, repo: String? = null) =
        deleteRecord(collection = COLLECTION_TASK, rkey = rkey, repo = repo)

    // High-level Spec methods
    suspend fun putSpecRecord(rkey: String, spec: SpecRecord): PutRecordResponse =
        putRecord(collection = COLLECTION_SPEC, rkey = rkey, record = spec)

    suspend fun getSpecRecord(rkey: String, repo: String? = null): GetRecordResponse<SpecRecord> =
        getRecord(collection = COLLECTION_SPEC, rkey = rkey, repo = repo)

    suspend fun listSpecRecords(limit: Int = 50, cursor: String? = null, repo: String? = null): ListRecordsResponse<SpecRecord> =
        listRecords(collection = COLLECTION_SPEC, limit = limit, cursor = cursor, repo = repo)

    // High-level ContextNode methods
    suspend fun putContextNodeRecord(rkey: String, node: ContextNodeRecord): PutRecordResponse =
        putRecord(collection = COLLECTION_CONTEXT_NODE, rkey = rkey, record = node)

    suspend fun getContextNodeRecord(rkey: String, repo: String? = null): GetRecordResponse<ContextNodeRecord> =
        getRecord(collection = COLLECTION_CONTEXT_NODE, rkey = rkey, repo = repo)

    suspend fun listContextNodeRecords(limit: Int = 100, cursor: String? = null, repo: String? = null): ListRecordsResponse<ContextNodeRecord> =
        listRecords(collection = COLLECTION_CONTEXT_NODE, limit = limit, cursor = cursor, repo = repo)

    // High-level ContextEdge methods
    suspend fun putContextEdgeRecord(rkey: String, edge: ContextEdgeRecord): PutRecordResponse =
        putRecord(collection = COLLECTION_CONTEXT_EDGE, rkey = rkey, record = edge)

    suspend fun getContextEdgeRecord(rkey: String, repo: String? = null): GetRecordResponse<ContextEdgeRecord> =
        getRecord(collection = COLLECTION_CONTEXT_EDGE, rkey = rkey, repo = repo)

    suspend fun listContextEdgeRecords(limit: Int = 100, cursor: String? = null, repo: String? = null): ListRecordsResponse<ContextEdgeRecord> =
        listRecords(collection = COLLECTION_CONTEXT_EDGE, limit = limit, cursor = cursor, repo = repo)

    // High-level AgentTask methods
    suspend fun putAgentTaskRecord(rkey: String, task: AgentTaskRecord): PutRecordResponse =
        putRecord(collection = COLLECTION_AGENT_TASK, rkey = rkey, record = task)

    suspend fun getAgentTaskRecord(rkey: String, repo: String? = null): GetRecordResponse<AgentTaskRecord> =
        getRecord(collection = COLLECTION_AGENT_TASK, rkey = rkey, repo = repo)

    suspend fun listAgentTaskRecords(limit: Int = 50, cursor: String? = null, repo: String? = null): ListRecordsResponse<AgentTaskRecord> =
        listRecords(collection = COLLECTION_AGENT_TASK, limit = limit, cursor = cursor, repo = repo)

    companion object {
        const val COLLECTION_TASK = "thoughtless.task"
        const val COLLECTION_PROJECT = "thoughtless.project"
        const val COLLECTION_SPEC = "thoughtless.spec"
        const val COLLECTION_CONTEXT_NODE = "thoughtless.contextNode"
        const val COLLECTION_CONTEXT_EDGE = "thoughtless.contextEdge"
        const val COLLECTION_AGENT_TASK = "thoughtless.agentTask"
        const val COLLECTION_MILESTONE = "thoughtless.milestone"

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
