package github.magnusp.thoughtless.domain.validation

/**
 * Validates that workspace identifiers and file targets conform to the
 * decentralized portability invariants.
 */
object WorkspaceValidator {

    private val ABSOLUTE_PATH_PREFIXES = listOf(
        "/",
        "\\",
        "~",
        "file://",
        "file:/",
    )

    private val WINDOWS_DRIVE_REGEX = Regex("""^[a-zA-Z]:[/\\]""")

    /**
     * Validates that a workspace identifier is a canonical repository URL or logical name.
     * Strictly rejects host filesystem absolute paths or home directory paths.
     *
     * Valid examples:
     *  - "github.com/org/repo"
     *  - "git@github.com:org/repo.git"
     *  - "https://github.com/org/repo.git"
     *  - "urn:workspace:payment-service"
     *  - "auth-backend"
     *
     * Invalid examples (will throw [IllegalArgumentException]):
     *  - "/home/user/src/repo"
     *  - "~/projects/repo"
     *  - "file:///var/repo"
     *  - "C:\src\repo"
     */
    fun validateWorkspace(workspace: String?) {
        if (workspace == null || workspace.isBlank()) return

        val trimmed = workspace.trim()

        for (prefix in ABSOLUTE_PATH_PREFIXES) {
            if (trimmed.startsWith(prefix, ignoreCase = true)) {
                throw IllegalArgumentException(
                    "Workspace identifier '$workspace' is invalid: absolute host paths, home directories, and file:// URIs are forbidden. " +
                        "Use a canonical repository URL (e.g. 'github.com/org/repo') or a logical name (e.g. 'auth-service')."
                )
            }
        }

        if (WINDOWS_DRIVE_REGEX.containsMatchIn(trimmed)) {
            throw IllegalArgumentException(
                "Workspace identifier '$workspace' is invalid: Windows absolute drive paths are forbidden. " +
                    "Use a canonical repository URL or logical name."
            )
        }
    }

    /**
     * Validates that targetFile and contextFiles are relative paths to the resolved workspace root.
     */
    fun validateRelativePath(path: String?, fieldName: String = "Path") {
        if (path == null || path.isBlank()) return

        val trimmed = path.trim()

        for (prefix in ABSOLUTE_PATH_PREFIXES) {
            if (trimmed.startsWith(prefix, ignoreCase = true)) {
                throw IllegalArgumentException(
                    "$fieldName '$path' is invalid: must be a relative path to the workspace root, not an absolute path."
                )
            }
        }

        if (WINDOWS_DRIVE_REGEX.containsMatchIn(trimmed)) {
            throw IllegalArgumentException(
                "$fieldName '$path' is invalid: must be a relative path, not a Windows drive path."
            )
        }
    }
}
