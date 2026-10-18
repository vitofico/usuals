package io.theficos.usuals.relay

import io.theficos.usuals.sync.Wire
import kotlinx.serialization.json.*
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

/** Operator configuration only. None of these URLs originate in a browser request. */
class OidcConfig(
    val issuer: String,
    val clientId: String,
    internal val clientSecret: String,
    val origin: String,
    val adminSubjects: Set<String> = emptySet(),
    val allowedGroups: Set<String> = emptySet(),
) {
    val callback = "$origin/auth/callback"
    val secureCookies = URI(origin).scheme == "https"
    init {
        require(issuer.length <= 2048 && issuer.endsWith('/')) { "invalid_oidc_issuer" }
        endpoint(issuer)
        require(URI(issuer).rawQuery == null)
        require(clientId.isNotBlank() && clientId.length <= 256 && clientId.none(Char::isISOControl)) { "invalid_oidc_client" }
        require(clientSecret.isNotBlank() && clientSecret.length <= 8192 && clientSecret.none(Char::isISOControl)) { "invalid_oidc_secret" }
        require(origin == canonicalOrigin(origin, !secureCookies)) { "invalid_oidc_origin" }
        require(adminSubjects.size <= 100 && adminSubjects.all { it.isNotBlank() && it.length <= 255 && it.none(Char::isISOControl) }) { "invalid_oidc_admins" }
        require(allowedGroups.size <= 100 && allowedGroups.all { it.isNotBlank() && it.length <= 255 && it.none(Char::isISOControl) }) { "invalid_oidc_groups" }
    }
    fun endpoint(value: String): URI {
        val url = URI(value)
        require(value.length <= 2048 && url.scheme == "https" && !url.host.isNullOrBlank() && url.userInfo == null && url.fragment == null && url.rawQuery == null) { "invalid_oidc_endpoint" }
        require(url.normalize() == url && !value.contains('\\')) { "invalid_oidc_endpoint" }
        val expected = URI(issuer)
        require(url.host == expected.host && url.port == expected.port) { "invalid_oidc_endpoint" }
        return url
    }
    companion object {
        fun fromEnvironment(origin: String, environment: Map<String, String> = System.getenv()): OidcConfig? {
            val enabled = environment["USUALS_OIDC_ENABLED"] ?: "false"
            require(enabled in setOf("true", "false")) { "invalid_oidc_enabled" }
            if (enabled == "false") return null
            fun required(name: String) = requireNotNull(environment[name]) { "missing_config:$name" }
            fun values(name: String): Set<String> {
                val raw = environment[name] ?: return emptySet()
                require(raw.length <= 32768) { "invalid_oidc_set" }
                val array = Wire.strictJson(raw.toByteArray(), 32768) as? JsonArray ?: error("invalid_oidc_set")
                val result = array.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: error("invalid_oidc_set") }
                require(result.distinct().size == result.size) { "invalid_oidc_set" }
                return result.toSet()
            }
            val path = Path.of(required("USUALS_OIDC_CLIENT_SECRET_FILE"))
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && Files.size(path) in 1..8193) { "invalid_oidc_secret_file" }
            return OidcConfig(required("USUALS_OIDC_ISSUER"), required("USUALS_OIDC_CLIENT_ID"), Files.readString(path).trim(), origin,
                values("USUALS_OIDC_ADMIN_SUBJECTS"), values("USUALS_OIDC_ALLOWED_GROUPS"))
        }
    }
}

internal data class OidcIdentity(val subject: String, val displayName: String, val email: String?)
internal data class OidcMetadata(val authorization: String, val token: String, val jwks: String)
internal fun interface OidcTransport { fun json(url: URI, form: Map<String, String>?, basic: String?): JsonObject }

internal class JdkOidcTransport : OidcTransport {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build()
    override fun json(url: URI, form: Map<String, String>?, basic: String?): JsonObject = try {
        val request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(4)).header("Accept", "application/json")
        if (form == null) request.GET() else request.header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form.entries.joinToString("&") { "${encodeForm(it.key)}=${encodeForm(it.value)}" }))
        if (basic != null) request.header("Authorization", "Basic $basic")
        val response = client.send(request.build()) { LimitedOidcBody(131072) }
        expect(response.statusCode() == 200 && response.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim() == "application/json", "identity_unavailable", 503)
        Wire.strictJson(response.body(), 131072) as? JsonObject ?: reject("identity_unavailable", 503)
    } catch (error: Exception) {
        if (error is RelayError) throw error
        reject("identity_unavailable", 503)
    }
}

