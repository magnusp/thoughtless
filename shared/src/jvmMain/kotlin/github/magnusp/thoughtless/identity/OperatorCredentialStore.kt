package github.magnusp.thoughtless.identity

import github.magnusp.thoughtless.util.currentTimeMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
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
            val exited = proc.waitFor(5, TimeUnit.SECONDS)
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
     *
     * Automatically executes self-checks if performSelfCheck is true.
     */
    fun resolveIdentity(performSelfCheck: Boolean = true): OperatorIdentity {
        // 1. Environment variables
        val envToken = envGetter("GH_TOKEN")?.ifBlank { null }
            ?: envGetter("GITHUB_TOKEN")?.ifBlank { null }
        if (!envToken.isNullOrBlank()) {
            val selfCheck = if (performSelfCheck) performTokenSelfCheck(envToken) else IdentitySelfCheck()
            return OperatorIdentity(
                authType = OperatorAuthType.ENV_VARIABLE,
                username = selfCheck.login,
                token = envToken,
                sourceDescription = "Environment variable (GH_TOKEN/GITHUB_TOKEN)",
                selfCheck = selfCheck,
            )
        }

        // 2. GitHub CLI
        val (ghTokenExit, ghTokenOut) = processRunner(listOf("gh", "auth", "token"))
        if (ghTokenExit == 0 && ghTokenOut.isNotBlank() && !ghTokenOut.startsWith("no token found")) {
            val token = ghTokenOut.trim()
            val selfCheck = if (performSelfCheck) performCliSelfCheck() else IdentitySelfCheck()
            return OperatorIdentity(
                authType = OperatorAuthType.GITHUB_CLI,
                username = selfCheck.login,
                token = token,
                sourceDescription = "GitHub CLI (`gh auth token` as ${selfCheck.login ?: "authenticated user"})",
                selfCheck = selfCheck,
            )
        }

        // 3. Local credentials file
        if (configFile.exists() && configFile.canRead()) {
            try {
                val content = configFile.readText()
                val config = json.decodeFromString<StoredOperatorConfig>(content)
                if (!config.token.isNullOrBlank()) {
                    val selfCheck = if (performSelfCheck) performTokenSelfCheck(config.token) else IdentitySelfCheck()
                    return OperatorIdentity(
                        authType = OperatorAuthType.LOCAL_CONFIG,
                        username = selfCheck.login ?: config.username,
                        token = config.token,
                        sourceDescription = "Local confidential config (~/.thoughtless/credentials.json)",
                        selfCheck = selfCheck,
                    )
                }
            } catch (_: Exception) {
                // Ignore parse errors from corrupted local file
            }
        }

        return OperatorIdentity(
            authType = OperatorAuthType.NONE,
            sourceDescription = "No operator credentials found",
            selfCheck = IdentitySelfCheck(status = SelfCheckStatus.UNCHECKED),
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

    /**
     * Performs self-check via GitHub CLI (`gh api -i user`)
     */
    fun performCliSelfCheck(): IdentitySelfCheck {
        val (exit, out) = processRunner(listOf("gh", "api", "-i", "user"))
        return parseSelfCheckResponse(exit, out)
    }

    /**
     * Performs self-check via direct API call using token (`curl -i ...`)
     */
    fun performTokenSelfCheck(token: String): IdentitySelfCheck {
        val (exit, out) = processRunner(listOf(
            "curl", "-s", "-i",
            "-H", "Authorization: Bearer $token",
            "-H", "User-Agent: thoughtless",
            "https://api.github.com/user"
        ))
        return parseSelfCheckResponse(exit, out)
    }

    private fun parseSelfCheckResponse(exitCode: Int, responseText: String): IdentitySelfCheck {
        val now = currentTimeMillis()
        if (exitCode != 0 || responseText.isBlank()) {
            return IdentitySelfCheck(
                status = SelfCheckStatus.INVALID,
                errorMessage = "Failed to communicate with GitHub API (exit code $exitCode): $responseText",
                checkedAt = now,
            )
        }

        // Parse HTTP status if present in headers
        val statusLine = responseText.lines().firstOrNull { it.startsWith("HTTP/") }
        if (statusLine != null && (statusLine.contains("401") || statusLine.contains("403"))) {
            return IdentitySelfCheck(
                status = SelfCheckStatus.INVALID,
                errorMessage = "GitHub API authorization rejected: $statusLine",
                checkedAt = now,
            )
        }

        // Parse x-oauth-scopes header
        val scopes = responseText.lines()
            .firstOrNull { it.startsWith("x-oauth-scopes:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        // Parse login and name
        val loginMatch = Regex("\"login\"\\s*:\\s*\"([^\"]+)\"").find(responseText)
        val nameMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(responseText)

        val login = loginMatch?.groupValues?.get(1)
        val name = nameMatch?.groupValues?.get(1)

        return if (login != null) {
            IdentitySelfCheck(
                status = SelfCheckStatus.VERIFIED,
                login = login,
                name = name,
                scopes = scopes,
                checkedAt = now,
            )
        } else {
            IdentitySelfCheck(
                status = SelfCheckStatus.INVALID,
                errorMessage = "Could not parse authenticated user info from response",
                checkedAt = now,
            )
        }
    }
}
