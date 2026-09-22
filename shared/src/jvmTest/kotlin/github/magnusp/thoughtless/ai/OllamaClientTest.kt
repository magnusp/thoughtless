package github.magnusp.thoughtless.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OllamaClientTest {

    @Test
    fun testIsAvailableReturnsTrueOnSuccess() = runBlocking {
        val mockHttpClient = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addHandler { request ->
                    if (request.url.encodedPath.endsWith("/api/version")) {
                        respond(
                            content = """{"version": "0.5.4"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    } else {
                        respond(content = "Not found", status = HttpStatusCode.NotFound)
                    }
                }
            }
        }

        val client = OllamaClient(client = mockHttpClient)
        assertTrue(client.isAvailable())
        client.close()
    }

    @Test
    fun testIsAvailableReturnsFalseOnFailure() = runBlocking {
        val mockHttpClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(content = "Offline", status = HttpStatusCode.ServiceUnavailable)
                }
            }
        }

        val client = OllamaClient(client = mockHttpClient)
        assertFalse(client.isAvailable())
        client.close()
    }

    @Test
    fun testChatCompletion() = runBlocking {
        val mockHttpClient = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addHandler { request ->
                    if (request.url.encodedPath.endsWith("/api/chat")) {
                        respond(
                            content = """{"message": {"role": "assistant", "content": "Decomposed subtasks ready."}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    } else {
                        respond(content = "Not found", status = HttpStatusCode.NotFound)
                    }
                }
            }
        }

        val client = OllamaClient(client = mockHttpClient)
        val answer = client.chat(
            prompt = "Decompose this user story into tasks",
            systemPrompt = "You are a senior architect",
        )

        assertNotNull(answer)
        assertEquals("Decomposed subtasks ready.", answer)
        client.close()
    }
}
