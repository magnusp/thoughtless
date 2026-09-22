package github.magnusp.thoughtless.service

import github.magnusp.thoughtless.ai.OllamaClient
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBContextGraphRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBEngine
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBSpecRepository
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
import kotlin.test.assertTrue

class SpecEngineServiceTest {

    private lateinit var tempDir: File
    private lateinit var engine: ArcadeDBEngine
    private lateinit var graphRepo: ArcadeDBContextGraphRepository
    private lateinit var specRepo: ArcadeDBSpecRepository
    private lateinit var ingestionService: MarkdownIngestionService

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("spec-engine-test").toFile()
        engine = ArcadeDBEngine(tempDir.absolutePath)
        graphRepo = ArcadeDBContextGraphRepository(engine)
        specRepo = ArcadeDBSpecRepository(engine)
        ingestionService = MarkdownIngestionService(graphRepo)
    }

    @AfterTest
    fun tearDown() {
        engine.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun testInteractiveInterviewLoopAndSpecGeneration() = runBlocking {
        var turnCount = 0
        val mockHttpClient = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addHandler {
                    turnCount++
                    val responseText = when (turnCount) {
                        1 -> "What are the primary inputs and non-goals?"
                        2 -> "Are there any backward-compatibility requirements?"
                        else -> """
                            # WebAuthn Authentication Spec
                            
                            ## System Specification
                            Passkey authentication via standard browser APIs [[doc-security-overview]].
                            
                            ## Non-Goals
                            SMS fallback and email OTP are explicitly excluded.
                            
                            ## Architecture & Data Flow
                            Ceremony protocol details.
                        """.trimIndent()
                    }

                    respond(
                        content = """{"message": {"role": "assistant", "content": ${Json.encodeToString(responseText)}}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val ollamaClient = OllamaClient(client = mockHttpClient)
        val specEngine = SpecEngineService(ollamaClient, specRepo, ingestionService)

        // 1. Start interview
        val (session, q1) = specEngine.startInterview(
            projectId = "proj-auth",
            title = "WebAuthn Authentication Spec",
            initialGoal = "Implement WebAuthn passkey authentication",
        )
        assertNotNull(session)
        assertEquals("What are the primary inputs and non-goals?", q1)
        assertEquals(1, session.turns.size)

        // 2. Submit answer turn 1
        val q2 = specEngine.submitAnswer(session, "Inputs are public key credentials. Non-goal: no SMS.")
        assertEquals("Are there any backward-compatibility requirements?", q2)
        assertEquals(2, session.turns.size)

        // 3. Submit answer turn 2
        val status = specEngine.submitAnswer(session, "No backward compatibility needed.")
        assertTrue(session.isReadyToGenerate)
        assertTrue(status.contains("Ready to generate"))

        // 4. Generate and freeze spec
        val frozenSpec = specEngine.generateAndFreezeSpec(session)
        assertNotNull(frozenSpec)
        assertEquals("spec-proj-auth-webauthn-authentication-spec", frozenSpec.id)
        assertNotNull(frozenSpec.frozenAt)
        assertNotNull(frozenSpec.nonGoals)
        assertTrue(frozenSpec.nonGoals!!.contains("SMS fallback"))

        // Verify persisted in SpecRepository
        val savedSpec = specRepo.getSpecById(frozenSpec.id).first()
        assertNotNull(savedSpec)
        assertEquals(frozenSpec.title, savedSpec.title)

        // Verify ingested into ContextGraphRepository
        val graphNode = graphRepo.getNodeById(frozenSpec.id).first()
        assertNotNull(graphNode)
        assertEquals(NodeType.SPEC, graphNode.type)

        ollamaClient.close()
    }
}
