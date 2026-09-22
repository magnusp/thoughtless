package github.magnusp.thoughtless.data.arcadedb

import github.magnusp.thoughtless.domain.model.ContextEdge
import github.magnusp.thoughtless.domain.model.ContextNode
import github.magnusp.thoughtless.domain.model.NodeType
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
import kotlin.test.assertTrue

class ArcadeDBContextGraphRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var engine: ArcadeDBEngine
    private lateinit var graphRepo: ArcadeDBContextGraphRepository

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("context-graph-test").toFile()
        engine = ArcadeDBEngine(tempDir.absolutePath)
        graphRepo = ArcadeDBContextGraphRepository(engine)
    }

    @AfterTest
    fun tearDown() {
        engine.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun testNodeAndEdgeCrud() = runBlocking {
        val node1 = ContextNode(
            id = "node-1",
            type = NodeType.REQUIREMENT,
            label = "User Authentication",
            body = "Users must be able to log in securely.",
            embedding = FloatArray(384) { 0.1f },
            filePath = "auth/spec.md",
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        val node2 = ContextNode(
            id = "node-2",
            type = NodeType.ENTITY,
            label = "User Session",
            body = "Session token representation",
            embedding = FloatArray(384) { 0.2f },
            filePath = "auth/session.kt",
            createdAt = 2000L,
            updatedAt = 2000L,
        )

        // Save nodes
        graphRepo.saveNode(node1)
        graphRepo.saveNode(node2)

        val fetchedNode1 = graphRepo.getNodeById("node-1").first()
        assertNotNull(fetchedNode1)
        assertEquals("User Authentication", fetchedNode1.label)
        assertEquals(NodeType.REQUIREMENT, fetchedNode1.type)
        val fetchedEmb = fetchedNode1.embedding
        assertNotNull(fetchedEmb)
        assertEquals(384, fetchedEmb.size)

        val allNodes = graphRepo.getNodes().first()
        assertEquals(2, allNodes.size)

        // Create edge
        val edge1 = ContextEdge(
            id = "edge-1",
            fromId = "node-1",
            toId = "node-2",
            relation = "REFERENCES",
            createdAt = 3000L,
        )
        graphRepo.saveEdge(edge1)

        val allEdges = graphRepo.getEdges().first()
        assertEquals(1, allEdges.size)
        assertEquals("edge-1", allEdges.first().id)
        assertEquals("node-1", allEdges.first().fromId)
        assertEquals("node-2", allEdges.first().toId)

        val node1Edges = graphRepo.getEdgesForNode("node-1").first()
        assertEquals(1, node1Edges.size)

        // Delete edge
        graphRepo.deleteEdge("edge-1")
        assertEquals(0, graphRepo.getEdges().first().size)

        // Delete node
        graphRepo.deleteNode("node-1")
        assertNull(graphRepo.getNodeById("node-1").first())
        assertEquals(1, graphRepo.getNodes().first().size)
    }

    @Test
    fun testFindRelatedNHopTraversal() = runBlocking {
        // Build graph: n1 -> n2 -> n3 -> n4
        val n1 = ContextNode(id = "n1", type = NodeType.SPEC, label = "Spec 1", body = "B1", createdAt = 1L, updatedAt = 1L)
        val n2 = ContextNode(id = "n2", type = NodeType.REQUIREMENT, label = "Req 2", body = "B2", createdAt = 2L, updatedAt = 2L)
        val n3 = ContextNode(id = "n3", type = NodeType.ENTITY, label = "Entity 3", body = "B3", createdAt = 3L, updatedAt = 3L)
        val n4 = ContextNode(id = "n4", type = NodeType.TABLE, label = "Table 4", body = "B4", createdAt = 4L, updatedAt = 4L)

        graphRepo.saveNode(n1)
        graphRepo.saveNode(n2)
        graphRepo.saveNode(n3)
        graphRepo.saveNode(n4)

        graphRepo.saveEdge(ContextEdge("e1", "n1", "n2", "REFERENCES", 10L))
        graphRepo.saveEdge(ContextEdge("e2", "n2", "n3", "IMPLEMENTS", 20L))
        graphRepo.saveEdge(ContextEdge("e3", "n3", "n4", "MUTATES", 30L))

        // 1 hop from n1 should find only n2
        val related1Hop = graphRepo.findRelated("n1", hops = 1)
        assertEquals(1, related1Hop.size)
        assertEquals("n2", related1Hop.first().id)

        // 2 hops from n1 should find n2 and n3
        val related2Hops = graphRepo.findRelated("n1", hops = 2)
        assertEquals(2, related2Hops.size)
        val related2HopIds = related2Hops.map { it.id }.toSet()
        assertTrue(related2HopIds.contains("n2"))
        assertTrue(related2HopIds.contains("n3"))

        // 3 hops from n1 should find n2, n3, and n4
        val related3Hops = graphRepo.findRelated("n1", hops = 3)
        assertEquals(3, related3Hops.size)
        val related3HopIds = related3Hops.map { it.id }.toSet()
        assertTrue(related3HopIds.contains("n2"))
        assertTrue(related3HopIds.contains("n3"))
        assertTrue(related3HopIds.contains("n4"))
    }

    @Test
    fun testGetBacklinks() = runBlocking {
        val target = ContextNode(id = "target", type = NodeType.ENTITY, label = "Target", body = "Core Entity", createdAt = 1L, updatedAt = 1L)
        val caller1 = ContextNode(id = "caller1", type = NodeType.ENDPOINT, label = "API 1", body = "Endpoint 1", createdAt = 2L, updatedAt = 2L)
        val caller2 = ContextNode(id = "caller2", type = NodeType.ENDPOINT, label = "API 2", body = "Endpoint 2", createdAt = 3L, updatedAt = 3L)

        graphRepo.saveNode(target)
        graphRepo.saveNode(caller1)
        graphRepo.saveNode(caller2)

        graphRepo.saveEdge(ContextEdge("e1", "caller1", "target", "REFERENCES", 10L))
        graphRepo.saveEdge(ContextEdge("e2", "caller2", "target", "MUTATES", 20L))

        val backlinks = graphRepo.getBacklinks("target")
        assertEquals(2, backlinks.size)
        val backlinkIds = backlinks.map { it.id }.toSet()
        assertTrue(backlinkIds.contains("caller1"))
        assertTrue(backlinkIds.contains("caller2"))
    }

    @Test
    fun testAnalyzeImpact() = runBlocking {
        // Dependency chain: upstream -> middle -> leaf
        // leaf is target; if leaf changes, what is impacted (upstream nodes pointing to it)?
        val leaf = ContextNode(id = "leaf", type = NodeType.ENTITY, label = "Schema Table", body = "Core schema", createdAt = 1L, updatedAt = 1L)
        val mid = ContextNode(id = "mid", type = NodeType.REQUIREMENT, label = "Service", body = "Logic", createdAt = 2L, updatedAt = 2L)
        val top = ContextNode(id = "top", type = NodeType.ENDPOINT, label = "UI Component", body = "View", createdAt = 3L, updatedAt = 3L)

        graphRepo.saveNode(leaf)
        graphRepo.saveNode(mid)
        graphRepo.saveNode(top)

        // mid references leaf, top implements mid
        graphRepo.saveEdge(ContextEdge("e1", "mid", "leaf", "REFERENCES", 10L))
        graphRepo.saveEdge(ContextEdge("e2", "top", "mid", "IMPLEMENTS", 20L))

        val impacted = graphRepo.analyzeImpact("leaf")
        assertEquals(2, impacted.size)
        val impactedIds = impacted.map { it.id }.toSet()
        assertTrue(impactedIds.contains("mid"))
        assertTrue(impactedIds.contains("top"))
    }

    @Test
    fun testFindSimilarVectors() = runBlocking {
        // Orthogonal / distinct unit vectors in first 2 components:
        // baseVec: [1.0, 0.0, ...]
        // closeVec: [0.9, 0.1, ...] (cosine sim ~ 0.99)
        // farVec: [0.0, 1.0, ...] (orthogonal, cosine sim = 0.0)
        val baseVec = FloatArray(384) { 0f }.also { it[0] = 1.0f }
        val closeVec = FloatArray(384) { 0f }.also { it[0] = 0.9f; it[1] = 0.1f }
        val farVec = FloatArray(384) { 0f }.also { it[1] = 1.0f }

        val n1 = ContextNode(id = "n-base", type = NodeType.SPEC, label = "Base", body = "B", embedding = baseVec, createdAt = 1L, updatedAt = 1L)
        val n2 = ContextNode(id = "n-close", type = NodeType.SPEC, label = "Close", body = "C", embedding = closeVec, createdAt = 2L, updatedAt = 2L)
        val n3 = ContextNode(id = "n-far", type = NodeType.SPEC, label = "Far", body = "F", embedding = farVec, createdAt = 3L, updatedAt = 3L)

        graphRepo.saveNode(n1)
        graphRepo.saveNode(n2)
        graphRepo.saveNode(n3)

        val similar = graphRepo.findSimilar(baseVec, topK = 2)
        assertEquals(2, similar.size)
        // Highest similarity is n-base (sim = 1.0), second is n-close (sim ~ 0.99), n-far is excluded (sim = 0)
        assertEquals("n-base", similar[0].id)
        assertEquals("n-close", similar[1].id)
    }

    @Test
    fun testReactiveFlowUpdates() = runBlocking {
        val collectedNodes = mutableListOf<List<ContextNode>>()
        val flowStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            graphRepo.getNodes().take(3).collect {
                collectedNodes.add(it)
                if (collectedNodes.size == 1) {
                    flowStarted.complete(Unit)
                }
            }
        }

        flowStarted.await()
        kotlinx.coroutines.delay(50)

        val n1 = ContextNode(id = "n1", type = NodeType.SPEC, label = "N1", body = "B1", createdAt = 1L, updatedAt = 1L)
        val n2 = ContextNode(id = "n2", type = NodeType.SPEC, label = "N2", body = "B2", createdAt = 2L, updatedAt = 2L)

        graphRepo.saveNode(n1)
        kotlinx.coroutines.delay(50)
        graphRepo.saveNode(n2)

        job.join()

        assertEquals(3, collectedNodes.size)
        assertEquals(0, collectedNodes[0].size)
        assertEquals(1, collectedNodes[1].size)
        assertEquals(2, collectedNodes[2].size)
    }
}
