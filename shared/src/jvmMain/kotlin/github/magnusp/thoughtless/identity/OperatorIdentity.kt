package github.magnusp.thoughtless.identity

enum class OperatorAuthType {
    GITHUB_CLI,
    ENV_VARIABLE,
    LOCAL_CONFIG,
    NONE,
}

data class OperatorIdentity(
    val authType: OperatorAuthType,
    val username: String? = null,
    val token: String? = null,
    val sourceDescription: String = "No operator credentials found",
) {
    val isAuthenticated: Boolean
        get() = authType != OperatorAuthType.NONE && (!token.isNullOrBlank() || authType == OperatorAuthType.GITHUB_CLI)

    override fun toString(): String {
        val maskedToken = if (!token.isNullOrBlank()) "******" else "none"
        return "OperatorIdentity(authType=$authType, username=$username, token=$maskedToken, sourceDescription='$sourceDescription')"
    }
}
