package github.magnusp.thoughtless.sync

import github.magnusp.thoughtless.data.arcadedb.ArcadeDBContextGraphRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBEngine
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBSpecRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBTaskRepository
import github.magnusp.thoughtless.domain.model.AgentTaskStatus
import github.magnusp.thoughtless.domain.model.ContextEdge
import github.magnusp.thoughtless.domain.model.ContextNode
import github.magnusp.thoughtless.domain.model.NodeType
import github.magnusp.thoughtless.domain.model.Spec
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.sync.atproto.AtProtoClient
import github.magnusp.thoughtless.sync.atproto.AtProtoSession
import github.magnusp.thoughtless.sync.atproto.CreateSessionRequest
import github.magnusp.thoughtless.sync.atproto.DeleteRecordRequest
import github.magnusp.thoughtless.sync.atproto.PutRecordResponse
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GraphSyncServiceTest {

    private lateinit var tempDir: File
    private lateinit var engine: ArcadeDBEngine
    private lateinit var graphRepo: ArcadeDBContextGraphRepository
    private lateinit var specRepo: ArcadeDBSpecRepository
    private lateinit var taskRepo: ArcadeDBTaskRepository
    private lateinit var syncService: GraphSyncService
    private val recordsDb = mutableMapOf<String, String>() // "repo/collection/rkey" -> record json

    @BeforeTest
    fun setup() {
        tempDir = java.nio.file.Files.createTempDirectory("thoughtless-graphsync-test-").toFile()
        engine = ArcadeDBEngine(tempDir.absolutePath)
        engine.open()
        graphRepo = ArcadeDBContextGraphRepository(engine)
        specRepo = ArcadeDBSpecRepository(engine)
        taskRepo = ArcadeDBTaskRepository(engine)
        recordsDb.clear()

        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            val method = request.method
            val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

            when {
                // Auth: createSession
                path == "/xrpc/com.atproto.server.createSession" && method == HttpMethod.Post -> {
                    val session = AtProtoSession(
                        did = "did:plc:localuser123",
                        handle = "local.user",
                        accessJwt = "jwt_access_token_123",
                        refreshJwt = "jwt_refresh_token_123",
                    )
                    respond(Json.encodeToString(AtProtoSession.serializer(), session), HttpStatusCode.OK, jsonHeaders)
                }

                // Repo: putRecord
                path == "/xrpc/com.atproto.repo.putRecord" && method == HttpMethod.Post -> {
                    val bodyBytes = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()
                    val jsonElement = Json.parseToJsonElement(bodyBytes.decodeToString()).jsonObject
                    val repo = jsonElement["repo"]?.jsonPrimitive?.contentOrNull ?: "did:plc:localuser123"
                    val collection = jsonElement["collection"]?.jsonPrimitive?.contentOrNull ?: ""
                    val rkey = jsonElement["rkey"]?.jsonPrimitive?.contentOrNull ?: "rkey"
                    val recordObj = jsonElement["record"]?.jsonObject ?: JsonObject(emptyMap())

                    recordsDb["$repo/$collection/$rkey"] = recordObj.toString()
                    val resp = PutRecordResponse(uri = "at://$repo/$collection/$rkey", cid = "cid123")
                    respond(Json.encodeToString(PutRecordResponse.serializer(), resp), HttpStatusCode.OK, jsonHeaders)
                }

                // Repo: listRecords
                path == "/xrpc/com.atproto.repo.listRecords" && method == HttpMethod.Get -> {
                    val repo = request.url.parameters["repo"] ?: ""
                    val collection = request.url.parameters["collection"] ?: ""
                    val prefix = "$repo/$collection/"
                    val matching = recordsDb.filterKeys { it.startsWith(prefix) }
                    val items = matching.map { (key, valueJson) ->
                        val rkey = key.removePrefix(prefix)
                        """{"uri":"at://$repo/$collection/$rkey","cid":"cid123","value":$valueJson}"""
                    }.joinToString(",")

                    respond("""{"records": [$items]}""", HttpStatusCode.OK, jsonHeaders)
                }

                else -> respond("""{"error":"NotFound"}""", HttpStatusCode.NotFound, jsonHeaders)
            }
        }

        val testHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(AtProtoClient.defaultJson)
            }
        }

        val atProtoClient = AtProtoClient(pdsUrl = "https://mock.pds.local", httpClient = testHttpClient)
        atProtoClient.setSession(
            AtProtoSession(
                did = "did:plc:localuser123",
                handle = "local.user",
                accessJwt = "jwt_access_token_123",
                refreshJwt = "jwt_refresh_token_123",
            )
        )

        syncService = GraphSyncService(atProtoClient, graphRepo, specRepo, taskRepo)
    }

    @AfterTest
    fun tearDown() {
        engine.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun testPushLocalGraphToPds() = runTest {
        val now = currentTimeMillis()

        // 1. Seed local ArcadeDB
        val spec = specRepo.saveSpec(
            Spec(
                id = "spec-sync-1",
                projectId = "p1",
                title = "Federation Spec",
                systemSpec = "ATProto specs federated across peers",
                frozenAt = now,
            )
        )

        val node = graphRepo.saveNode(
            ContextNode(
                id = "spec-sync-1#fed1",
                type = NodeType.REQUIREMENT,
                label = "P2P Sync",
                body = "Peer synchronization over ATProto XRPC",
                createdAt = now,
                updatedAt = now,
            )
        )

        val edge = graphRepo.saveEdge(
            ContextEdge(
                id = "edge-sync-1",
                fromId = "spec-sync-1#fed1",
                toId = "target-node",
                relation = "references",
                createdAt = now,
            )
        )

        val task = taskRepo.createTask(
            title = "Agent sync task",
            description = "Run sync background daemon",
            priority = TaskPriority.HIGH,
        )

        // 2. Push to PDS
        val result = syncService.pushLocalGraphToPds()
        assertEquals(1, result.pushedSpecs)
        assertEquals(2, result.pushedNodes) // spec-sync-1#fed1 and placeholder target-node
        assertEquals(1, result.pushedEdges)
        assertEquals(1, result.pushedTasks)

        // 3. Verify records stored in PDS
        assertNotNull(recordsDb["did:plc:localuser123/thoughtless.spec/spec-sync-1"])
        assertNotNull(recordsDb["did:plc:localuser123/thoughtless.contextNode/spec-sync-1~fed1"])
        assertNotNull(recordsDb["did:plc:localuser123/thoughtless.contextEdge/edge-sync-1"])
    }

    @Test
    fun testPullPeerGraphWithLastWriteWins() = runTest {
        val peerDid = "did:plc:peer456"

        // Setup remote peer records directly in PDS
        recordsDb["$peerDid/thoughtless.spec/spec-peer-1"] = """
            {
                "${'$'}type": "thoughtless.spec",
                "projectId": "peer-proj",
                "title": "Peer Federated Spec",
                "systemSpec": "Peer remote architecture spec",
                "createdAt": "2026-09-23T00:00:00Z"
            }
        """.trimIndent()

        recordsDb["$peerDid/thoughtless.contextNode/peer-node-1"] = """
            {
                "${'$'}type": "thoughtless.contextNode",
                "nodeId": "peer-doc#sec1",
                "nodeType": "REQUIREMENT",
                "label": "Peer Requirement",
                "body": "Remote requirement body",
                "createdAt": "2026-09-23T00:00:00Z"
            }
        """.trimIndent()

        recordsDb["$peerDid/thoughtless.contextEdge/peer-edge-1"] = """
            {
                "${'$'}type": "thoughtless.contextEdge",
                "edgeId": "edge-peer-1",
                "fromId": "peer-doc#sec1",
                "toId": "another-node",
                "relation": "implements",
                "createdAt": "2026-09-23T00:00:00Z"
            }
        """.trimIndent()

        recordsDb["$peerDid/thoughtless.agentTask/peer-task-1"] = """
            {
                "${'$'}type": "thoughtless.agentTask",
                "title": "Peer Agent Task",
                "description": "Task created by peer",
                "agentStatus": "AWAITING_REVIEW",
                "dependsOn": [],
                "contextFiles": [],
                "acceptanceCriteria": [],
                "createdAt": "2026-09-23T00:00:00Z"
            }
        """.trimIndent()

        // Pull peer records into local database
        val syncResult = syncService.pullPeerGraph(peerDid)
        assertEquals(1, syncResult.pulledSpecs)
        assertEquals(1, syncResult.pulledNodes)
        assertEquals(1, syncResult.pulledEdges)
        assertEquals(1, syncResult.pulledTasks)

        // Verify locally merged entities in ArcadeDB
        val localSpec = specRepo.getSpecById("spec-peer-1").first()
        assertNotNull(localSpec)
        assertEquals("Peer Federated Spec", localSpec.title)

        val localNode = graphRepo.getNodeById("peer-doc#sec1").first()
        assertNotNull(localNode)
        assertEquals("Peer Requirement", localNode.label)

        val localEdges = graphRepo.getEdges().first()
        assertEquals(1, localEdges.size)
        assertEquals("implements", localEdges.first().relation)

        val localTasks = taskRepo.getTasks().first()
        assertEquals(1, localTasks.size)
        assertEquals("Peer Agent Task", localTasks.first().title)
    }
}
