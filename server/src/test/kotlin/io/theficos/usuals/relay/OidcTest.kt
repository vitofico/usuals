package io.theficos.usuals.relay

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.sync.Wire
import kotlinx.serialization.json.*
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

internal class OidcFixture(val now: () -> Long) {
    val issuer = "https://sso.example/application/o/usuals/"
    var subject = "member-subject"
    var nonce = "synthetic-nonce"
    var groups: JsonElement = buildJsonArray { add("usuals-users") }
    var discoveryOverride: JsonObject? = null
    var tokenOverride: String? = null
    var lastForm: Map<String, String>? = null
    var lastBasic: String? = null
    var calls = 0
    fun config(admins: Set<String> = setOf("admin-subject"), allowed: Set<String> = emptySet()) = OidcConfig(issuer, "usuals-web", "synthetic-client-secret", "https://usuals.invalid", admins, allowed)
    fun claims() = buildJsonObject {
        put("iss", issuer); put("sub", subject); put("aud", "usuals-web"); put("iat", now() / 1000); put("exp", now() / 1000 + 300)
        put("nonce", nonce); put("name", "Synthetic Member"); put("email", "member@example.invalid"); put("email_verified", true); put("groups", groups)
    }
    fun jwt(claims: JsonObject = claims(), header: JsonObject = buildJsonObject { put("alg", "RS256"); put("kid", "fixture-key") }): String {
        val content = encode(header.toString().toByteArray()) + "." + encode(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(content.toByteArray()) }.sign()
        return "$content.${encode(signature)}"
    }
    fun keys() = buildJsonObject { put("keys", buildJsonArray { add(buildJsonObject {
        put("kid", "fixture-key"); put("kty", "RSA"); put("alg", "RS256"); put("use", "sig")
        val key = pair.public as RSAPublicKey
        put("n", encode(key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()))
        put("e", encode(key.publicExponent.toByteArray()))
    }) }) }
    fun metadata() = buildJsonObject {
        put("issuer", issuer); put("authorization_endpoint", "https://sso.example/application/o/authorize/")
        put("token_endpoint", "https://sso.example/application/o/token/"); put("jwks_uri", issuer + "jwks/")
        put("response_types_supported", buildJsonArray { add("code") }); put("code_challenge_methods_supported", buildJsonArray { add("S256") })
        put("id_token_signing_alg_values_supported", buildJsonArray { add("RS256") }); put("token_endpoint_auth_methods_supported", buildJsonArray { add("client_secret_basic") })
    }
    val transport = OidcTransport { url, form, basic ->
        calls++
        when {
            url.path.endsWith("openid-configuration") -> discoveryOverride ?: metadata()
            url.path.endsWith("/token/") -> { lastForm = form; lastBasic = basic; buildJsonObject { put("id_token", tokenOverride ?: jwt()) } }
            url.path.endsWith("/jwks/") -> keys()
            else -> error("unexpected_test_endpoint")
        }
    }
    fun authorize(start: WebLoginStart): Map<String, String> = query(start.location).also { nonce = it.getValue("nonce") }
    companion object {
        private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
        fun query(location: String): Map<String, String> = URI(location).rawQuery.split('&').associate { item ->
            val parts = item.split('=', limit = 2); URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts[1], Charsets.UTF_8)
        }
    }
}

class OidcTest {
    private val now = 1_800_000_000_000L
    private val fixture = OidcFixture { now }
    private val config = fixture.config()
    private fun denied(action: () -> Any?) = assertThrows(RelayError::class.java) { action() }
    private fun JsonObject.changed(name: String, value: JsonElement) = JsonObject(toMutableMap().also { it[name] = value })

