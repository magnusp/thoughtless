package github.magnusp.thoughtless.identity

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class OperatorCredentialStoreTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("thoughtless-cred-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testEnvPrecedenceOverCliAndFileWithSelfCheck() {
        val store = OperatorCredentialStore(
            configDir = tempDir,
            envGetter = { key -> if (key == "GH_TOKEN") "gho_env_token_12345" else null },
            processRunner = { cmd ->
                if (cmd == listOf("gh", "auth", "token")) {
                    Pair(0, "gho_cli_token_99999")
                } else if (cmd.contains("https://api.github.com/user")) {
                    Pair(0, "HTTP/2 200\nx-oauth-scopes: repo, read:org\n\n{\"login\":\"testuser\",\"name\":\"Test User\"}")
                } else {
                    Pair(0, "{}")
                }
            }
        )

        val identity = store.resolveIdentity()
        assertEquals(OperatorAuthType.ENV_VARIABLE, identity.authType)
        assertEquals("gho_env_token_12345", identity.token)
        assertEquals("testuser", identity.username)
        assertTrue(identity.isAuthenticated)

        // Verify Self-Check
        assertEquals(SelfCheckStatus.VERIFIED, identity.selfCheck.status)
        assertEquals("testuser", identity.selfCheck.login)
        assertEquals("Test User", identity.selfCheck.name)
        assertEquals(listOf("repo", "read:org"), identity.selfCheck.scopes)

        // Verify toString masks token
        assertFalse(identity.toString().contains("gho_env_token_12345"))
        assertTrue(identity.toString().contains("******"))
    }

    @Test
    fun testCliFallbackWhenNoEnvWithSelfCheck() {
        val store = OperatorCredentialStore(
            configDir = tempDir,
            envGetter = { null },
            processRunner = { cmd ->
                when (cmd) {
                    listOf("gh", "auth", "token") -> Pair(0, "gho_cli_token_99999")
                    listOf("gh", "api", "-i", "user") -> Pair(
                        0,
                        "HTTP/2 200\nx-oauth-scopes: repo, gist\n\n{\"login\":\"cli_user\",\"name\":\"CLI User\"}"
                    )
                    else -> Pair(-1, "unknown")
                }
            }
        )

        val identity = store.resolveIdentity()
        assertEquals(OperatorAuthType.GITHUB_CLI, identity.authType)
        assertEquals("gho_cli_token_99999", identity.token)
        assertEquals("cli_user", identity.username)
        assertTrue(identity.isAuthenticated)
        assertEquals(SelfCheckStatus.VERIFIED, identity.selfCheck.status)
        assertEquals(listOf("repo", "gist"), identity.selfCheck.scopes)
    }

    @Test
    fun testSelfCheckFailureOnInvalidToken() {
        val store = OperatorCredentialStore(
            configDir = tempDir,
            envGetter = { key -> if (key == "GH_TOKEN") "invalid_token" else null },
            processRunner = { _ ->
                Pair(0, "HTTP/2 401 Unauthorized\n\n{\"message\":\"Bad credentials\"}")
            }
        )

        val identity = store.resolveIdentity()
        assertEquals(OperatorAuthType.ENV_VARIABLE, identity.authType)
        assertEquals(SelfCheckStatus.INVALID, identity.selfCheck.status)
        assertTrue(identity.selfCheck.errorMessage?.contains("authorization rejected") == true)
    }

    @Test
    fun testLocalConfigFileWhenNoEnvOrCli() {
        val store = OperatorCredentialStore(
            configDir = tempDir,
            envGetter = { null },
            processRunner = { cmd ->
                if (cmd.contains("https://api.github.com/user")) {
                    Pair(0, "HTTP/2 200\nx-oauth-scopes: repo\n\n{\"login\":\"file_operator\"}")
                } else {
                    Pair(1, "command not found")
                }
            }
        )

        // Initially none
        assertEquals(OperatorAuthType.NONE, store.resolveIdentity().authType)

        // Save confidential config
        store.saveLocalCredentials("local_pat_secret", "file_operator")

        val identity = store.resolveIdentity()
        assertEquals(OperatorAuthType.LOCAL_CONFIG, identity.authType)
        assertEquals("local_pat_secret", identity.token)
        assertEquals("file_operator", identity.username)
        assertTrue(identity.isAuthenticated)
        assertEquals(SelfCheckStatus.VERIFIED, identity.selfCheck.status)

        // Clear credentials
        store.clearLocalCredentials()
        assertEquals(OperatorAuthType.NONE, store.resolveIdentity().authType)
    }
}
