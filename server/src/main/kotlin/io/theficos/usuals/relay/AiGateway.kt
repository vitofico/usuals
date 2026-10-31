package io.theficos.usuals.relay

import io.theficos.usuals.llm.*
import io.theficos.usuals.sync.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable data class AiProvider(val id: String, val label: String, val baseUrl: String, val model: String,
    val imageModel: String = model, val isLocal: Boolean = false, val kind: String = "OPENAI_COMPATIBLE", val keyEnv: String? = null,
    val allowHttp: Boolean = false) {
    override fun toString() = "AiProvider([redacted])"
    fun view() = AiProfileView(id, label, model, imageModel, isLocal, baseUrl)
}
class AiConfiguration(val profiles: List<AiProvider> = emptyList(), val default: String? = profiles.firstOrNull()?.id,
    val wrappingKey: ByteArray? = null, val dailyLimit: Int = 20, private val environment: Map<String, String> = System.getenv()) {
    init {
        require(profiles.size <= 16 && profiles.map { it.id }.distinct().size == profiles.size && dailyLimit in 0..10000)
        require(default == null || profiles.any { it.id == default })
        require(wrappingKey == null || wrappingKey.size == 32)
        profiles.forEach { p ->
            require(p.id.matches(Regex("[a-z0-9-]{1,40}")) && p.label.length in 1..100 && p.model.length in 1..128 && p.imageModel.length in 1..128)
            require(p.kind in setOf("ANTHROPIC", "OLLAMA", "OPENAI_COMPATIBLE"))
            val uri = URI(p.baseUrl)
            require(uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.scheme == "https" || uri.scheme == "http" && (p.isLocal || p.allowHttp)) && p.baseUrl.length <= 1024)
            p.keyEnv?.let { require(it.matches(Regex("[A-Z][A-Z0-9_]{0,100}"))); validateKey(environment[it]) }
        }
    }
    fun key(profile: AiProvider) = profile.keyEnv?.let { environment[it] }
    override fun toString() = "AiConfiguration([redacted])"
    companion object {
        fun validateKey(key: String?) { require(key == null || key.length in 1..4096 && key.none { it.code < 32 || it.code == 127 }) }
        fun readKey(path: Path): ByteArray {
            require(!Files.isSymbolicLink(path) && Files.isRegularFile(path) && Files.size(path) == 32L)
            require(Files.getPosixFilePermissions(path).none { it in setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE) })
            return Files.readAllBytes(path)
        }
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AiConfiguration {
            val text = env["USUALS_AI_PROFILES"] ?: "[]"
            val profiles = Wire.json.decodeFromJsonElement<List<AiProvider>>(Wire.strictJson(text.toByteArray(), 32768))
            val key = env["USUALS_AI_KEY_FILE"]?.let { readKey(Path.of(it)) }
            return AiConfiguration(profiles, env["USUALS_AI_DEFAULT"] ?: profiles.firstOrNull()?.id, key,
                env["USUALS_AI_DAILY_LIMIT"]?.toInt() ?: 20, env)
        }
    }
}

