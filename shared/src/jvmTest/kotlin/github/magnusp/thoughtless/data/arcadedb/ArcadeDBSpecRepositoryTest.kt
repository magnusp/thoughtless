package github.magnusp.thoughtless.data.arcadedb

import github.magnusp.thoughtless.domain.model.Spec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ArcadeDBSpecRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var engine: ArcadeDBEngine
    private lateinit var specRepo: ArcadeDBSpecRepository

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("spec-repo-test").toFile()
        engine = ArcadeDBEngine(tempDir.absolutePath)
        specRepo = ArcadeDBSpecRepository(engine)
    }

    @AfterTest
    fun tearDown() {
        engine.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun testSpecCrud() = runBlocking {
        val spec = Spec(
            id = "spec-auth",
            projectId = "proj-1",
            title = "Authentication Architecture",
            systemSpec = "Passkey and WebAuthn based system",
            nonGoals = "No SMS OTP",
            rfcDocument = "RFC-001: Passkeys",
            frozenAt = 1700000000000L,
        )

        specRepo.saveSpec(spec)

        val fetched = specRepo.getSpecById("spec-auth").first()
        assertNotNull(fetched)
        assertEquals("spec-auth", fetched.id)
        assertEquals("Authentication Architecture", fetched.title)
        assertEquals("No SMS OTP", fetched.nonGoals)
        assertEquals(1700000000000L, fetched.frozenAt)

        val allSpecs = specRepo.getSpecs().first()
        assertEquals(1, allSpecs.size)

        val projectSpecs = specRepo.getSpecsByProject("proj-1").first()
        assertEquals(1, projectSpecs.size)

        // Delete
        specRepo.deleteSpec("spec-auth")
        val afterDelete = specRepo.getSpecById("spec-auth").first()
        assertNull(afterDelete)
    }

    @Test
    fun testReactiveFlowUpdates() = runBlocking {
        val collectedLists = mutableListOf<List<Spec>>()
        val flowStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            specRepo.getSpecs().take(3).collect {
                collectedLists.add(it)
                if (collectedLists.size == 1) {
                    flowStarted.complete(Unit)
                }
            }
        }

        flowStarted.await()
        kotlinx.coroutines.delay(50)

        val s1 = Spec("s1", "p1", "Spec 1", "Body 1")
        val s2 = Spec("s2", "p1", "Spec 2", "Body 2")

        specRepo.saveSpec(s1)
        kotlinx.coroutines.delay(50)
        specRepo.saveSpec(s2)

        job.join()

        assertEquals(3, collectedLists.size)
        assertEquals(0, collectedLists[0].size)
        assertEquals(1, collectedLists[1].size)
        assertEquals(2, collectedLists[2].size)
    }
}
