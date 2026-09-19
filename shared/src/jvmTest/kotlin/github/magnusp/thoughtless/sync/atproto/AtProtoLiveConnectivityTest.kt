package github.magnusp.thoughtless.sync.atproto

import github.magnusp.thoughtless.domain.model.TaskPriority
import github.magnusp.thoughtless.domain.model.TaskStatus
import github.magnusp.thoughtless.util.currentTimeMillis
import github.magnusp.thoughtless.util.formatIsoTimestamp
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AtProtoLiveConnectivityTest {

    @Test
    fun testLivePdsConnectivityIfConfigured() = runTest {
        val pdsUrl = System.getenv("ATPROTO_PDS_URL") ?: "https://bsky.social"
        val identifier = System.getenv("ATPROTO_IDENTIFIER")
        val password = System.getenv("ATPROTO_PASSWORD")

        if (identifier.isNullOrBlank() || password.isNullOrBlank()) {
            println("[Spike 2] Live PDS credentials not provided. Set ATPROTO_IDENTIFIER and ATPROTO_PASSWORD to run live PDS round-trip.")
            return@runTest
        }

        println("[Spike 2] Testing live ATProto PDS connectivity against $pdsUrl with identifier: $identifier")

        val realClient = AtProtoClient(
            pdsUrl = pdsUrl,
            httpClient = HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(AtProtoClient.defaultJson)
                }
            },
        )

        // 1. Authenticate
        val session = realClient.createSession(identifier = identifier, password = password)
        println("[Spike 2] Successfully authenticated! DID: ${session.did}")

        // 2. Write a thoughtless.task record
        val now = currentTimeMillis()
        val testTask = TaskRecord(
            title = "Thoughtless Spike 2 Live Verification",
            description = "Verified at ${formatIsoTimestamp(now)}",
            status = TaskStatus.TODO.value,
            priority = TaskPriority.HIGH.level,
            createdAt = formatIsoTimestamp(now),
        )

        val createResponse = realClient.createTaskRecord(testTask)
        println("[Spike 2] Successfully wrote record! URI: ${createResponse.uri}, CID: ${createResponse.cid}")

        // Extract rkey from URI
        val rkey = createResponse.uri.substringAfterLast("/")

        // 3. Read record back
        val fetchedRecord = realClient.getTaskRecord(rkey)
        println("[Spike 2] Successfully read record! Title: ${fetchedRecord.value.title}")
        assertEquals(testTask.title, fetchedRecord.value.title)
        assertEquals(testTask.status, fetchedRecord.value.status)

        // 4. Clean up
        realClient.deleteTaskRecord(rkey)
        println("[Spike 2] Successfully cleaned up test record $rkey from PDS.")
    }
}
