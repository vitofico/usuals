package io.theficos.usuals.relay

class WebIdentityConfig(val origin: String, val oidc: OidcConfig?, val localEnabled: Boolean,
    val invitationOnly: Boolean = false, val directory: String = "all", val accountLimits: AccountLimits = AccountLimits()) {
    val secureCookies = java.net.URI(origin).scheme == "https"
    init {
        require(origin == canonicalOrigin(origin, !secureCookies))
        require(oidc == null || oidc.origin == origin)
        require(oidc != null || localEnabled)
        require(!invitationOnly || oidc != null)
        require(directory in setOf("all", "collaborators"))
    }
    fun accepts(issuer: String) = issuer == oidc?.issuer || localEnabled && issuer == LOCAL_ISSUER
    fun administrator(issuer: String, subject: String, localAdmin: Boolean) =
        if (issuer == LOCAL_ISSUER) localEnabled && localAdmin else oidc?.let { issuer == it.issuer && subject in it.adminSubjects } == true
    companion object {
        const val LOCAL_ISSUER = "urn:usuals:local"
        fun fromEnvironment(origin: String, environment: Map<String, String> = System.getenv()): WebIdentityConfig? {
            val local = environment["USUALS_LOCAL_AUTH_ENABLED"] ?: "false"
            require(local in setOf("true", "false")) { "invalid_local_auth_enabled" }
            val oidc = OidcConfig.fromEnvironment(origin, environment)
            val invited = environment["USUALS_INVITATION_ONLY"] ?: "false"
            require(invited in setOf("true", "false"))
            val limits = AccountLimits.fromEnvironment(environment)
            return if (oidc == null && local == "false") null else WebIdentityConfig(origin, oidc, local == "true", invited == "true", environment["USUALS_DIRECTORY"] ?: "all", limits)
        }
    }
}
