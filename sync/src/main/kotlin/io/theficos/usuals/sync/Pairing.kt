package io.theficos.usuals.sync

import com.google.crypto.tink.subtle.Hkdf
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable

/**
 * The private half of a pairing v2 invitation. It travels only inside the
 * link; the relay sees the derived admission secret, never this key or the
 * MAC key, so it cannot vouch for public keys it substituted.
 */
class InviteKey(bytes: ByteArray) {
    private val bytes = bytes.copyOf().also { require(it.size == 32) }
    fun admit(): ByteArray = derive("USUALS/INVITE/2/admit")
    fun macKey(): ByteArray = derive("USUALS/INVITE/2/mac")
    fun encoded(): String = Wire.encode(bytes)
    internal fun raw(): ByteArray = bytes.copyOf()
    private fun derive(info: String) = Hkdf.computeHkdf("HMACSHA256", bytes, ByteArray(0), info.toByteArray(), 32)
    override fun toString() = "InviteKey([redacted])"
    companion object { fun random() = InviteKey(SyncCrypto.randomKey()) }
}

/** Household facts the applicant must not take from the relay; the link pins their exact bytes. */
@Serializable data class InviteDescriptor(val version: Int = 2, val origin: String, val serverId: String, val householdId: String,
    val genesisHash: String, val ownerDeviceId: String, val ownerFingerprint: String, val memberId: String, val role: String) {
    fun validate(origin: String): InviteDescriptor = also {
        require(version == 2 && this.origin == origin && role in setOf("owner", "editor"))
        Wire.uuid(householdId); Wire.uuid(ownerDeviceId); Wire.uuid(memberId)
        Wire.decode(serverId, 32); Wire.decode(genesisHash, 32); Wire.decode(ownerFingerprint, 32)
    }
}

