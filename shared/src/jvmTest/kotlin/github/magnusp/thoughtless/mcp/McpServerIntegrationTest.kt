package github.magnusp.thoughtless.mcp

import github.magnusp.thoughtless.data.InMemoryTaskProposalRepository
import github.magnusp.thoughtless.data.InMemoryTaskRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBContextGraphRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBEngine
import github.magnusp.thoughtless.mcp.protocol.JsonRpcResponse
import github.magnusp.thoughtless.mcp.tools.ThoughtlessMcpTools
import github.magnusp.thoughtless.service.DAGDecomposerService
import github.magnusp.thoughtless.service.TaskProposalService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerIntegrationTest {
    private lateinit var tempDir: File
    private lateinit var engine: ArcadeDBEngine
    private lateinit var server: McpServer
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        tempDir = File.createTempFile("arcadedb-mcp-integration", "").apply {
            delete()
            mkdirs()
        }
        engine = ArcadeDBEngine(tempDir.absolutePath)
        engine.open()

        val taskRepo = InMemoryTaskRepository()
        val proposalRepo = InMemoryTaskProposalRepository()
        val graphRepo = ArcadeDBContextGraphRepository(engine)
        val proposalService = TaskProposalService(proposalRepo, taskRepo, graphRepo)
        val dagService = DAGDecomposerService(taskRepo, graphRepo)

        val tools = ThoughtlessMcpTools(
            taskRepository = taskRepo,
            taskProposalRepository = proposalRepo,
            contextGraphRepository = graphRepo,
            taskProposalService = proposalService,
            dagDecomposerService = dagService,
            arcadeDBEngine = engine
        )
        server = McpServer(tools)
    }

    @AfterTest
    fun tearDown() {
        try {
            engine.close()
            tempDir.deleteRecursively()
        } catch (_: Exception) {}
    }

    @Test
    fun testInitializeHandshake() {
        val req = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""
        val resp = server.handleLine(req)
        assertNotNull(resp)
        assertEquals(JsonPrimitive(1), resp.id)
        assertNull(resp.error)
        val res = resp.result?.jsonObject
        assertNotNull(res)
        assertEquals("2024-11-05", res["protocolVersion"]?.jsonPrimitive?.content)
    }

    @Test
    fun testToolsList() {
        val req = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
        val resp = server.handleLine(req)
        assertNotNull(resp)
        assertEquals(JsonPrimitive(2), resp.id)
        assertNull(resp.error)
        val toolsList = resp.result?.jsonObject?.get("tools")?.jsonArray
        assertNotNull(toolsList)
        assertTrue(toolsList.size >= 8)
    }

    @Test
    fun testToolsCallProposeTask() {
        val req = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"propose_task","arguments":{"title":"New finding","rationale":"Important refactor","suggestedWorkspace":"github.com/my/project"}}}"""
        val resp = server.handleLine(req)
        assertNotNull(resp)
        assertEquals(JsonPrimitive(3), resp.id)
        assertNull(resp.error)
        val content = resp.result?.jsonObject?.get("content")?.jsonArray
        assertNotNull(content)
        val text = content[0].jsonObject["text"]?.jsonPrimitive?.content
        assertNotNull(text)
        assertTrue(text.contains("PROPOSED"))
    }
}