/** Fixed operator destinations prevent user credentials turning the gateway into an open proxy. */
class AiGateway(private val db: Database, val configuration: AiConfiguration,
    private val clock: () -> Long = System::currentTimeMillis,
    private val accountLimits: AccountLimits = AccountLimits(clock = clock),
    private val build: (ProviderConfig) -> LlmClient = { OpenAiCompatibleClient(it, failOnError = true) }) {
    private data class Choice(val profile: AiProvider, val model: String?, val key: String?, val revision: String) {
        override fun toString() = "Choice([redacted])"
    }
    private fun choice(c: java.sql.Connection, user: String): Choice {
        val selected = c.query("SELECT profile,model,credential FROM ai_preference WHERE user_id=?", id(user)) {
            Triple(it.getString(1), it.getString(2), it.getBytes(3))
        }.singleOrNull()
        val profile = configuration.profiles.firstOrNull { it.id == (selected?.first ?: configuration.default) } ?: reject("ai_unavailable", 503)
        return Choice(profile, selected?.second, selected?.third?.let { decrypt(user, it) } ?: configuration.key(profile),
            revision(profile, selected?.second, selected?.third))
    }
    private fun revision(profile: AiProvider, model: String?, credential: ByteArray?) = Wire.hash(
        "${profile.id}\u0000${profile.baseUrl}\u0000${profile.model}\u0000${profile.imageModel}\u0000${profile.isLocal}\u0000$model\u0000${credential?.let { Wire.hash(it) }}".toByteArray())
    fun settings(user: String): AiSettings = db.transaction { c ->
        val pref = c.query("SELECT profile,model,credential FROM ai_preference WHERE user_id=?", id(user)) { Triple(it.getString(1), it.getString(2), it.getBytes(3)) }.singleOrNull()
        val selected = pref?.first ?: configuration.default
        val profile = configuration.profiles.firstOrNull { it.id == selected }
        val used = c.query("SELECT attempts FROM ai_usage WHERE user_id=? AND day=?", id(user), clock() / 86400000) { it.getInt(1) }.singleOrNull() ?: 0
        AiSettings(configuration.profiles.map { it.view() }, selected, pref != null, pref?.third != null, configuration.dailyLimit, used,
            profile?.let { revision(it, pref?.second, pref?.third) })
    }
    fun select(user: String, request: AiSelection, authorize: (java.sql.Connection) -> Unit = {}): AiSettings {
        AiConfiguration.validateKey(request.key)
        db.transaction { c ->
            lockAccount(c, user); authorize(c)
            if (request.profile == null) {
                expect(request.model == null && request.key == null)
                c.execute("DELETE FROM ai_preference WHERE user_id=?", id(user))
            } else {
                val profile = configuration.profiles.firstOrNull { it.id == request.profile } ?: reject("invalid", 400)
                expect(request.model == null || request.model in setOf(profile.model, profile.imageModel))
                val encrypted = request.key?.let { encrypt(user, it) }
                c.execute("INSERT INTO ai_preference VALUES (?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET profile=EXCLUDED.profile,model=EXCLUDED.model,credential=EXCLUDED.credential",
                    id(user), profile.id, request.model, encrypted)
            }
            c.execute("DELETE FROM ai_vision_check WHERE user_id=?", id(user))
        }
        return settings(user)
    }
    private fun lockAccount(c: java.sql.Connection, user: String) {
        accountRead(c)
        expect(c.query("SELECT disabled FROM web_user WHERE id=? FOR UPDATE", id(user)) { !it.getBoolean(1) }.singleOrNull() == true, "forbidden", 403)
    }
    private fun consume(c: java.sql.Connection, user: String) {
        val day = clock() / 86400000
        val used = c.query("INSERT INTO ai_usage VALUES (?,?,1) ON CONFLICT(user_id) DO UPDATE SET day=EXCLUDED.day,attempts=CASE WHEN ai_usage.day=EXCLUDED.day THEN ai_usage.attempts+1 ELSE 1 END RETURNING attempts", id(user), day) { it.getInt(1) }.single()
        expect(used <= configuration.dailyLimit, "ai_budget", 429)
        accountLimits.effective(c, user)?.monthlyAiRequests?.let { limit ->
            val month = java.time.Instant.ofEpochMilli(clock()).atZone(java.time.ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).toString()
            val monthly = c.query("INSERT INTO ai_usage_monthly VALUES (?,?,1) ON CONFLICT(user_id) DO UPDATE SET month=EXCLUDED.month,requests=CASE WHEN ai_usage_monthly.month=EXCLUDED.month THEN ai_usage_monthly.requests+1 ELSE 1 END RETURNING requests", id(user), month) { it.getInt(1) }.single()
            expect(monthly <= limit, "ai_monthly_budget", 429)
        }
    }
    suspend fun execute(user: String, action: String, request: AiRequest, authorize: (java.sql.Connection) -> Unit = {}): AiReply {
        val (choice, image, stamp) = db.transaction { c ->
            lockAccount(c, user); authorize(c)
            val choice = choice(c, user); val p = choice.profile
            expect(request.revision == choice.revision, "ai_configuration_changed", 409)
            val model = if (action in setOf("image", "probe-image")) p.imageModel else choice.model ?: p.model
            val stamp = Wire.hash("${p.id}\u0000${p.baseUrl}\u0000$model\u0000${p.kind}\u0000${choice.key}".toByteArray())
            val image = if (action == "image") {
                expect(request.text == null && request.image != null && request.mimeType in setOf("image/jpeg", "image/png"))
                expect(requireNotNull(request.image).length <= 2800000, "too_large", 413)
                expect(c.query("SELECT 1 FROM ai_vision_check WHERE user_id=? AND profile_hash=? AND expires>?", id(user), stamp, clock()) { true }.isNotEmpty(), "ai_images_unverified", 409)
                ModelImage(requireNotNull(request.mimeType), java.util.Base64.getDecoder().decode(request.image))
            } else null
            if (action == "text") expect(request.image == null && request.mimeType == null && request.text != null && requireNotNull(request.text).length in 1..30000)
            else if (action.startsWith("probe")) expect(request.text == null && request.image == null && request.mimeType == null)
            else expect(action == "image")
            consume(c, user)
            Triple(choice, image, stamp)
        }
        val p = choice.profile
        val model = if (action in setOf("image", "probe-image")) p.imageModel else choice.model ?: p.model
        val config = ProviderConfig(ProviderKind.valueOf(p.kind), p.label, p.baseUrl, model, p.isLocal, choice.key)
        val client = build(config)
        if (action.startsWith("probe")) {
            val caps = if (action == "probe-image") client.probeImages() else client.probe()
            if (action == "probe-image" && caps.supportsImages) db.transaction { c ->
                lockAccount(c, user); authorize(c)
                c.execute("INSERT INTO ai_vision_check VALUES (?,?,?) ON CONFLICT(user_id) DO UPDATE SET profile_hash=EXCLUDED.profile_hash,expires=EXCLUDED.expires", id(user), stamp, clock() + 600000)
            }
            return AiReply(reachable = caps.reachable, structured = caps.supportsStructuredOutput, images = caps.supportsImages, model = caps.model, problem = caps.problem?.kind?.name)
        }
        val items = if (image != null) client.parseImage(image) else client.parseUtterance(requireNotNull(request.text))
        return AiReply(items = items.map { AiItem(it.name, it.quantity?.amount?.toString(), it.quantity?.unit?.name, it.note) }, model = model)
    }
    fun rewrap(newKey: ByteArray) {
        require(newKey.size == 32)
        val replacement = AiGateway(db, AiConfiguration(wrappingKey = newKey))
        db.transaction { c ->
            c.query("SELECT user_id,credential FROM ai_preference WHERE credential IS NOT NULL FOR UPDATE") { it.getObject(1).toString() to it.getBytes(2) }
                .forEach { (user, value) -> c.execute("UPDATE ai_preference SET credential=? WHERE user_id=?", replacement.encrypt(user, decrypt(user, value)), id(user)) }
            c.execute("DELETE FROM ai_vision_check")
        }
    }
    private fun encrypt(user: String, key: String): ByteArray {
        val wrapping = configuration.wrappingKey ?: reject("ai_credentials_unavailable", 503)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapping, "AES"), GCMParameterSpec(128, nonce)); cipher.updateAAD("USUALS/AI/1\u0000$user".toByteArray())
        return nonce + cipher.doFinal(key.toByteArray())
    }
    private fun decrypt(user: String, bytes: ByteArray): String {
        val wrapping = configuration.wrappingKey ?: reject("ai_credentials_unavailable", 503)
        try {
            require(bytes.size >= 29)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(wrapping, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD("USUALS/AI/1\u0000$user".toByteArray())
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        } catch (_: java.security.GeneralSecurityException) { reject("ai_credentials_unavailable", 503) }
    }
}
