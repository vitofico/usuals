package io.theficos.usuals.sync

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

/** Shared framing only. Secrets, cryptographic primitives and transport live at their boundaries. */
object Wire {
    val json = Json { encodeDefaults = true; explicitNulls = false }
    val zero: String = encode(ByteArray(32))
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(text: String, size: Int? = null): ByteArray {
        require(text.isNotEmpty() && text.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' })
        val bytes = Base64.getUrlDecoder().decode(text)
        require(encode(bytes) == text && (size == null || bytes.size == size))
        return bytes
    }
    fun uuid(text: String): UUID = UUID.fromString(text).also { require(it.toString() == text) }
    fun hash(bytes: ByteArray): String = encode(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun bytes(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()
    fun DataOutputStream.field(bytes: ByteArray) { writeInt(bytes.size); write(bytes) }
    fun DataOutputStream.id(text: String) { val id = uuid(text); writeLong(id.mostSignificantBits); writeLong(id.leastSignificantBits) }
    fun domain(domain: String, vararg fields: ByteArray): ByteArray = bytes {
        write("USUALS/$domain/1\u0000".toByteArray(Charsets.US_ASCII)); fields.forEach { field(it) }
    }
    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    fun strictJson(bytes: ByteArray, max: Int, payload: Boolean = false): JsonElement {
        if (bytes.size > max) throw WireTooLarge()
        val text = utf8(bytes)
        JsonShape(text, payload).validate()
        return json.parseToJsonElement(text)
    }
    fun fingerprint(server: String, household: String, signing: String, recipient: String): String = hash(bytes {
        write("USUALS/FINGERPRINT/1\u0000".toByteArray()); write(decode(server, 32)); id(household)
        write(decode(signing, 32)); write(decode(recipient, 32))
    })
    fun auth(origin: String, server: String, device: String, challenge: String, nonce: String, expiry: Long, purpose: String, signing: String) = bytes {
        write("USUALS/AUTH/1\u0000".toByteArray()); field(origin.toByteArray()); write(decode(server, 32))
        id(device); id(challenge); write(decode(nonce, 32)); require(expiry >= 0); writeLong(expiry)
        field(purpose.toByteArray(Charsets.US_ASCII)); write(decode(signing, 32))
    }
    fun receipt(server: String, household: String, list: String, seq: Long, head: String) = bytes {
        write("USUALS/RECEIPT/1\u0000".toByteArray()); write(decode(server, 32)); id(household); id(list)
        require(seq > 0); writeLong(seq); write(decode(head, 32))
    }
    fun head(previous: String, seq: Long, envelope: Envelope) = hash(bytes {
        write("USUALS/HEAD/1\u0000".toByteArray()); write(decode(previous, 32)); writeLong(seq)
        write(decode(hash(envelope.signingBytes()), 32)); write(decode(envelope.signature, 64))
    })
}

@Serializable
data class Header(val serverId: String, val householdId: String, val listId: String, val authorDeviceId: String,
    val intentId: String, val envelopeId: String, val epoch: Long, val manifestHash: String, val baseSeq: Long, val baseHead: String,
    val protocol: Int = 1, val payloadSchema: Int = 1) {
    fun bytes() = with(Wire) { bytes {
        require(protocol == 1 && payloadSchema == 1 && epoch > 0 && baseSeq >= 0)
        writeShort(protocol); write(decode(serverId, 32)); id(householdId); id(listId); id(authorDeviceId); id(intentId); id(envelopeId)
        writeLong(epoch); write(decode(manifestHash, 32)); writeLong(baseSeq); write(decode(baseHead, 32)); writeShort(payloadSchema)
    } }
    companion object {
        fun parse(bytes: ByteArray): Header {
            require(bytes.size == 196)
            val b = ByteBuffer.wrap(bytes)
            val version = b.short.toInt()
            fun hash() = Wire.encode(ByteArray(32).also { b.get(it) })
            fun id() = UUID(b.long, b.long).toString()
            val h = Header(hash(), id(), id(), id(), id(), id(), b.long, hash(), b.long, hash(), version, b.short.toInt())
            if (h.protocol != 1 || h.payloadSchema != 1) throw UnsupportedWireVersion()
            require(h.epoch > 0 && h.baseSeq >= 0)
            return h
        }
    }
}
class UnsupportedWireVersion : IllegalArgumentException()
class WireTooLarge : IllegalArgumentException()

@Serializable
data class Envelope(val header: String, val ciphertext: String, val signature: String) {
    fun parsed() = Header.parse(Wire.decode(header, 196))
    fun signingBytes() = Wire.domain("ENVELOPE", Wire.decode(header, 196), Wire.decode(ciphertext))
    fun digest() = Wire.hash(signingBytes() + Wire.decode(signature, 64))
    fun validate(maxDecoded: Int = 65536) {
        parsed(); val cipher = Wire.decode(ciphertext); Wire.decode(signature, 64)
        require(cipher.size >= 28)
        if (cipher.size + 260 > maxDecoded) throw WireTooLarge()
    }
}
@Serializable data class Receipt(val seq: Long, val previousHead: String, val head: String, val signature: String)
@Serializable data class StoredOperation(val envelope: Envelope, val receipt: Receipt)
@Serializable data class Append(val envelopes: List<Envelope>)
@Serializable data class AppendResult(@SerialName("inputIndex") val index: Int, val intentId: String, val envelopeId: String, @SerialName("outcome") val status: String, val receipt: Receipt)
@Serializable data class AppendResults(val results: List<AppendResult>, @SerialName("lastSeq") val currentSeq: Long, @SerialName("head") val currentHead: String)
@Serializable data class Operations(val entries: List<StoredOperation>, val nextCursor: Long, val hasMore: Boolean)
@Serializable data class ChallengeRequest(val deviceId: String, val purpose: String, val signingPublicKey: String? = null)
@Serializable data class Challenge(val challengeId: String, val nonce: String, val serverId: String, val expiresAt: Long, val purpose: String)
@Serializable data class Proof(val challengeId: String, val deviceId: String, val signature: String)
@Serializable data class Session(val token: String, val expiresAt: Long) { override fun toString() = "Session([redacted])" }
@Serializable data class ServerInfo(val serverId: String, val receiptPublicKey: String, val protocolVersions: List<Int> = listOf(1))
@Serializable data class DeviceKeys(val id: String, val signingPublicKey: String, val recipientPublicKey: String)
@Serializable data class RosterMember(val id: String, val role: String, val devices: List<DeviceKeys>)
@Serializable data class ListCutoff(val listId: String, val seq: Long, val head: String, val snapshotId: String, val snapshotHash: String)
@Serializable data class Manifest(val version: Int = 1, val serverId: String, val householdId: String, val genesisHash: String,
    val epoch: Long, val previousHash: String, val action: String, val signer: String,
    val members: List<RosterMember>, val recovery: DeviceKeys, val lists: List<ListCutoff> = emptyList(),
    val packageHashes: List<String> = emptyList(), val approvedJoins: Map<String, String> = emptyMap(),
    val createdListId: String? = null, val transferToDeviceId: String? = null)
@Serializable data class TransferAcceptance(val signature: String)
@Serializable data class SnapshotRef(val candidateId: String, @SerialName("sha256") val hash: String, val listId: String, val seq: Long, val head: String)
@Serializable data class KeyPackage(val listId: String, val recipientFingerprint: String, val epoch: Long,
    val predecessorHash: String, val ciphertext: String, val signature: String) {
    fun context(server: String, household: String) = with(Wire) { bytes {
        write("USUALS/KEY/1\u0000".toByteArray()); write(decode(server, 32)); id(household); id(listId)
        writeLong(epoch); write(decode(predecessorHash, 32)); write(decode(recipientFingerprint, 32))
    } }
    fun signingBytes(server: String, household: String) = Wire.domain("PACKAGE", context(server, household), Wire.decode(ciphertext))
    fun hash(server: String, household: String) = Wire.hash(signingBytes(server, household) + Wire.decode(signature, 64))
}
@Serializable data class SignedManifest(val manifestBytes: String, val signature: String, val packages: List<KeyPackage>,
    val snapshotRefs: List<SnapshotRef>, val transferAcceptance: TransferAcceptance? = null) {
    fun body(): Manifest {
        val objectValue = Wire.strictJson(Wire.decode(manifestBytes), 4 * 1024 * 1024).jsonObject
        require(objectValue.keys.containsAll(setOf("version", "serverId", "householdId", "genesisHash", "epoch", "previousHash",
            "action", "signer", "members", "recovery", "lists", "packageHashes", "approvedJoins")))
        return Wire.json.decodeFromJsonElement(objectValue)
    }
    fun signingBytes() = Wire.domain("MEMBERSHIP", Wire.decode(manifestBytes), transferAcceptance?.let { Wire.decode(it.signature, 64) } ?: byteArrayOf())
    fun hash() = Wire.hash(signingBytes() + Wire.decode(signature, 64))
}
@Serializable data class CreateHousehold(val manifest: SignedManifest, val bootstrapProof: Proof)
@Serializable data class InviteRequest(val memberId: String, val role: String)
@Serializable data class Invite(val inviteId: String, val secret: String, val expiresAt: Long) { override fun toString() = "Invite([redacted])" }
@Serializable data class JoinRequest(val secret: String, val assertion: String, val signature: String) { override fun toString() = "JoinRequest([redacted])" }
@Serializable data class JoinPending(val requestId: String, val expiresAt: Long)

@Serializable data class Snapshot(val envelope: Envelope, val seq: Long, val head: String)
@Serializable data class ManifestProof(val manifestBytes: String, val signature: String, val transferAcceptance: TransferAcceptance? = null) {
    fun signed() = SignedManifest(manifestBytes, signature, emptyList(), emptyList(), transferAcceptance)
}
@Serializable data class Membership(val manifests: List<ManifestProof>, val nextEpoch: Long)
@Serializable data class Lists(val listIds: List<String>)
@Serializable data class KeyPackages(val packages: List<KeyPackage>)
@Serializable data class PendingJoin(val requestId: String, val assertion: String, val signature: String)
@Serializable data class PendingRequests(val requests: List<PendingJoin>)

/** Detect duplicate decoded keys before kotlinx.serialization can collapse them. */
private class JsonShape(private val text: String, private val payload: Boolean) {
    private var at = 0
    private var nodes = 0
    fun validate() { value(0); space(); require(at == text.length) }
    private fun space() { while (at < text.length && text[at] in " \n\r\t") at++ }
    private fun take(c: Char) { space(); require(at < text.length && text[at++] == c) }
    private fun string(): String {
        space(); val start = at; require(at < text.length && text[at++] == '"')
        while (at < text.length) {
            when (text[at++]) {
                '\\' -> { require(at < text.length); at++ }
                '"' -> return Wire.json.decodeFromString(text.substring(start, at))
            }
        }
        throw IllegalArgumentException("invalid")
    }
    private fun value(depth: Int) {
        require(depth <= 32 && ++nodes <= if (payload) 1000000 else 65536); space(); require(at < text.length)
        when (text[at]) {
            '{' -> {
                at++; space(); val keys = mutableSetOf<String>()
                if (at < text.length && text[at] != '}') while (true) {
                    val key = string(); require(key.length <= (if (payload) 1000 else 64) && keys.size < (if (payload) 20000 else 4096) && keys.add(key)); take(':'); value(depth + 1); space()
                    if (at >= text.length || text[at] != ',') break
                    at++
                }
                take('}')
            }
            '[' -> {
                at++; space()
                if (at < text.length && text[at] != ']') while (true) {
                    value(depth + 1); space(); if (at >= text.length || text[at] != ',') break; at++
                }
                take(']')
            }
            '"' -> string()
            else -> {
                val start = at
                while (at < text.length && text[at] !in ",]} \n\r\t") at++
                val literal = text.substring(start, at)
                require(at > start && (payload && literal == "null" || Regex("(?:true|false|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)").matches(literal)))
            }
        }
    }
}

@Serializable data class JoinAssertion(val origin: String, val serverId: String, val householdId: String,
    val genesisHash: String, val inviteId: String, val requestId: String, val memberId: String,
    val device: DeviceKeys, val secretHash: String, val challengeId: String, val nonce: String, val expiresAt: Long) {
    fun bytes() = with(Wire) { bytes {
        write("USUALS/JOIN/1\u0000".toByteArray()); field(origin.toByteArray()); write(decode(serverId, 32)); id(householdId)
        write(decode(genesisHash, 32)); id(inviteId); id(requestId); id(memberId); id(device.id)
        write(decode(device.signingPublicKey, 32)); write(decode(device.recipientPublicKey, 32)); write(decode(secretHash, 32))
        id(challengeId); write(decode(nonce, 32)); require(expiresAt >= 0); writeLong(expiresAt)
    } }
    companion object {
        fun parse(bytes: ByteArray): JoinAssertion {
            require(bytes.size <= 1536)
            val b = ByteBuffer.wrap(bytes)
            val domain = "USUALS/JOIN/1\u0000".toByteArray()
            require(ByteArray(domain.size).also { b.get(it) }.contentEquals(domain))
            val length = b.int; require(length in 1..512 && length <= b.remaining())
            val origin = Wire.utf8(ByteArray(length).also { b.get(it) })
            fun hash() = Wire.encode(ByteArray(32).also { b.get(it) })
            fun id() = UUID(b.long, b.long).toString()
            val value = JoinAssertion(origin, hash(), id(), hash(), id(), id(), id(), DeviceKeys(id(), hash(), hash()), hash(), id(), hash(), b.long)
            require(!b.hasRemaining() && value.expiresAt >= 0)
            return value
        }
    }
}
