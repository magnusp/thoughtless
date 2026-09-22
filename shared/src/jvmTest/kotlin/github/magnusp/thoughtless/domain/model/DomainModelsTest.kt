package github.magnusp.thoughtless.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class DomainModelsTest {

    @Test
    fun testTaskDefaults() {
        val task = Task(
            id = "task-1",
            title = "Agent task",
            createdAt = 1000L,
            updatedAt = 2000L,
        )

        assertEquals(TaskType.TASK, task.type)
        assertEquals(emptyList(), task.contextFiles)
        assertNull(task.targetFile)
        assertEquals(emptyList(), task.acceptanceCriteria)
        assertEquals(emptyList(), task.dependsOn)
        assertNull(task.milestoneId)
        assertNull(task.agentPermissions)
        assertEquals(AgentTaskStatus.PENDING, task.agentStatus)
    }

    @Test
    fun testTaskWithAgentFields() {
        val permissions = AgentPermissions(
            canInstallPackages = true,
            allowedCommands = listOf("npm test", "gradle test"),
        )
        val task = Task(
            id = "task-2",
            title = "Complex agent task",
            createdAt = 1000L,
            updatedAt = 2000L,
            type = TaskType.SPIKE,
            contextFiles = listOf("file1.kt", "file2.kt"),
            targetFile = "target.kt",
            acceptanceCriteria = listOf("Pass tests"),
            dependsOn = listOf("task-1"),
            milestoneId = "milestone-1",
            agentPermissions = permissions,
            agentStatus = AgentTaskStatus.AGENT_RUNNING,
        )

        assertEquals(TaskType.SPIKE, task.type)
        assertEquals(listOf("file1.kt", "file2.kt"), task.contextFiles)
        assertEquals("target.kt", task.targetFile)
        assertEquals(listOf("Pass tests"), task.acceptanceCriteria)
        assertEquals(listOf("task-1"), task.dependsOn)
        assertEquals("milestone-1", task.milestoneId)
        assertEquals(permissions, task.agentPermissions)
        assertEquals(AgentTaskStatus.AGENT_RUNNING, task.agentStatus)
    }

    @Test
    fun testMilestoneCreation() {
        val milestone = Milestone(
            id = "m-1",
            name = "Auth Foundation",
            version = "v0.1.0-auth",
            description = "OAuth implementation",
            projectId = "p-1",
            createdAt = 1000L,
        )
        assertEquals("m-1", milestone.id)
        assertEquals("v0.1.0-auth", milestone.version)
    }

    @Test
    fun testContextNodeEqualsAndHashCode() {
        val embedding1 = floatArrayOf(0.1f, 0.2f, 0.3f)
        val embedding2 = floatArrayOf(0.1f, 0.2f, 0.3f)
        val node1 = ContextNode(
            id = "node-1",
            type = NodeType.SPEC,
            label = "Auth Spec",
            body = "# Specification",
            embedding = embedding1,
            filePath = "spec/auth.md",
            createdAt = 1000L,
            updatedAt = 2000L,
        )
        val node2 = ContextNode(
            id = "node-1",
            type = NodeType.SPEC,
            label = "Auth Spec",
            body = "# Specification",
            embedding = embedding2,
            filePath = "spec/auth.md",
            createdAt = 1000L,
            updatedAt = 2000L,
        )
        assertEquals(node1, node2)
        assertEquals(node1.hashCode(), node2.hashCode())

        val node3 = node1.copy(label = "Different")
        assertNotEquals(node1, node3)
    }

    @Test
    fun testContextEdgeCreation() {
        val edge = ContextEdge(
            id = "edge-1",
            fromId = "node-1",
            toId = "node-2",
            relation = "references",
            createdAt = 1000L,
        )
        assertEquals("edge-1", edge.id)
        assertEquals("references", edge.relation)
    }

    @Test
    fun testSpecCreation() {
        val spec = Spec(
            id = "spec-1",
            projectId = "p-1",
            title = "Agent Protocol Spec",
            systemSpec = "System constraints",
            nonGoals = "No cloud UI",
            rfcDocument = "RFC #1",
            frozenAt = 5000L,
        )
        assertEquals("spec-1", spec.id)
        assertEquals("Agent Protocol Spec", spec.title)
    }
}
