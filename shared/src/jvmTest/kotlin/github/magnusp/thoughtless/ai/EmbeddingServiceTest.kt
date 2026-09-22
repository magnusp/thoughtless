package github.magnusp.thoughtless.ai

import github.magnusp.thoughtless.data.arcadedb.ArcadeDBContextGraphRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBEngine
import github.magnusp.thoughtless.domain.model.ContextNode
import github.magnusp.thoughtless.domain.model.NodeType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddingServiceTest {

    private lateinit var tempDir: File
    private lateinit var engine: ArcadeDBEngine
    private lateinit var graphRepo: ArcadeDBContextGraphRepository

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("embedding-service-test").toFile()
        engine = ArcadeDBEngine(tempDir.absolutePath)
        graphRepo = ArcadeDBContextGraphRepository(engine)
    }

    @AfterTest
    fun tearDown() {
        engine.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun testSuccessfulEmbeddingGenerationAndPersistence() = runBlocking {
        val fakeVector = List(384) { 0.25f }
        val mockHttpClient = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addHandler { request ->
                    if (request.url.encodedPath.endsWith("/api/embeddings")) {
                        respond(
                            content = """{"embedding": [${fakeVector.joinToString(",")}]}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    } else {
                        respond(
                            content = """{"version": "0.5.0"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }
                }
            }
        }

        val ollamaClient = OllamaClient(client = mockHttpClient)
        val service = EmbeddingService(ollamaClient, graphRepo)

        val node = ContextNode(
            id = "node-to-embed",
            type = NodeType.REQUIREMENT,
            label = "Federated Graph Synchronization",
            body = "Synchronize subgraphs over ATProto.",
            embedding = null,
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        graphRepo.saveNode(node)

        val embedded = service.embedNode(node)

        assertNotNull(embedded.embedding)
        assertEquals(384, embedded.embedding!!.size)
        assertEquals(0.25f, embedded.embedding!![0])

        // Verify updated in repository
        val inRepo = graphRepo.getNodeById("node-to-embed").first()
        assertNotNull(inRepo)
        assertNotNull(inRepo.embedding)
        assertEquals(384, inRepo.embedding!!.size)
    }

    @Test
    fun testGracefulOfflineDegradationWhenOllamaUnavailable() = runBlocking {
        val mockOfflineClient = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addHandler {
                    respond(
                        content = "Internal Server Error",
                        status = HttpStatusCode.InternalServerError,
                    )
                }
            }
        }

        val ollamaClient = OllamaClient(client = mockOfflineClient)
        val service = EmbeddingService(ollamaClient, graphRepo)

        val node = ContextNode(
            id = "node-offline",
            type = NodeType.SPEC,
            label = "Offline Spec",
            body = "This should not fail even if Ollama is down.",
            embedding = null,
            createdAt = 2000L,
            updatedAt = 2000L,
        )
        graphRepo.saveNode(node)

        // Should not throw, should return node with embedding == null
        val result = service.embedNode(node)
        assertNull(result.embedding)

        val inRepo = graphRepo.getNodeById("node-offline").first()
        assertNotNull(inRepo)
        assertNull(inRepo.embedding)
    }

    @Test
    fun testBatchEmbedMissing() = runBlocking {
        val fakeVector = List(384) { 0.1f }
        val mockHttpClient = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addHandler {
                    respond(
                        content = """{"embedding": [${fakeVector.joinToString(",")}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val ollamaClient = OllamaClient(client = mockHttpClient)
        val service = EmbeddingService(ollamaClient, graphRepo)

        val n1 = ContextNode("n1", NodeType.ENTITY, "N1", "B1", embedding = null, createdAt = 1L, updatedAt = 1L)
        val n2 = ContextNode("n2", NodeType.ENTITY, "N2", "B2", embedding = FloatArray(384) { 0.9f }, createdAt = 2L, updatedAt = 2L)
        graphRepo.saveNode(n1)
        graphRepo.saveNode(n2)

        val processed = service.embedMissing(listOf(n1, n2))

        assertEquals(2, processed.size)
        // n1 was embedded
        assertNotNull(processed[0].embedding)
        assertEquals(0.1f, processed[0].embedding!![0])
        // n2 kept its existing embedding
        assertNotNull(processed[1].embedding)
        assertEquals(0.9f, processed[1].embedding!![0])
    }
}
