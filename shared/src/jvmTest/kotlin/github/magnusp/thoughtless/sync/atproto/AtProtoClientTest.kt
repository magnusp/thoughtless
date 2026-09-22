package github.magnusp.thoughtless.sync.atproto

import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.util.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AtProtoClientTest {

    private val recordsDb = mutableMapOf<String, String>() // "collection/rkey" -> record json
    private lateinit var client: AtProtoClient

    @BeforeTest
    fun setup() {
        recordsDb.clear()

        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            val method = request.method
            val authHeader = request.headers[HttpHeaders.Authorization]

            val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

            when {
                // Auth: createSession
                path == "/xrpc/com.atproto.server.createSession" && method == HttpMethod.Post -> {
                    val bodyText = readBodyText(request.body)
                    val req = Json.decodeFromString<CreateSessionRequest>(bodyText)
                    if (req.password == "valid_password") {
                        val session = AtProtoSession(
                            did = "did:plc:testuser123456",
                            handle = req.identifier,
                            email = "test@example.com",
                            accessJwt = "jwt_access_token_123",
                            refreshJwt = "jwt_refresh_token_123",
                        )
                        respond(
                            content = Json.encodeToString(AtProtoSession.serializer(), session),
                            status = HttpStatusCode.OK,
                            headers = jsonHeaders,
                        )
                    } else {
                        respond(
                            content = """{"error": "AuthenticationFailed", "message": "Invalid identifier or password"}""",
                            status = HttpStatusCode.Unauthorized,
                            headers = jsonHeaders,
                        )
                    }
                }

                // Repo: createRecord
                path == "/xrpc/com.atproto.repo.createRecord" && method == HttpMethod.Post -> {
                    if (authHeader != "Bearer jwt_access_token_123") {
                        respond("""{"error":"AuthRequired","message":"Authentication required"}""", HttpStatusCode.Unauthorized, jsonHeaders)
                    } else {
                        val bodyText = readBodyText(request.body)
                        val jsonElement = Json.parseToJsonElement(bodyText).jsonObject
                        val collection = jsonElement["collection"]?.jsonPrimitive?.contentOrNull ?: ""
                        val rkey = jsonElement["rkey"]?.jsonPrimitive?.contentOrNull ?: "rkey_${recordsDb.size + 1}"
                        val recordObj = jsonElement["record"]?.jsonObject ?: JsonObject(emptyMap())

                        val recordKey = "$collection/$rkey"
                        recordsDb[recordKey] = recordObj.toString()

                        val resp = CreateRecordResponse(
                            uri = "at://did:plc:testuser123456/$collection/$rkey",
                            cid = "bafyreihash1234567890",
                        )
                        respond(
                            content = Json.encodeToString(CreateRecordResponse.serializer(), resp),
                            status = HttpStatusCode.OK,
                            headers = jsonHeaders,
                        )
                    }
                }

                // Repo: putRecord
                path == "/xrpc/com.atproto.repo.putRecord" && method == HttpMethod.Post -> {
                    if (authHeader != "Bearer jwt_access_token_123") {
                        respond("""{"error":"AuthRequired","message":"Authentication required"}""", HttpStatusCode.Unauthorized, jsonHeaders)
                    } else {
                        val bodyText = readBodyText(request.body)
                        val jsonElement = Json.parseToJsonElement(bodyText).jsonObject
                        val collection = jsonElement["collection"]?.jsonPrimitive?.contentOrNull ?: ""
                        val rkey = jsonElement["rkey"]?.jsonPrimitive?.contentOrNull ?: "rkey_put"
                        val recordObj = jsonElement["record"]?.jsonObject ?: JsonObject(emptyMap())

                        val recordKey = "$collection/$rkey"
                        recordsDb[recordKey] = recordObj.toString()

                        val resp = PutRecordResponse(
                            uri = "at://did:plc:testuser123456/$collection/$rkey",
                            cid = "bafyreihash1234567890",
                        )
                        respond(
                            content = Json.encodeToString(PutRecordResponse.serializer(), resp),
                            status = HttpStatusCode.OK,
                            headers = jsonHeaders,
                        )
                    }
                }

                // Repo: getRecord
                path == "/xrpc/com.atproto.repo.getRecord" && method == HttpMethod.Get -> {
                    val collection = request.url.parameters["collection"] ?: ""
                    val rkey = request.url.parameters["rkey"] ?: ""
                    val recordKey = "$collection/$rkey"
                    val recordJson = recordsDb[recordKey]

                    if (recordJson != null) {
                        val responseJson = """
                            {
                                "uri": "at://did:plc:testuser123456/$collection/$rkey",
                                "cid": "bafyreihash1234567890",
                                "value": $recordJson
                            }
                        """.trimIndent()
                        respond(responseJson, HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond("""{"error":"RecordNotFound","message":"Record not found: $recordKey"}""", HttpStatusCode.NotFound, jsonHeaders)
                    }
                }

                // Repo: listRecords
                path == "/xrpc/com.atproto.repo.listRecords" && method == HttpMethod.Get -> {
                    val collection = request.url.parameters["collection"] ?: ""
                    val matching = recordsDb.filterKeys { it.startsWith("$collection/") }
                    val items = matching.map { (key, valueJson) ->
                        val rkey = key.removePrefix("$collection/")
                        """{"uri":"at://did:plc:testuser123456/$collection/$rkey","cid":"bafyreihash1234567890","value":$valueJson}"""
                    }.joinToString(",")

                    val responseJson = """{"records": [$items]}"""
                    respond(responseJson, HttpStatusCode.OK, jsonHeaders)
                }

                // Repo: deleteRecord
                path == "/xrpc/com.atproto.repo.deleteRecord" && method == HttpMethod.Post -> {
                    if (authHeader != "Bearer jwt_access_token_123") {
                        respond("""{"error":"AuthRequired","message":"Authentication required"}""", HttpStatusCode.Unauthorized, jsonHeaders)
                    } else {
                        val bodyText = readBodyText(request.body)
                        val req = Json.decodeFromString<DeleteRecordRequest>(bodyText)
                        recordsDb.remove("${req.collection}/${req.rkey}")
                        respond("{}", HttpStatusCode.OK, jsonHeaders)
                    }
                }

                else -> respond("""{"error":"NotFound","message":"Endpoint not found"}""", HttpStatusCode.NotFound, jsonHeaders)
            }
        }

        val testHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(AtProtoClient.defaultJson)
            }
        }

        client = AtProtoClient(
            pdsUrl = "https://mock.pds.local",
            httpClient = testHttpClient,
        )
    }

    @Test
    fun testAuthenticationSuccess() = runTest {
        val session = client.createSession(identifier = "magnus.bsky.social", password = "valid_password")
        assertEquals("did:plc:testuser123456", session.did)
        assertEquals("magnus.bsky.social", session.handle)
        assertEquals("jwt_access_token_123", session.accessJwt)
        assertNotNull(client.currentSession)
    }

    @Test
    fun testAuthenticationFailure() = runTest {
        val ex = assertFailsWith<AtProtoException> {
            client.createSession(identifier = "magnus.bsky.social", password = "wrong_password")
        }
        assertEquals(401, ex.statusCode)
        assertEquals("AuthenticationFailed", ex.errorType)
    }

    @Test
    fun testCreateAndGetTaskRecordRoundTrip() = runTest {
        // Authenticate
        client.createSession("magnus.bsky.social", "valid_password")

        val originalTask = Task(
            id = "task-uuid-1",
            title = "Connect to ATProto PDS",
            description = "Spike 2 connectivity test",
            status = TaskStatus.IN_PROGRESS,
            priority = TaskPriority.HIGH,
            createdAt = currentTimeMillis(),
            updatedAt = currentTimeMillis(),
        )

        // Convert domain Task to ATProto record
        val record = originalTask.toAtProtoRecord()
        assertEquals("thoughtless.task", record.type)
        assertEquals("Connect to ATProto PDS", record.title)
        assertEquals("IN_PROGRESS", record.status)
        assertEquals(3, record.priority)

        // Write to ATProto PDS
        val createResult = client.createTaskRecord(record, rkey = "spike2-task")
        assertEquals("at://did:plc:testuser123456/thoughtless.task/spike2-task", createResult.uri)
        assertEquals("bafyreihash1234567890", createResult.cid)

        // Read record back from PDS
        val fetchResult = client.getTaskRecord(rkey = "spike2-task")
        assertEquals("at://did:plc:testuser123456/thoughtless.task/spike2-task", fetchResult.uri)
        assertEquals("Connect to ATProto PDS", fetchResult.value.title)
        assertEquals("Spike 2 connectivity test", fetchResult.value.description)
        assertEquals("IN_PROGRESS", fetchResult.value.status)
        assertEquals(3, fetchResult.value.priority)

        // Map retrieved record back to domain model
        val retrievedTask = fetchResult.value.toDomain(id = "spike2-task")
        assertEquals("Connect to ATProto PDS", retrievedTask.title)
        assertEquals(TaskStatus.IN_PROGRESS, retrievedTask.status)
        assertEquals(TaskPriority.HIGH, retrievedTask.priority)
    }

    @Test
    fun testPutAndListSpecAndContextRecords() = runTest {
        client.createSession("magnus.bsky.social", "valid_password")

        val specRecord = SpecRecord(
            projectId = "p1",
            title = "Spec 1",
            systemSpec = "Architecture definition",
            createdAt = "2026-09-23T00:00:00Z"
        )
        val putSpec = client.putSpecRecord("spec-1", specRecord)
        assertEquals("at://did:plc:testuser123456/thoughtless.spec/spec-1", putSpec.uri)

        val fetchedSpec = client.getSpecRecord("spec-1")
        assertEquals("Spec 1", fetchedSpec.value.title)

        val nodeRecord = ContextNodeRecord(
            nodeId = "spec-1#req1",
            nodeType = "REQUIREMENT",
            label = "Auth Requirement",
            body = "Must use OAuth",
            createdAt = "2026-09-23T00:00:00Z"
        )
        client.putContextNodeRecord("node-1", nodeRecord)

        val edgeRecord = ContextEdgeRecord(
            edgeId = "edge-1",
            fromId = "spec-1#req1",
            toId = "endpoint-auth",
            relation = "implements",
            createdAt = "2026-09-23T00:00:00Z"
        )
        client.putContextEdgeRecord("edge-1", edgeRecord)

        val agentTaskRecord = AgentTaskRecord(
            title = "Agent Task 1",
            agentStatus = "PENDING",
            createdAt = "2026-09-23T00:00:00Z"
        )
        client.putAgentTaskRecord("atask-1", agentTaskRecord)

        val nodesList = client.listContextNodeRecords()
        assertEquals(1, nodesList.records.size)
        assertEquals("Auth Requirement", nodesList.records.first().value.label)

        val edgesList = client.listContextEdgeRecords()
        assertEquals(1, edgesList.records.size)
        assertEquals("implements", edgesList.records.first().value.relation)

        val agentTasksList = client.listAgentTaskRecords()
        assertEquals(1, agentTasksList.records.size)
        assertEquals("Agent Task 1", agentTasksList.records.first().value.title)
    }

    @Test
    fun testListAndDeleteRecords() = runTest {
        client.createSession("magnus.bsky.social", "valid_password")

        val task1 = TaskRecord(title = "Task 1", createdAt = "2026-09-19T21:00:00Z")
        val task2 = TaskRecord(title = "Task 2", createdAt = "2026-09-19T22:00:00Z")

        client.createTaskRecord(task1, rkey = "t1")
        client.createTaskRecord(task2, rkey = "t2")

        val list = client.listTaskRecords()
        assertEquals(2, list.records.size)

        // Delete t1
        client.deleteTaskRecord(rkey = "t1")

        val listAfterDelete = client.listTaskRecords()
        assertEquals(1, listAfterDelete.records.size)
        assertEquals("Task 2", listAfterDelete.records.first().value.title)

        // Verify t1 is gone
        assertFailsWith<AtProtoException> {
            client.getTaskRecord(rkey = "t1")
        }
    }

    private fun readBodyText(content: io.ktor.http.content.OutgoingContent): String = when (content) {
        is io.ktor.http.content.OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        is io.ktor.http.content.OutgoingContent.NoContent -> ""
        else -> ""
    }
}
