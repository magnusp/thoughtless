package github.magnusp.thoughtless.sync.atproto

import github.magnusp.thoughtless.domain.model.AgentTaskStatus
import github.magnusp.thoughtless.domain.model.ContextEdge
import github.magnusp.thoughtless.domain.model.ContextNode
import github.magnusp.thoughtless.domain.model.NodeType
import github.magnusp.thoughtless.domain.model.Spec
import github.magnusp.thoughtless.domain.model.Task
import github.magnusp.thoughtless.domain.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AtProtoMappersTest {

    @Test
    fun testSpecRecordRoundTrip() {
        val original = Spec(
            id = "spec-auth-flow",
            projectId = "proj-1",
            title = "Authentication Flow RFC",
            systemSpec = "Complete OAuth2 PKCE flow",
            nonGoals = "No legacy sessions",
            rfcDocument = "RFC #101",
            frozenAt = 1700000000000L,
        )

        val record = original.toAtProtoRecord(createdAtTimestamp = 1700000000000L, updatedAtTimestamp = 1700000005000L)
        assertEquals("thoughtless.spec", record.type)
        assertEquals(original.title, record.title)
        assertEquals(original.projectId, record.projectId)
        assertNotNull(record.createdAt)

        val domain = record.toDomain(original.id)
        assertEquals(original.id, domain.id)
        assertEquals(original.projectId, domain.projectId)
        assertEquals(original.title, domain.title)
        assertEquals(original.systemSpec, domain.systemSpec)
        assertEquals(original.nonGoals, domain.nonGoals)
        assertEquals(original.rfcDocument, domain.rfcDocument)
        assertEquals(original.frozenAt, domain.frozenAt)
    }

    @Test
    fun testContextNodeRecordRoundTrip() {
        val original = ContextNode(
            id = "spec-auth#oauth2",
            type = NodeType.REQUIREMENT,
            label = "OAuth 2.0 PKCE Requirement",
            body = "Clients must use PKCE when authenticating.",
            filePath = "docs/auth.md",
            createdAt = 1700000000000L,
            updatedAt = 1700000010000L,
        )

        val record = original.toAtProtoRecord()
        assertEquals("thoughtless.contextNode", record.type)
        assertEquals(original.id, record.nodeId)
        assertEquals("REQUIREMENT", record.nodeType)
        assertEquals(original.label, record.label)
        assertEquals(original.body, record.body)

        val domain = record.toDomain()
        assertEquals(original.id, domain.id)
        assertEquals(original.type, domain.type)
        assertEquals(original.label, domain.label)
        assertEquals(original.body, domain.body)
        assertEquals(original.filePath, domain.filePath)
    }

    @Test
    fun testContextEdgeRecordRoundTrip() {
        val original = ContextEdge(
            id = "edge-1",
            fromId = "spec-auth#oauth2",
            toId = "endpoint-auth-token",
            relation = "implements",
            createdAt = 1700000000000L,
        )

        val record = original.toAtProtoRecord()
        assertEquals("thoughtless.contextEdge", record.type)
        assertEquals(original.id, record.edgeId)
        assertEquals(original.fromId, record.fromId)
        assertEquals(original.toId, record.toId)
        assertEquals(original.relation, record.relation)

        val domain = record.toDomain()
        assertEquals(original.id, domain.id)
        assertEquals(original.fromId, domain.fromId)
        assertEquals(original.toId, domain.toId)
        assertEquals(original.relation, domain.relation)
    }

    @Test
    fun testAgentTaskRecordRoundTrip() {
        val original = Task(
            id = "task-agent-1",
            projectId = "proj-1",
            title = "Implement PKCE auth exchange",
            description = "Draft endpoint code",
            status = TaskStatus.DONE,
            createdAt = 1700000000000L,
            updatedAt = 1700000020000L,
            agentStatus = AgentTaskStatus.MERGED,
            targetFile = "AuthClient.kt",
            contextFiles = listOf("Spec.md", "Graph.md"),
            dependsOn = listOf("task-base-http"),
            acceptanceCriteria = listOf("Passes tests"),
        )

        val record = original.toAgentTaskRecord()
        assertEquals("thoughtless.agentTask", record.type)
        assertEquals(original.title, record.title)
        assertEquals("MERGED", record.agentStatus)
        assertEquals(listOf("task-base-http"), record.dependsOn)

        val domain = record.toDomain(original.id)
        assertEquals(original.id, domain.id)
        assertEquals(original.title, domain.title)
        assertEquals(AgentTaskStatus.MERGED, domain.agentStatus)
        assertEquals(TaskStatus.DONE, domain.status)
        assertEquals(original.targetFile, domain.targetFile)
        assertEquals(original.contextFiles, domain.contextFiles)
        assertEquals(original.dependsOn, domain.dependsOn)
    }
}
