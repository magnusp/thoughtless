package github.magnusp.thoughtless.workspace

import github.magnusp.thoughtless.domain.validation.WorkspaceValidator
import github.magnusp.thoughtless.identity.OperatorIdentity
import java.io.File
import java.util.concurrent.TimeUnit

enum class WorkspaceStatus {
    READY,
    MISSING_LOCAL_CLONE,
    UNAUTHORIZED,
    INVALID_IDENTIFIER,
    ERROR,
}

data class WorkspaceCheckResult(
    val workspace: String,
    val localPath: File?,
    val status: WorkspaceStatus,
    val details: String? = null,
)

class LocalWorkspaceService(
    val baseWorkspacesDir: File = File(System.getProperty("user.home"), ".thoughtless/workspaces"),
    private val processRunner: (List<String>, File?) -> Pair<Int, String> = { cmd, workDir ->
        try {
            val proc = ProcessBuilder(cmd)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            val text = proc.inputStream.bufferedReader().use { it.readText().trim() }
            val exited = proc.waitFor(30, TimeUnit.SECONDS)
            if (exited) {
                Pair(proc.exitValue(), text)
            } else {
                proc.destroyForcibly()
                Pair(-1, "Process timed out")
            }
        } catch (e: Exception) {
            Pair(-1, e.message ?: "Failed to execute")
        }
    }
) {
    /**
     * Resolves a workspace identifier (e.g. "github.com/magnusp/thoughtless") into
     * a canonical local path: ~/.thoughtless/workspaces/github.com/magnusp/thoughtless
     */
    fun resolveLocalWorkspacePath(workspace: String): File {
        WorkspaceValidator.validateWorkspace(workspace)
        // Clean prefix if any
        val clean = workspace.removePrefix("https://").removePrefix("http://")
        return File(baseWorkspacesDir, clean)
    }

    /**
     * Inspects the local workspace status and checks whether the repository is cloned locally.
     */
    fun checkWorkspaceStatus(workspace: String): WorkspaceCheckResult {
        try {
            WorkspaceValidator.validateWorkspace(workspace)
        } catch (e: Exception) {
            return WorkspaceCheckResult(
                workspace = workspace,
                localPath = null,
                status = WorkspaceStatus.INVALID_IDENTIFIER,
                details = e.message
            )
        }

        val targetDir = resolveLocalWorkspacePath(workspace)
        val gitDir = File(targetDir, ".git")

        return if (gitDir.exists()) {
            WorkspaceCheckResult(
                workspace = workspace,
                localPath = targetDir,
                status = WorkspaceStatus.READY,
                details = "Repository cloned locally at ${targetDir.absolutePath}"
            )
        } else {
            WorkspaceCheckResult(
                workspace = workspace,
                localPath = targetDir,
                status = WorkspaceStatus.MISSING_LOCAL_CLONE,
                details = "Repository not found locally; needs to be cloned to ${targetDir.absolutePath}"
            )
        }
    }

    /**
     * Pre-flight batch check for all workspaces referenced in an execution plan.
     */
    fun checkAllWorkspaces(workspaces: Collection<String?>): List<WorkspaceCheckResult> {
        val nonNullWorkspaces = workspaces.filterNotNull().toSet()
        return nonNullWorkspaces.map { checkWorkspaceStatus(it) }
    }

    /**
     * Clones the repository locally using the operator's identity if needed.
     */
    fun ensureWorkspaceCloned(
        workspace: String,
        identity: OperatorIdentity? = null,
    ): Result<File> {
        val status = checkWorkspaceStatus(workspace)
        if (status.status == WorkspaceStatus.READY && status.localPath != null) {
            return Result.success(status.localPath)
        }
        if (status.status == WorkspaceStatus.INVALID_IDENTIFIER) {
            return Result.failure(IllegalArgumentException(status.details ?: "Invalid workspace identifier"))
        }

        val targetDir = resolveLocalWorkspacePath(workspace)
        targetDir.parentFile.mkdirs()

        val clean = workspace.removePrefix("https://").removePrefix("http://")
        val cloneUrl = if (identity?.token != null && clean.startsWith("github.com/")) {
            "https://x-access-token:${identity.token}@$clean.git"
        } else if (clean.startsWith("github.com/")) {
            "https://$clean.git"
        } else {
            clean
        }

        val cmd = listOf("git", "clone", cloneUrl, targetDir.absolutePath)
        val (exit, out) = processRunner(cmd, null)

        return if (exit == 0 && File(targetDir, ".git").exists()) {
            Result.success(targetDir)
        } else {
            val safeOut = if (identity?.token != null) out.replace(identity.token, "******") else out
            Result.failure(RuntimeException("git clone failed ($exit): $safeOut"))
        }
    }

    /**
     * Creates an isolated git worktree for a task, fulfilling the Execution Isolation Invariant.
     * e.g., git worktree add -B agent/<taskId> <worktreePath>
     */
    fun prepareTaskWorktree(
        workspace: String,
        taskId: String,
        worktreeBaseDir: File = File(baseWorkspacesDir, "_worktrees")
    ): Result<File> {
        val repoDir = resolveLocalWorkspacePath(workspace)
        if (!File(repoDir, ".git").exists()) {
            return Result.failure(IllegalStateException("Local repository not present: ${repoDir.absolutePath}. Clone it first."))
        }

        val worktreeDir = File(worktreeBaseDir, taskId)
        worktreeDir.parentFile.mkdirs()

        val branchName = "agent/$taskId"
        val cmd = listOf("git", "worktree", "add", "-B", branchName, worktreeDir.absolutePath)
        val (exit, out) = processRunner(cmd, repoDir)

        return if (exit == 0 && worktreeDir.exists()) {
            Result.success(worktreeDir)
        } else {
            Result.failure(RuntimeException("Failed to create worktree ($exit): $out"))
        }
    }
}