object Pairing {
    /** The relay's 24-hour invite lifetime plus five minutes of clock skew; a later expiry is the relay overreaching. */
    const val MAX_INVITE_LIFETIME = 86_700_000L
    fun descriptorAnchor(descriptor: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("USUALS/INVITE/2\u0000".toByteArray() + descriptor).copyOf(16)
    fun joinMac(macKey: ByteArray, assertion: ByteArray): String = Wire.encode(hmac(macKey, "USUALS/JOIN-MAC/2\u0000", assertion))
    fun verifyJoinMac(macKey: ByteArray, assertion: ByteArray, mac: String): Boolean =
        runCatching { MessageDigest.isEqual(hmac(macKey, "USUALS/JOIN-MAC/2\u0000", assertion), Wire.decode(mac, 32)) }.getOrDefault(false)
    fun joinCode(macKey: ByteArray, assertion: ByteArray): String = sixDigits(hmac(macKey, "USUALS/JOIN-CODE/2\u0000", assertion))
    /** The connection fingerprint's first four bytes, short enough to compare across two apps on one phone. */
    fun connectionCode(fingerprintHex: String): String {
        require(fingerprintHex.length == 64 && fingerprintHex.all { it in '0'..'9' || it in 'a'..'f' })
        return sixDigits(ByteArray(4) { fingerprintHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
    }
    private fun sixDigits(bytes: ByteArray) = "%06d".format((ByteBuffer.wrap(bytes, 0, 4).int.toLong() and 0xffffffffL) % 1_000_000)
    private fun hmac(key: ByteArray, label: String, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(label.toByteArray() + message)
}

/**
 * A signed-in browser's one-scan QR: its account approves this phone without a second sign-in, and an owner
 * browser adds a household invite so the same approval also brings the phone into the household.
 */
class ConnectCode(val origin: String, val offerId: String, secret: ByteArray, val invite: InviteCode?) {
    private val secret = secret.copyOf().also { require(it.size == 32) }
    init { Wire.uuid(offerId); require(invite == null || invite.origin == origin) }
    fun claim() = OfferClaim(offerId, Wire.encode(secret))
    fun link(): String {
        val buffer = ByteBuffer.allocate(if (invite == null) 48 else 112)
        fun put(id: String) = Wire.uuid(id).let { buffer.putLong(it.mostSignificantBits).putLong(it.leastSignificantBits) }
        put(offerId); buffer.put(secret)
        invite?.let { put(it.inviteId); buffer.put(it.key.raw()).put(it.anchor) }
        return "$origin/connect#1." + Wire.encode(buffer.array())
    }
    override fun toString() = "ConnectCode([redacted])"

    companion object {
        /** True for both the web link and the app link that wraps it; a bare server address is not a connect code. */
        fun matches(text: String): Boolean {
            val link = if (text.startsWith("usuals://connect#")) runCatching { Wire.decode(text.substringAfter('#')).toString(Charsets.UTF_8) }.getOrNull() else text
            return link != null && (link.startsWith("https://") || link.startsWith("http://")) && link.substringBefore('#').endsWith("/connect") && link.contains('#')
        }
        fun parse(text: String): ConnectCode {
            require(text.length <= 512)
            if (text.startsWith("usuals://connect#")) {
                val link = Wire.decode(text.substringAfter('#')).toString(Charsets.UTF_8)
                require(!link.startsWith("usuals:"))
                return parse(link)
            }
            val base = text.substringBefore('#')
            val fragment = text.substringAfter('#', "")
            require(base.endsWith("/connect") && fragment.startsWith("1."))
            val origin = serverOrigin(base.removeSuffix("/connect"))
            val raw = Wire.decode(fragment.removePrefix("1."))
            require(raw.size == 48 || raw.size == 112)
            val bytes = ByteBuffer.wrap(raw)
            fun id() = UUID(bytes.long, bytes.long).toString()
            val offer = id(); val secret = ByteArray(32).also { bytes.get(it) }
            val invite = if (raw.size == 48) null else InviteCode(origin, id(), InviteKey(ByteArray(32).also { bytes.get(it) }), ByteArray(16).also { bytes.get(it) })
            return ConnectCode(origin, offer, secret, invite)
        }
    }
}

/** What a pairing v2 QR or link carries: where to ask, which invite, its key, and the descriptor anchor. */
class InviteCode(val origin: String, val inviteId: String, val key: InviteKey, anchor: ByteArray) {
    val anchor: ByteArray = anchor.copyOf().also { require(it.size == 16) }
    init { Wire.uuid(inviteId) }
    fun link(): String {
        val id = Wire.uuid(inviteId)
        val payload = ByteBuffer.allocate(64).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).put(key.raw()).put(anchor).array()
        return "$origin/join#2." + Wire.encode(payload)
    }
    /** The custom-scheme wrapper a browser page uses to hand the same link to the installed app. */
    fun appLink(): String = "usuals://join#" + Wire.encode(link().toByteArray())
    override fun toString() = "InviteCode([redacted])"

    companion object {
        fun parse(text: String): InviteCode {
            require(text.length <= 512)
            if (text.startsWith("usuals://join#")) {
                val inner = Wire.decode(text.substringAfter('#'))
                if (inner.firstOrNull() == '{'.code.toByte()) throw UnsupportedWireVersion()
                val link = inner.toString(Charsets.UTF_8)
                require(!link.startsWith("usuals:"))
                return parse(link)
            }
            val base = text.substringBefore('#')
            val fragment = text.substringAfter('#', "")
            require(base.endsWith("/join") && fragment.startsWith("2."))
            val origin = serverOrigin(base.removeSuffix("/join"))
            val bytes = ByteBuffer.wrap(Wire.decode(fragment.removePrefix("2."), 64))
            val id = UUID(bytes.long, bytes.long).toString()
            return InviteCode(origin, id, InviteKey(ByteArray(32).also { bytes.get(it) }), ByteArray(16).also { bytes.get(it) })
        }
    }
}
