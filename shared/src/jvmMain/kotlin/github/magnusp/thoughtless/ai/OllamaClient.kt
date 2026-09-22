package github.magnusp.thoughtless.ai

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class OllamaEmbeddingRequest(
    val model: String,
    val prompt: String,
)

@Serializable
data class OllamaEmbeddingResponse(
    val embedding: List<Float> = emptyList(),
)

@Serializable
data class OllamaChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaChatMessage>,
    val stream: Boolean = false,
)

@Serializable
data class OllamaChatResponse(
    val message: OllamaChatMessage? = null,
    @SerialName("done") val done: Boolean = false,
)

/**
 * Programmatic Ollama client targeting localhost:11434 with zero Spring Boot overhead.
 */
class OllamaClient(
    val baseUrl: String = DEFAULT_BASE_URL,
    private val client: HttpClient = createDefaultHttpClient(),
) : AutoCloseable {

    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:11434"
        const val DEFAULT_EMBEDDING_MODEL = "nomic-embed-text"
        const val DEFAULT_CHAT_MODEL = "llama3.2"

        fun createDefaultHttpClient(): HttpClient {
            return HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    })
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 60_000
                    connectTimeoutMillis = 3_000
                    socketTimeoutMillis = 60_000
                }
            }
        }
    }

    /**
     * Checks if Ollama daemon is reachable.
     */
    suspend fun isAvailable(): Boolean {
        return try {
            val response = client.get("$baseUrl/api/version")
            response.status.value in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Generates a single vector embedding for the given [prompt].
     */
    suspend fun generateEmbedding(
        prompt: String,
        model: String = DEFAULT_EMBEDDING_MODEL,
    ): FloatArray? {
        return try {
            val response: OllamaEmbeddingResponse = client.post("$baseUrl/api/embeddings") {
                contentType(ContentType.Application.Json)
                setBody(OllamaEmbeddingRequest(model = model, prompt = prompt))
            }.body()

            if (response.embedding.isNotEmpty()) {
                FloatArray(response.embedding.size) { response.embedding[it] }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Generates chat completion for the given prompt.
     */
    suspend fun chat(
        prompt: String,
        systemPrompt: String? = null,
        model: String = DEFAULT_CHAT_MODEL,
    ): String? {
        val messages = mutableListOf<OllamaChatMessage>()
        if (!systemPrompt.isNullOrBlank()) {
            messages.add(OllamaChatMessage(role = "system", content = systemPrompt))
        }
        messages.add(OllamaChatMessage(role = "user", content = prompt))
        return chat(messages, model)
    }

    /**
     * Generates chat completion for a multi-turn list of [messages].
     */
    suspend fun chat(
        messages: List<OllamaChatMessage>,
        model: String = DEFAULT_CHAT_MODEL,
    ): String? {
        return try {
            val response: OllamaChatResponse = client.post("$baseUrl/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(OllamaChatRequest(model = model, messages = messages, stream = false))
            }.body()
            response.message?.content
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        client.close()
    }
}
