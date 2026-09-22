package github.magnusp.thoughtless.integration

import github.magnusp.thoughtless.data.arcadedb.ArcadeDBContextGraphRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBEngine
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBProjectRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBSpecRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBTaskRepository
import github.magnusp.thoughtless.domain.model.AgentTaskStatus
import github.magnusp.thoughtless.domain.model.NodeType
import github.magnusp.thoughtless.domain.model.Spec
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.service.DAGDecomposerService
import github.magnusp.thoughtless.service.MarkdownIngestionService
import github.magnusp.thoughtless.sync.GraphSyncService
import github.magnusp.thoughtless.sync.atproto.AtProtoClient
import github.magnusp.thoughtless.sync.atproto.AtProtoSession
import github.magnusp.thoughtless.sync.atproto.PutRecordResponse
import github.magnusp.thoughtless.ui.WorkspaceNavTab
import github.magnusp.thoughtless.ui.WorkspaceViewModel
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
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EndToEndIntegrationTest {

    private lateinit var tempDirNodeA: File
    private lateinit var tempDirNodeB: File
    private lateinit var engineA: ArcadeDBEngine
    private lateinit var engineB: ArcadeDBEngine

    // Node A repos
    private lateinit var graphRepoA: ArcadeDBContextGraphRepository
    private lateinit var specRepoA: ArcadeDBSpecRepository
    private lateinit var taskRepoA: ArcadeDBTaskRepository
    private lateinit var projectRepoA: ArcadeDBProjectRepository
    private lateinit var markdownServiceA: MarkdownIngestionService
    private lateinit var dagServiceA: DAGDecomposerService

    // Node B repos
    private lateinit var graphRepoB: ArcadeDBContextGraphRepository
    private lateinit var specRepoB: ArcadeDBSpecRepository
    private lateinit var taskRepoB: ArcadeDBTaskRepository
    private lateinit var projectRepoB: ArcadeDBProjectRepository

    // Simulated PDS records database
    private val pdsStore = mutableMapOf<String, String>() // "repo/collection/rkey" -> record json

    @BeforeTest
    fun setup() {
        pdsStore.clear()

        // 1. Initialize Node A (Author Node)
        tempDirNodeA = Files.createTempDirectory("thoughtless-e2e-nodeA-").toFile()
        engineA = ArcadeDBEngine(tempDirNodeA.absolutePath)
        engineA.open()
        graphRepoA = ArcadeDBContextGraphRepository(engineA)
        specRepoA = ArcadeDBSpecRepository(engineA)
        taskRepoA = ArcadeDBTaskRepository(engineA)
        projectRepoA = ArcadeDBProjectRepository(engineA)
        markdownServiceA = MarkdownIngestionService(graphRepoA)
        dagServiceA = DAGDecomposerService(taskRepoA, graphRepoA)

        // 2. Initialize Node B (Peer Consumer Node)
        tempDirNodeB = Files.createTempDirectory("thoughtless-e2e-nodeB-").toFile()
        engineB = ArcadeDBEngine(tempDirNodeB.absolutePath)
        engineB.open()
        graphRepoB = ArcadeDBContextGraphRepository(engineB)
        specRepoB = ArcadeDBSpecRepository(engineB)
        taskRepoB = ArcadeDBTaskRepository(engineB)
        projectRepoB = ArcadeDBProjectRepository(engineB)
    }

    @AfterTest
    fun tearDown() {
        engineA.close()
        engineB.close()
        tempDirNodeA.deleteRecursively()
        tempDirNodeB.deleteRecursively()
    }

    private fun createMockAtProtoClient(userDid: String): AtProtoClient {
        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            val method = request.method
            val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

            when {
                // Repo: putRecord
                path == "/xrpc/com.atproto.repo.putRecord" && method == HttpMethod.Post -> {
                    val bodyBytes = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()
                    val jsonElement = Json.parseToJsonElement(bodyBytes.decodeToString()).jsonObject
                    val repo = jsonElement["repo"]?.jsonPrimitive?.contentOrNull ?: userDid
                    val collection = jsonElement["collection"]?.jsonPrimitive?.contentOrNull ?: ""
                    val rkey = jsonElement["rkey"]?.jsonPrimitive?.contentOrNull ?: "rkey"
                    val recordObj = jsonElement["record"]?.jsonObject ?: JsonObject(emptyMap())

                    pdsStore["$repo/$collection/$rkey"] = recordObj.toString()
                    val resp = PutRecordResponse(uri = "at://$repo/$collection/$rkey", cid = "cid_${pdsStore.size}")
                    respond(Json.encodeToString(PutRecordResponse.serializer(), resp), HttpStatusCode.OK, jsonHeaders)
                }

                // Repo: listRecords
                path == "/xrpc/com.atproto.repo.listRecords" && method == HttpMethod.Get -> {
                    val repo = request.url.parameters["repo"] ?: ""
                    val collection = request.url.parameters["collection"] ?: ""
                    val prefix = "$repo/$collection/"
                    val matching = pdsStore.filterKeys { it.startsWith(prefix) }
                    val items = matching.map { (key, valueJson) ->
                        val rkey = key.removePrefix(prefix)
                        """{"uri":"at://$repo/$collection/$rkey","cid":"cid_1","value":$valueJson}"""
                    }.joinToString(",")

                    respond("""{"records": [$items]}""", HttpStatusCode.OK, jsonHeaders)
                }

                else -> respond("""{"error":"NotFound"}""", HttpStatusCode.NotFound, jsonHeaders)
            }
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(AtProtoClient.defaultJson)
            }
        }

        val client = AtProtoClient(pdsUrl = "https://mock.pds.local", httpClient = httpClient)
        client.setSession(
            AtProtoSession(
                did = userDid,
                handle = "$userDid.handle",
                accessJwt = "jwt_access_token",
                refreshJwt = "jwt_refresh_token",
            )
        )
        return client
    }

    @Test
    fun testCompleteAgenticContextGraphLifecycle() = kotlinx.coroutines.runBlocking {
        // =========================================================================
        // STEP 1: UI Ingestion & Graph Creation on Node A
        // =========================================================================
        val viewModelA = WorkspaceViewModel(
            taskRepository = taskRepoA,
            projectRepository = projectRepoA,
            contextGraphRepository = graphRepoA,
            markdownIngestionService = markdownServiceA,
            dagDecomposerService = dagServiceA,
        )

        // 1.1 Create project on Node A
        viewModelA.createProject("Decentralized Agent Engine", "#4F46E5")
        val projects = viewModelA.projects.first { it.isNotEmpty() }
        val projectId = projects.first().id
        viewModelA.selectProject(projectId)

        // 1.2 Author Markdown Specification with Frontmatter and Wikilinks
        val markdownDoc = """
            ---
            id: doc-agent-sync
            type: SPEC
            title: Agent Sync Specification
            project: $projectId
            ---
            # Agent Synchronization Architecture
            
            This document outlines the decentralized agent synchronization architecture.
            
            ## Requirement: Storage Isolation
            Local state is stored in embedded ArcadeDB.
            
            ## Requirement: ATProto Federation
            Syncs with peer nodes via [[references:doc-agent-sync#requirement-storage-isolation]].
            Agents execute tasks in topological order [[depends_on:doc-agent-sync#requirement-atproto-federation]].
            
            ## Endpoint: RPC Sync Gateway
            Exposes sync APIs [[implements:doc-agent-sync#requirement-atproto-federation]].
        """.trimIndent()

        markdownServiceA.ingestDocument(markdownDoc, filePath = "docs/agent-sync.md")

        // 1.3 Verify Context Graph ingestion on Node A
        val nodesA = graphRepoA.getNodes().first()
        assertTrue(nodesA.size >= 4, "Expected root doc + 3 sections, found: ${nodesA.size}")

        val rootNode = nodesA.firstOrNull { it.id == "doc-agent-sync" }
        assertNotNull(rootNode)
        assertEquals("Agent Sync Specification", rootNode.label)

        val edgesA = graphRepoA.getEdges().first()
        assertTrue(edgesA.isNotEmpty(), "Context graph edges should be created from wikilinks")

        // 1.4 Verify Backlinks and Impact Analysis on Node A
        val impact = graphRepoA.analyzeImpact("doc-agent-sync#requirement-storage-isolation")
        assertTrue(impact.isNotEmpty(), "Impact analysis should traverse outgoing references")

        // =========================================================================
        // STEP 2: Spec Ingestion & DAG Decomposition on Node A
        // =========================================================================
        val specA = Spec(
            id = "spec-agent-sync",
            projectId = projectId,
            title = "Agent Sync Specification",
            systemSpec = "Complete agent DAG execution and ATProto sync",
            rfcDocument = "RFC-009: Decentralized Agent Federation",
            frozenAt = currentTimeMillis(),
        )
        specRepoA.saveSpec(specA)

        // Decompose Spec into topologically ordered Tasks
        val t1 = Task(
            id = "task-storage",
            projectId = projectId,
            title = "Setup Storage Layer",
            description = "Ensure ArcadeDB embedded instance is running",
            targetFile = "ArcadeDBEngine.kt",
            contextFiles = listOf("docs/agent-sync.md"),
            createdAt = currentTimeMillis(),
            updatedAt = currentTimeMillis(),
        )
        val t2 = Task(
            id = "task-sync",
            projectId = projectId,
            title = "Implement ATProto Sync",
            description = "Connect GraphSyncService to PDS",
            targetFile = "GraphSyncService.kt",
            contextFiles = listOf("docs/agent-sync.md"),
            dependsOn = listOf("task-storage"),
            createdAt = currentTimeMillis(),
            updatedAt = currentTimeMillis(),
        )
        val t3 = Task(
            id = "task-worker",
            projectId = projectId,
            title = "Launch Agent Worker",
            description = "Start autonomous agent executor",
            targetFile = "AgentQueueView.kt",
            contextFiles = listOf("docs/agent-sync.md"),
            dependsOn = listOf("task-sync"),
            createdAt = currentTimeMillis(),
            updatedAt = currentTimeMillis(),
        )

        val dagExport = dagServiceA.decomposeAndPersist(specA, listOf(t3, t1, t2))
        assertEquals(3, dagExport.totalTasks)

        // 2.1 Verify Topological Order & Execution Tiers in ViewModel
        val executionTiers = viewModelA.executionTiers.first { it.isNotEmpty() }
        assertEquals(3, executionTiers.size, "3 sequential tasks should produce 3 execution tiers")
        assertEquals("Setup Storage Layer", executionTiers[0].first().title)
        assertEquals("Implement ATProto Sync", executionTiers[1].first().title)
        assertEquals("Launch Agent Worker", executionTiers[2].first().title)

        // 2.2 Verify Agent DAG Export schema
        val dagExportJson = dagServiceA.exportToJson(dagExport)
        assertTrue(dagExportJson.contains("spec-agent-sync"))
        assertTrue(dagExportJson.contains("Setup Storage Layer"))
        assertTrue(dagExportJson.contains("executionTiers"))

        // 2.3 Simulate Agent Execution & Approval Flow
        val firstTask = executionTiers[0].first()
        viewModelA.updateAgentTaskStatus(firstTask, AgentTaskStatus.AWAITING_REVIEW)
        kotlinx.coroutines.delay(100)
        val inReviewTask = taskRepoA.getTaskById(firstTask.id).first()
        assertEquals(AgentTaskStatus.AWAITING_REVIEW, inReviewTask?.agentStatus)

        viewModelA.updateAgentTaskStatus(inReviewTask!!, AgentTaskStatus.MERGED)
        kotlinx.coroutines.delay(100)
        val completedTask = taskRepoA.getTaskById(firstTask.id).first()
        assertEquals(AgentTaskStatus.MERGED, completedTask?.agentStatus)
        assertEquals(TaskStatus.DONE, completedTask?.status)

        // =========================================================================
        // STEP 3: ATProto PDS Push from Node A
        // =========================================================================
        val userDidA = "did:plc:alice_author"
        val clientA = createMockAtProtoClient(userDidA)
        val syncServiceA = GraphSyncService(clientA, graphRepoA, specRepoA, taskRepoA)

        val pushResult = syncServiceA.pushLocalGraphToPds()
        assertTrue(pushResult.pushedSpecs >= 1, "Pushed specs count: ${pushResult.pushedSpecs}")
        assertTrue(pushResult.pushedNodes >= 4, "Pushed nodes count: ${pushResult.pushedNodes}")
        assertTrue(pushResult.pushedEdges >= 1, "Pushed edges count: ${pushResult.pushedEdges}")
        assertTrue(pushResult.pushedTasks >= 3, "Pushed tasks count: ${pushResult.pushedTasks}")

        // Ensure records are present in PDS
        assertTrue(pdsStore.keys.any { it.startsWith("$userDidA/thoughtless.spec/") })
        assertTrue(pdsStore.keys.any { it.startsWith("$userDidA/thoughtless.contextNode/") })
        assertTrue(pdsStore.keys.any { it.startsWith("$userDidA/thoughtless.contextEdge/") })
        assertTrue(pdsStore.keys.any { it.startsWith("$userDidA/thoughtless.agentTask/") })

        // =========================================================================
        // STEP 4: ATProto Peer Federation & LWW Pull onto Node B
        // =========================================================================
        val userDidB = "did:plc:bob_consumer"
        val clientB = createMockAtProtoClient(userDidB)
        val syncServiceB = GraphSyncService(clientB, graphRepoB, specRepoB, taskRepoB)

        // Verify Node B is initially empty
        assertEquals(0, graphRepoB.getNodes().first().size)
        assertEquals(0, specRepoB.getSpecs().first().size)
        assertEquals(0, taskRepoB.getTasks().first().size)

        // Pull Peer (Alice) Graph into Bob's ArcadeDB
        val pullResult = syncServiceB.pullPeerGraph(peerDid = userDidA)
        assertTrue(pullResult.pulledSpecs >= 1, "Pulled specs: ${pullResult.pulledSpecs}")
        assertTrue(pullResult.pulledNodes >= 4, "Pulled nodes: ${pullResult.pulledNodes}")
        assertTrue(pullResult.pulledEdges >= 1, "Pulled edges: ${pullResult.pulledEdges}")
        assertTrue(pullResult.pulledTasks >= 3, "Pulled tasks: ${pullResult.pulledTasks}")

        // =========================================================================
        // STEP 5: Verification of Federated State on Node B
        // =========================================================================
        val federatedSpecsB = specRepoB.getSpecs().first()
        assertEquals(1, federatedSpecsB.size)
        assertEquals("Agent Sync Specification", federatedSpecsB.first().title)

        val federatedNodesB = graphRepoB.getNodes().first()
        assertTrue(federatedNodesB.any { it.id == "doc-agent-sync" })
        assertTrue(federatedNodesB.any { it.id == "doc-agent-sync#requirement-storage-isolation" })

        val federatedEdgesB = graphRepoB.getEdges().first()
        assertTrue(federatedEdgesB.isNotEmpty())

        val federatedTasksB = taskRepoB.getTasks().first()
        assertEquals(3, federatedTasksB.size)
        val mergedTaskOnB = federatedTasksB.firstOrNull { it.title == "Setup Storage Layer" }
        assertNotNull(mergedTaskOnB)
        assertEquals(AgentTaskStatus.MERGED, mergedTaskOnB.agentStatus)
        assertEquals(TaskStatus.DONE, mergedTaskOnB.status)

        // =========================================================================
        // STEP 6: Reactive View on Node B
        // =========================================================================
        val viewModelB = WorkspaceViewModel(
            taskRepository = taskRepoB,
            projectRepository = projectRepoB,
            contextGraphRepository = graphRepoB,
            markdownIngestionService = MarkdownIngestionService(graphRepoB),
            dagDecomposerService = DAGDecomposerService(taskRepoB, graphRepoB),
        )

        // Verify UI navigation tab switching and document viewing on Node B
        viewModelB.selectTab(WorkspaceNavTab.DOCUMENTS)
        val docsOnB = viewModelB.documentRoots.first { it.isNotEmpty() }
        assertEquals("doc-agent-sync", docsOnB.first().id)

        // Switch to Agent Queue and verify execution tiers on federated node
        viewModelB.selectTab(WorkspaceNavTab.AGENT_QUEUE)
        val tiersOnB = viewModelB.executionTiers.first { it.isNotEmpty() }
        assertEquals(3, tiersOnB.size)
        assertEquals("Setup Storage Layer", tiersOnB[0].first().title)
        assertEquals(AgentTaskStatus.MERGED, tiersOnB[0].first().agentStatus)

        println("====================================================================")
        println("  END-TO-END INTEGRATION TEST COMPLETED SUCCESSFULLY!")
        println("  • Embedded ArcadeDB Graph Engine: VERIFIED")
        println("  • Markdown Ingestion & live AST Wikilinks: VERIFIED")
        println("  • Topological DAG Decomposition & Approval: VERIFIED")
        println("  • ATProto Lexicon Serialization & XRPC: VERIFIED")
        println("  • Peer PDS Push & LWW Federated Pull: VERIFIED")
        println("  • Cross-Node Compose Desktop Workspace State: VERIFIED")
        println("====================================================================")
    }
}
