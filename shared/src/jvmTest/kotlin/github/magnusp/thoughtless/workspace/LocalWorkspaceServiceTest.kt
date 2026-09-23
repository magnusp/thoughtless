package github.magnusp.thoughtless.workspace

import github.magnusp.thoughtless.identity.OperatorAuthType
import github.magnusp.thoughtless.identity.OperatorIdentity
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class LocalWorkspaceServiceTest {

    private lateinit var tempWorkspacesDir: File
    private lateinit var service: LocalWorkspaceService

    @BeforeTest
    fun setUp() {
        tempWorkspacesDir = createTempDirectory("thoughtless-ws-test").toFile()
        service = LocalWorkspaceService(
            baseWorkspacesDir = tempWorkspacesDir,
            processRunner = { cmd, _ ->
                // Mock git clone creating a mock .git dir
                if (cmd.contains("clone")) {
                    val destPath = cmd.last()
                    val gitDir = File(destPath, ".git")
                    gitDir.mkdirs()
                    Pair(0, "Cloned successfully")
                } else if (cmd.contains("worktree")) {
                    val worktreePath = cmd.last()
                    File(worktreePath).mkdirs()
                    Pair(0, "Worktree created")
                } else {
                    Pair(0, "ok")
                }
            }
        )
    }

    @AfterTest
    fun tearDown() {
        tempWorkspacesDir.deleteRecursively()
    }

    @Test
    fun testResolveLocalWorkspacePath() {
        val path = service.resolveLocalWorkspacePath("github.com/org/alpha-service")
        val expected = File(tempWorkspacesDir, "github.com/org/alpha-service")
        assertEquals(expected.absolutePath, path.absolutePath)
    }

    @Test
    fun testRejectsInvalidWorkspacePath() {
        assertFailsWith<IllegalArgumentException> {
            service.resolveLocalWorkspacePath("/root/forbidden")
        }
    }

    @Test
    fun testCheckWorkspaceStatusMissingThenCloned() {
        val ws = "github.com/org/alpha-service"

        // Before clone
        val statusMissing = service.checkWorkspaceStatus(ws)
        assertEquals(WorkspaceStatus.MISSING_LOCAL_CLONE, statusMissing.status)

        // Clone
        val cloneResult = service.ensureWorkspaceCloned(
            workspace = ws,
            identity = OperatorIdentity(OperatorAuthType.GITHUB_CLI, "tester", "token")
        )
        assertTrue(cloneResult.isSuccess)

        // After clone
        val statusReady = service.checkWorkspaceStatus(ws)
        assertEquals(WorkspaceStatus.READY, statusReady.status)
        assertNotNull(statusReady.localPath)
    }

    @Test
    fun testPrepareTaskWorktree() {
        val ws = "github.com/org/alpha-service"
        service.ensureWorkspaceCloned(ws)

        val worktreeResult = service.prepareTaskWorktree(ws, "task-101")
        assertTrue(worktreeResult.isSuccess)
        val worktreeDir = worktreeResult.getOrThrow()
        assertTrue(worktreeDir.exists())
        assertEquals("task-101", worktreeDir.name)
    }

    @Test
    fun testCheckAllWorkspaces() {
        val results = service.checkAllWorkspaces(
            listOf("github.com/org/service-a", "github.com/org/service-b", null)
        )
        assertEquals(2, results.size)
        assertTrue(results.all { it.status == WorkspaceStatus.MISSING_LOCAL_CLONE })
    }
}
