package github.magnusp.thoughtless.service

import github.magnusp.thoughtless.data.arcadedb.ArcadeDBContextGraphRepository
import github.magnusp.thoughtless.data.arcadedb.ArcadeDBEngine
import github.magnusp.thoughtless.domain.model.NodeType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarkdownIngestionServiceTest {

    private lateinit var tempDir: File
    private lateinit var engine: ArcadeDBEngine
    private lateinit var graphRepo: ArcadeDBContextGraphRepository
    private lateinit var service: MarkdownIngestionService

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("markdown-ingest-test").toFile()
        engine = ArcadeDBEngine(tempDir.absolutePath)
        graphRepo = ArcadeDBContextGraphRepository(engine)
        service = MarkdownIngestionService(graphRepo)
    }

    @AfterTest
    fun tearDown() {
        engine.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun testParseFrontmatterAndHeadings() {
        val markdown = """
            ---
            id: doc-auth-spec
            type: SPEC
            label: Authentication Architecture
            project: proj-identity
            ---
            
            # Authentication Architecture
            
            This document describes the identity and authentication subsystem.
            
            ## Requirement: Passwordless Auth
            Users should log in via WebAuthn or passkeys.
            
            ## Entity Model: User Profile
            Stores public key credentials and email address.
            
            ## API Endpoint: /auth/login
            Initiates ceremony with challenge response.
        """.trimIndent()

        val result = service.parse(markdown, filePath = "specs/auth.md")

        assertEquals("doc-auth-spec", result.rootNode.id)
        assertEquals(NodeType.SPEC, result.rootNode.type)
        assertEquals("Authentication Architecture", result.rootNode.label)
        assertEquals("specs/auth.md", result.rootNode.filePath)

        assertEquals(3, result.sectionNodes.size)

        val reqSection = result.sectionNodes.firstOrNull { it.label.contains("Requirement") }
        assertNotNull(reqSection)
        assertEquals(NodeType.REQUIREMENT, reqSection.type)
        assertTrue(reqSection.body.contains("WebAuthn"))

        val entitySection = result.sectionNodes.firstOrNull { it.label.contains("Entity") }
        assertNotNull(entitySection)
        assertEquals(NodeType.ENTITY, entitySection.type)

        val endpointSection = result.sectionNodes.firstOrNull { it.label.contains("Endpoint") }
        assertNotNull(endpointSection)
        assertEquals(NodeType.ENDPOINT, endpointSection.type)

        // Verify parent references edges
        assertEquals(3, result.edges.size)
        assertTrue(result.edges.all { it.fromId == "doc-auth-spec" })
    }

    @Test
    fun testWikilinkExtractionWithTypedRelations() {
        val markdown = """
            ---
            id: doc-sync
            ---
            
            # Sync Architecture
            
            This component [[implements:spec-atproto]] and [[depends_on:node-db-storage]].
            It also mutates [[mutates:table-sync-state]] and references [[doc-auth-spec|Authentication Spec]].
            Regular link: [[plain-target-id]].
        """.trimIndent()

        val result = service.parse(markdown)

        assertEquals("doc-sync", result.rootNode.id)

        val edges = result.edges
        assertEquals(5, edges.size)

        val implementsEdge = edges.firstOrNull { it.toId == "spec-atproto" }
        assertNotNull(implementsEdge)
        assertEquals("IMPLEMENTS", implementsEdge.relation)

        val dependsEdge = edges.firstOrNull { it.toId == "node-db-storage" }
        assertNotNull(dependsEdge)
        assertEquals("DEPENDS_ON", dependsEdge.relation)

        val mutatesEdge = edges.firstOrNull { it.toId == "table-sync-state" }
        assertNotNull(mutatesEdge)
        assertEquals("MUTATES", mutatesEdge.relation)

        val refEdgeWithAlias = edges.firstOrNull { it.toId == "doc-auth-spec" }
        assertNotNull(refEdgeWithAlias)
        assertEquals("REFERENCES", refEdgeWithAlias.relation)

        val plainEdge = edges.firstOrNull { it.toId == "plain-target-id" }
        assertNotNull(plainEdge)
        assertEquals("REFERENCES", plainEdge.relation)
    }

    @Test
    fun testIngestDocumentPersistence() = runBlocking {
        val markdown = """
            ---
            id: doc-milestone-1
            type: REQUIREMENT
            title: Offline First Operations
            ---
            
            # Offline First Operations
            
            Requires persistent storage [[node-arcadedb]].
            
            ## Table Schema: Tasks
            Task entity definition.
        """.trimIndent()

        val result = service.ingestDocument(markdown, filePath = "docs/offline.md")
        assertNotNull(result)

        // Verify persisted in ContextGraphRepository
        val fetchedRoot = graphRepo.getNodeById("doc-milestone-1").first()
        assertNotNull(fetchedRoot)
        assertEquals(NodeType.REQUIREMENT, fetchedRoot.type)
        assertEquals("Offline First Operations", fetchedRoot.label)

        val allNodes = graphRepo.getNodes().first()
        // 3 nodes: Root document + Table section + placeholder created for wikilink target node-arcadedb
        assertEquals(3, allNodes.size)
        assertTrue(allNodes.any { it.id == "doc-milestone-1" })
        assertTrue(allNodes.any { it.id == "doc-milestone-1#table-schema-tasks" })
        assertTrue(allNodes.any { it.id == "node-arcadedb" })

        val allEdges = graphRepo.getEdges().first()
        // 1 containment/reference edge from root to table section + 1 wikilink edge to node-arcadedb
        assertEquals(2, allEdges.size)
        assertTrue(allEdges.any { it.toId == "node-arcadedb" && it.relation == "REFERENCES" })
    }
}
