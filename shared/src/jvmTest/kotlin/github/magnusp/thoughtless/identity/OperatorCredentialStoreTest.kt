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
    fun testEnvPrecedenceOverCliAndFile() {
        val store = OperatorCredentialStore(
            configDir = tempDir,
            envGetter = { key -> if (key == "GH_TOKEN") "gho_env_token_12345" else null },
            processRunner = { cmd ->
                if (cmd == listOf("gh", "auth", "token")) Pair(0, "gho_cli_token_99999")
                else Pair(0, "{\"login\":\"testuser\"}")
            }
        )

        val identity = store.resolveIdentity()
        assertEquals(OperatorAuthType.ENV_VARIABLE, identity.authType)
        assertEquals("gho_env_token_12345", identity.token)
        assertEquals("testuser", identity.username)
        assertTrue(identity.isAuthenticated)
        // Verify toString masks token
        assertFalse(identity.toString().contains("gho_env_token_12345"))
        assertTrue(identity.toString().contains("******"))
    }

    @Test
    fun testCliFallbackWhenNoEnv() {
        val store = OperatorCredentialStore(
            configDir = tempDir,
            envGetter = { null },
            processRunner = { cmd ->
                when (cmd) {
                    listOf("gh", "auth", "token") -> Pair(0, "gho_cli_token_99999")
                    listOf("gh", "api", "user", "-q", ".login") -> Pair(0, "cli_user")
                    else -> Pair(-1, "unknown")
                }
            }
        )

        val identity = store.resolveIdentity()
        assertEquals(OperatorAuthType.GITHUB_CLI, identity.authType)
        assertEquals("gho_cli_token_99999", identity.token)
        assertEquals("cli_user", identity.username)
        assertTrue(identity.isAuthenticated)
    }

    @Test
    fun testLocalConfigFileWhenNoEnvOrCli() {
        val store = OperatorCredentialStore(
            configDir = tempDir,
            envGetter = { null },
            processRunner = { Pair(1, "command not found") }
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

        // Clear credentials
        store.clearLocalCredentials()
        assertEquals(OperatorAuthType.NONE, store.resolveIdentity().authType)
    }
}
