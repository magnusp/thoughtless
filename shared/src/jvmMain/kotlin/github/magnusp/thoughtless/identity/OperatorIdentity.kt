package github.magnusp.thoughtless.identity

enum class OperatorAuthType {
    GITHUB_CLI,
    ENV_VARIABLE,
    LOCAL_CONFIG,
    NONE,
}

enum class SelfCheckStatus {
    VERIFIED,
    INVALID,
    UNCHECKED,
}

data class IdentitySelfCheck(
    val status: SelfCheckStatus = SelfCheckStatus.UNCHECKED,
    val login: String? = null,
    val name: String? = null,
    val scopes: List<String> = emptyList(),
    val errorMessage: String? = null,
    val checkedAt: Long = 0L,
) {
    val isVerified: Boolean get() = status == SelfCheckStatus.VERIFIED
}

data class OperatorIdentity(
    val authType: OperatorAuthType,
    val username: String? = null,
    val token: String? = null,
    val sourceDescription: String = "No operator credentials found",
    val selfCheck: IdentitySelfCheck = IdentitySelfCheck(),
) {
    val isAuthenticated: Boolean
        get() = authType != OperatorAuthType.NONE && (!token.isNullOrBlank() || authType == OperatorAuthType.GITHUB_CLI)

    override fun toString(): String {
        val maskedToken = if (!token.isNullOrBlank()) "******" else "none"
        return "OperatorIdentity(authType=$authType, username=$username, token=$maskedToken, sourceDescription='$sourceDescription', selfCheck=$selfCheck)"
    }
}