private class LimitedOidcBody(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val output = java.io.ByteArrayOutputStream()
    private lateinit var subscription: Flow.Subscription
    override fun getBody(): CompletionStage<ByteArray> = result
    override fun onSubscribe(value: Flow.Subscription) { subscription = value; subscription.request(1) }
    override fun onNext(values: List<ByteBuffer>) {
        try {
            for (value in values) {
                require(value.remaining() <= limit - output.size())
                val bytes = ByteArray(value.remaining()); value.get(bytes); output.write(bytes)
            }
            subscription.request(1)
        } catch (_: Exception) { subscription.cancel(); result.completeExceptionally(IllegalArgumentException("identity_unavailable")) }
    }
    override fun onError(error: Throwable) { result.completeExceptionally(error) }
    override fun onComplete() { result.complete(output.toByteArray()) }
}

internal class OidcClient(private val config: OidcConfig, private val transport: OidcTransport = JdkOidcTransport(), private val clock: () -> Long = System::currentTimeMillis) {
    fun metadata(): OidcMetadata {
        val document = transport.json(config.endpoint(config.issuer + ".well-known/openid-configuration"), null, null)
        expect(document.text("issuer") == config.issuer, "identity_unavailable", 503)
        fun supports(name: String, required: String) {
            val values = document[name] as? JsonArray ?: reject("identity_unavailable", 503)
            expect(values.any { it is JsonPrimitive && it.isString && it.content == required }, "identity_unavailable", 503)
        }
        supports("response_types_supported", "code")
        supports("code_challenge_methods_supported", "S256")
        supports("id_token_signing_alg_values_supported", "RS256")
        supports("token_endpoint_auth_methods_supported", "client_secret_basic")
        fun endpoint(name: String) = document.text(name).also { try { config.endpoint(it) } catch (_: Exception) { reject("identity_unavailable", 503) } }
        return OidcMetadata(endpoint("authorization_endpoint"), endpoint("token_endpoint"), endpoint("jwks_uri"))
    }
    fun authorization(metadata: OidcMetadata, state: String, nonce: String, verifier: String): String = metadata.authorization + "?" + linkedMapOf(
        "client_id" to config.clientId, "redirect_uri" to config.callback, "response_type" to "code", "scope" to "openid profile email",
        "state" to state, "nonce" to nonce, "code_challenge" to digestText(verifier), "code_challenge_method" to "S256",
    ).entries.joinToString("&") { "${encodeForm(it.key)}=${encodeForm(it.value)}" }
    fun complete(code: String, verifier: String, nonceHash: ByteArray): OidcIdentity {
        expect(code.isNotBlank() && code.length <= 8192 && code.none(Char::isISOControl), "unauthenticated", 401)
        val metadata = metadata()
        val basic = Base64.getEncoder().encodeToString("${encodeForm(config.clientId)}:${encodeForm(config.clientSecret)}".toByteArray())
        val token = transport.json(config.endpoint(metadata.token), mapOf("grant_type" to "authorization_code", "code" to code,
            "redirect_uri" to config.callback, "client_id" to config.clientId, "code_verifier" to verifier), basic)
        val compact = token.text("id_token")
        expect(compact.length <= 32768, "unauthenticated", 401)
        val keys = transport.json(config.endpoint(metadata.jwks), null, null)
        return verify(compact, keys, nonceHash)
    }
    internal fun verify(compact: String, keys: JsonObject, nonceHash: ByteArray): OidcIdentity = try {
        val parts = compact.split('.')
        expect(parts.size == 3 && compact.length <= 32768, "unauthenticated", 401)
        val header = strictObject(decodePart(parts[0]))
        expect(header.text("alg") == "RS256" && header["crit"] == null && header["jku"] == null && header["x5u"] == null, "unauthenticated", 401)
        val kid = header.text("kid").also { expect(it.isNotBlank() && it.length <= 255, "unauthenticated", 401) }
        val candidates = keys["keys"] as? JsonArray ?: reject("unauthenticated", 401)
        expect(candidates.size <= 32, "unauthenticated", 401)
        val key = candidates.filterIsInstance<JsonObject>().filter { (it["kid"] as? JsonPrimitive)?.content == kid }.singleOrNull() ?: reject("unauthenticated", 401)
        expect(key.text("kty") == "RSA" && (key["alg"] == null || key.text("alg") == "RS256") && (key["use"] == null || key.text("use") == "sig"), "unauthenticated", 401)
        key["key_ops"]?.let { value -> expect(value is JsonArray && value.size == 1 && value[0].jsonPrimitive.content == "verify", "unauthenticated", 401) }
        val modulus = BigInteger(1, decodePart(key.text("n")))
        val exponent = BigInteger(1, decodePart(key.text("e")))
        expect(modulus.bitLength() in 2048..4096 && exponent == BigInteger.valueOf(65537), "unauthenticated", 401)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val signature = Signature.getInstance("SHA256withRSA").apply { initVerify(publicKey); update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)) }
        expect(signature.verify(decodePart(parts[2])), "unauthenticated", 401)
        val claims = strictObject(decodePart(parts[1]))
        expect(claims.text("iss") == config.issuer, "unauthenticated", 401)
        val audience = when (val value = claims["aud"]) {
            is JsonPrimitive -> listOf(value.takeIf(JsonPrimitive::isString)?.content ?: reject("unauthenticated", 401))
            is JsonArray -> value.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: reject("unauthenticated", 401) }
            else -> reject("unauthenticated", 401)
        }
        expect(audience.isNotEmpty() && audience.size <= 20 && audience.distinct().size == audience.size && config.clientId in audience, "unauthenticated", 401)
        expect((audience.size == 1 && claims["azp"] == null) || claims.text("azp") == config.clientId, "unauthenticated", 401)
        val now = clock() / 1000
        val issued = claims.number("iat"); val expiry = claims.number("exp")
        expect(issued <= now + 30 && issued >= now - 600 && expiry > now && expiry > issued && expiry - issued <= 86400, "unauthenticated", 401)
        claims["nbf"]?.let { expect(claims.number("nbf") <= now + 30, "unauthenticated", 401) }
        expect(MessageDigest.isEqual(digest(claims.text("nonce")), nonceHash), "unauthenticated", 401)
        val subject = claims.text("sub").also { expect(it.isNotBlank() && it.length <= 255 && it.none(Char::isISOControl), "unauthenticated", 401) }
        if (config.allowedGroups.isNotEmpty()) {
            val groups = claims["groups"] as? JsonArray ?: reject("forbidden", 403)
            expect(groups.size <= 200 && groups.all { it is JsonPrimitive && it.isString && it.content.length <= 255 }, "forbidden", 403)
            expect(groups.any { it.jsonPrimitive.content in config.allowedGroups }, "forbidden", 403)
        }
        val name = (claims["name"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf { it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl) } ?: ""
        val email = (claims["email"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf { it.length <= 320 && it.none(Char::isISOControl) && (claims["email_verified"] as? JsonPrimitive)?.booleanOrNull == true }
        OidcIdentity(subject, name, email)
    } catch (error: Exception) {
        if (error is RelayError) throw error
        reject("unauthenticated", 401)
    }
}

internal fun encodeForm(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
internal fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
internal fun digestText(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(digest(value))
internal fun JsonObject.text(name: String): String = (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: reject("unauthenticated", 401)
private fun JsonObject.number(name: String): Long = (this[name] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull ?: reject("unauthenticated", 401)
private fun decodePart(value: String): ByteArray {
    expect(value.isNotEmpty() && value.length <= 32768 && value.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }, "unauthenticated", 401)
    return Wire.decode(value)
}
private fun strictObject(bytes: ByteArray) = Wire.strictJson(bytes, 32768) as? JsonObject ?: reject("unauthenticated", 401)
