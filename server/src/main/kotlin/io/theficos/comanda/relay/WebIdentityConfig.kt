package io.theficos.comanda.relay

class WebIdentityConfig(val origin: String, val oidc: OidcConfig?, val localEnabled: Boolean) {
    val secureCookies = java.net.URI(origin).scheme == "https"
    init {
        require(origin == canonicalOrigin(origin, !secureCookies))
        require(oidc == null || oidc.origin == origin)
        require(oidc != null || localEnabled)
    }
    fun accepts(issuer: String) = issuer == oidc?.issuer || localEnabled && issuer == LOCAL_ISSUER
    fun administrator(issuer: String, subject: String, localAdmin: Boolean) =
        if (issuer == LOCAL_ISSUER) localEnabled && localAdmin else oidc?.let { issuer == it.issuer && subject in it.adminSubjects } == true
    companion object {
        const val LOCAL_ISSUER = "urn:aurea:local"
        fun fromEnvironment(origin: String, environment: Map<String, String> = System.getenv()): WebIdentityConfig? {
            val local = environment["COMANDA_LOCAL_AUTH_ENABLED"] ?: "false"
            require(local in setOf("true", "false")) { "invalid_local_auth_enabled" }
            val oidc = OidcConfig.fromEnvironment(origin, environment)
            return if (oidc == null && local == "false") null else WebIdentityConfig(origin, oidc, local == "true")
        }
    }
}