    @Test fun secureCodeExchangeUsesExactCallbackBasicCredentialsAndS256Verifier() {
        val client = OidcClient(config, fixture.transport) { now }
        val location = client.authorization(client.metadata(), "state", fixture.nonce, "synthetic-verifier")
        val query = OidcFixture.query(location)
        assertThat(query["code_challenge"]).isEqualTo(digestText("synthetic-verifier"))
        assertThat(query["redirect_uri"]).isEqualTo("https://usuals.invalid/auth/callback")
        assertThat(query["scope"]).isEqualTo("openid profile email")
        assertThat(query["code_challenge_method"]).isEqualTo("S256")
        assertThat(query).doesNotContainKey("client_secret")
        val identity = client.complete("synthetic-code", "synthetic-verifier", digest(fixture.nonce))
        assertThat(identity.subject).isEqualTo(fixture.subject)
        assertThat(fixture.lastForm!!["code_verifier"]).isEqualTo("synthetic-verifier")
        assertThat(fixture.lastBasic).isEqualTo(Base64.getEncoder().encodeToString("usuals-web:synthetic-client-secret".toByteArray()))
    }
    @Test fun idTokenValidationRejectsAuthorityConfusionTimeNonceSignatureAndAlgorithmFailures() {
        val client = OidcClient(config, fixture.transport) { now }
        val valid = fixture.claims()
        val invalid = listOf(
            valid.changed("iss", JsonPrimitive("https://other.example/")), valid.changed("aud", JsonPrimitive("other-client")),
            valid.changed("aud", buildJsonArray { add("usuals-web"); add("other") }), valid.changed("azp", JsonPrimitive("other-client")),
            valid.changed("aud", buildJsonArray { add("usuals-web"); add("usuals-web") }), valid.changed("nonce", JsonPrimitive("other-nonce")),
            valid.changed("iat", JsonPrimitive(now / 1000 + 31)), valid.changed("iat", JsonPrimitive(now / 1000 - 601)),
            valid.changed("exp", JsonPrimitive(now / 1000)), valid.changed("exp", JsonPrimitive(now / 1000 + 86401)),
            valid.changed("exp", JsonPrimitive("1800000300")), valid.changed("nbf", JsonPrimitive(now / 1000 + 31)), valid.changed("sub", JsonPrimitive("")),
        )
        invalid.forEach { claims -> assertThat(denied { client.verify(fixture.jwt(claims), fixture.keys(), digest(fixture.nonce)) }.code).isEqualTo("unauthenticated") }
        for (alg in listOf("none", "HS256", "PS256")) denied { client.verify(fixture.jwt(header = buildJsonObject { put("alg", alg); put("kid", "fixture-key") }), fixture.keys(), digest(fixture.nonce)) }
        denied { client.verify(fixture.jwt(header = buildJsonObject { put("alg", "RS256"); put("kid", "fixture-key"); put("jku", "https://other.example") }), fixture.keys(), digest(fixture.nonce)) }
        denied { client.verify(fixture.jwt().dropLast(10) + "AAAAAAAAAA", fixture.keys(), digest(fixture.nonce)) }
        denied { client.verify(fixture.jwt(), buildJsonObject { put("keys", buildJsonArray { add(fixture.keys()["keys"]!!.jsonArray[0]); add(fixture.keys()["keys"]!!.jsonArray[0]) }) }, digest(fixture.nonce)) }
    }
    @Test fun issuerDiscoveryRejectsForeignEndpointsAndMissingSecureCapabilities() {
        val client = OidcClient(config, fixture.transport) { now }
        val valid = fixture.metadata()
        for ((name, value) in listOf("issuer" to JsonPrimitive("https://other.example/"), "token_endpoint" to JsonPrimitive("https://other.example/token"),
            "jwks_uri" to JsonPrimitive("http://sso.example/keys"), "authorization_endpoint" to JsonPrimitive("https://sso.example/authorize?redirect=x"),
            "code_challenge_methods_supported" to buildJsonArray { add("plain") }, "id_token_signing_alg_values_supported" to buildJsonArray { add("HS256") })) {
            fixture.discoveryOverride = valid.changed(name, value)
            denied { client.metadata() }
        }
    }
    @Test fun discoverySupportsExactIssuersWithAndWithoutTrailingSlash() {
        for ((issuer, discovery) in listOf(
            "https://sso.example" to "https://sso.example/.well-known/openid-configuration",
            "https://sso.example/" to "https://sso.example/.well-known/openid-configuration",
            "https://sso.example/realms/usuals" to "https://sso.example/realms/usuals/.well-known/openid-configuration",
            fixture.issuer to "https://sso.example/application/o/usuals/.well-known/openid-configuration",
        )) {
            val exact = OidcConfig(issuer, config.clientId, config.clientSecret, config.origin)
            val requested = mutableListOf<String>()
            var metadataIssuer = issuer
            val transport = OidcTransport { url, form, basic ->
                requested += url.toString()
                if (url.path.endsWith("openid-configuration")) fixture.metadata().changed("issuer", JsonPrimitive(metadataIssuer))
                else fixture.transport.json(url, form, basic)
            }
            val client = OidcClient(exact, transport) { now }
            fixture.tokenOverride = fixture.jwt(fixture.claims().changed("iss", JsonPrimitive(issuer)))
            assertThat(client.complete("synthetic-code", "synthetic-verifier", digest(fixture.nonce)).subject).isEqualTo(fixture.subject)
            assertThat(requested.first()).isEqualTo(discovery)

            // Discovery URL construction must never change the identity namespace or token issuer check.
            val different = if (issuer.endsWith('/')) issuer.dropLast(1) else "$issuer/"
            metadataIssuer = different
            assertThat(denied { client.metadata() }.code).isEqualTo("identity_unavailable")
            assertThat(denied { client.verify(fixture.jwt(fixture.claims().changed("iss", JsonPrimitive(different))), fixture.keys(), digest(fixture.nonce)) }.code).isEqualTo("unauthenticated")
        }
    }
    @Test fun configuredGroupsAreStrictSignedArraysAndEmailIsOnlyAViewOfVerifiedClaims() {
        val client = OidcClient(fixture.config(allowed = setOf("usuals-users")), fixture.transport) { now }
        assertThat(client.verify(fixture.jwt(), fixture.keys(), digest(fixture.nonce)).email).isEqualTo("member@example.invalid")
        fixture.groups = JsonPrimitive("usuals-users")
        assertThat(denied { client.verify(fixture.jwt(), fixture.keys(), digest(fixture.nonce)) }.code).isEqualTo("forbidden")
        fixture.groups = buildJsonArray { add("other-group") }
        assertThat(denied { client.verify(fixture.jwt(), fixture.keys(), digest(fixture.nonce)) }.code).isEqualTo("forbidden")
        fixture.groups = buildJsonArray { add("usuals-users") }
        val unverified = fixture.claims().changed("email_verified", JsonPrimitive(false))
        assertThat(client.verify(fixture.jwt(unverified), fixture.keys(), digest(fixture.nonce)).email).isNull()
    }
    @Test fun oidcIsExplicitlyDisabledAndConfigurationRejectsInvalidAuthority() {
        assertThat(OidcConfig.fromEnvironment(config.origin, emptyMap())).isNull()
        assertThrows(IllegalArgumentException::class.java) { OidcConfig.fromEnvironment(config.origin, mapOf("USUALS_OIDC_ENABLED" to "yes")) }
        for (issuer in listOf("http://sso.example/", "https://user@sso.example/", "https://sso.example/#fragment", "https://sso.example/a/../b/", "https://sso.example/?key=x")) {
            assertThrows(IllegalArgumentException::class.java) { OidcConfig(issuer, "client", "secret", config.origin) }
        }
    }
}
