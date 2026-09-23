package github.magnusp.thoughtless.domain.validation

import kotlin.test.Test
import kotlin.test.assertFailsWith

class WorkspaceValidatorTest {

    @Test
    fun testValidCanonicalWorkspaces() {
        val validWorkspaces = listOf(
            null,
            "",
            "   ",
            "github.com/org/repo",
            "git@github.com:org/repo.git",
            "https://github.com/org/repo.git",
            "urn:workspace:payment-service",
            "auth-backend",
            "project_alpha-123",
        )

        for (ws in validWorkspaces) {
            WorkspaceValidator.validateWorkspace(ws)
        }
    }

    @Test
    fun testRejectsAbsolutePathPrefixes() {
        val invalidWorkspaces = listOf(
            "/home/user/src/repo",
            "/var/app",
            "\\Windows\\path",
            "~/projects/repo",
            "~/.thoughtless",
            "file:///home/user/src",
            "file:/var/log",
            "C:\\src\\repo",
            "D:/projects/test",
        )

        for (invalid in invalidWorkspaces) {
            assertFailsWith<IllegalArgumentException>("Expected $invalid to be rejected") {
                WorkspaceValidator.validateWorkspace(invalid)
            }
        }
    }

    @Test
    fun testValidRelativePaths() {
        val validPaths = listOf(
            null,
            "",
            "src/main/kotlin/App.kt",
            "docs/specs/auth.md",
            "build.gradle.kts",
        )

        for (path in validPaths) {
            WorkspaceValidator.validateRelativePath(path)
        }
    }

    @Test
    fun testRejectsAbsolutePathsForFiles() {
        val invalidPaths = listOf(
            "/src/main/kotlin/App.kt",
            "~/docs/spec.md",
            "file:///tmp/test.kt",
            "C:\\src\\App.kt",
        )

        for (invalid in invalidPaths) {
            assertFailsWith<IllegalArgumentException>("Expected $invalid to be rejected as relative path") {
                WorkspaceValidator.validateRelativePath(invalid)
            }
        }
    }
}
