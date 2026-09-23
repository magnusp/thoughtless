package github.magnusp.thoughtless.identity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

@Serializable
internal data class StoredOperatorConfig(
    val token: String? = null,
    val username: String? = null,
)

class OperatorCredentialStore(
    private val configDir: File = File(System.getProperty("user.home"), ".thoughtless"),
    private val envGetter: (String) -> String? = { System.getenv(it) },
    private val processRunner: (List<String>) -> Pair<Int, String> = { cmd ->
        try {
            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val text = proc.inputStream.bufferedReader().use { it.readText().trim() }
            val exited = proc.waitFor(3, TimeUnit.SECONDS)
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
    private val configFile = File(configDir, "credentials.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * Resolves the current operator identity following the precedence order:
     * 1. Environment variable (GH_TOKEN, GITHUB_TOKEN)
     * 2. GitHub CLI (gh auth token + gh api user)
     * 3. Local configuration (~/.thoughtless/credentials.json)
     * 4. None
     */
    fun resolveIdentity(): OperatorIdentity {
        // 1. Environment variables
        val envToken = envGetter("GH_TOKEN")?.ifBlank { null }
            ?: envGetter("GITHUB_TOKEN")?.ifBlank { null }
        if (!envToken.isNullOrBlank()) {
            val username = fetchUsernameForToken(envToken)
            return OperatorIdentity(
                authType = OperatorAuthType.ENV_VARIABLE,
                username = username,
                token = envToken,
                sourceDescription = "Environment variable (GH_TOKEN/GITHUB_TOKEN)",
            )
        }

        // 2. GitHub CLI
        val (ghTokenExit, ghTokenOut) = processRunner(listOf("gh", "auth", "token"))
        if (ghTokenExit == 0 && ghTokenOut.isNotBlank() && !ghTokenOut.startsWith("no token found")) {
            val token = ghTokenOut.trim()
            val (userExit, userOut) = processRunner(listOf("gh", "api", "user", "-q", ".login"))
            val username = if (userExit == 0 && userOut.isNotBlank()) userOut.trim() else null
            return OperatorIdentity(
                authType = OperatorAuthType.GITHUB_CLI,
                username = username,
                token = token,
                sourceDescription = "GitHub CLI (`gh auth token` as ${username ?: "authenticated user"})",
            )
        }

        // 3. Local credentials file
        if (configFile.exists() && configFile.canRead()) {
            try {
                val content = configFile.readText()
                val config = json.decodeFromString<StoredOperatorConfig>(content)
                if (!config.token.isNullOrBlank()) {
                    return OperatorIdentity(
                        authType = OperatorAuthType.LOCAL_CONFIG,
                        username = config.username,
                        token = config.token,
                        sourceDescription = "Local confidential config (~/.thoughtless/credentials.json)",
                    )
                }
            } catch (_: Exception) {
                // Ignore parse errors from corrupted local file
            }
        }

        return OperatorIdentity(
            authType = OperatorAuthType.NONE,
            sourceDescription = "No operator credentials found",
        )
    }

    /**
     * Stores credentials strictly into local configuration file with 0600 file permissions.
     * This is NEVER replicated or uploaded.
     */
    fun saveLocalCredentials(token: String, username: String? = null) {
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
        val config = StoredOperatorConfig(token = token.trim(), username = username?.trim())
        configFile.writeText(json.encodeToString(StoredOperatorConfig.serializer(), config))

        try {
            // Set file permission to 0600 on POSIX platforms
            val perms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            Files.setPosixFilePermissions(configFile.toPath(), perms)
        } catch (_: Exception) {
            // Non-posix fallback
            configFile.setReadable(false, false)
            configFile.setReadable(true, true)
            configFile.setWritable(false, false)
            configFile.setWritable(true, true)
        }
    }

    /**
     * Clears local credentials file if present.
     */
    fun clearLocalCredentials() {
        if (configFile.exists()) {
            configFile.delete()
        }
    }

    private fun fetchUsernameForToken(token: String): String? {
        val (exit, out) = processRunner(listOf("curl", "-s", "-H", "Authorization: Bearer $token", "-H", "User-Agent: thoughtless", "https://api.github.com/user"))
        if (exit == 0 && out.contains("\"login\":")) {
            val match = Regex("\"login\"\\s*:\\s*\"([^\"]+)\"").find(out)
            return match?.groupValues?.get(1)
        }
        return null
    }
}
