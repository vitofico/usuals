package io.theficos.usuals.sync

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

/** Shared framing only. Secrets, cryptographic primitives and transport live at their boundaries. */
object Wire {
    val json = Json { encodeDefaults = true; explicitNulls = false }
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(text: String, size: Int? = null): ByteArray {
        require(text.isNotEmpty() && text.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' })
        val bytes = Base64.getUrlDecoder().decode(text)
        require(encode(bytes) == text && (size == null || bytes.size == size))
        return bytes
    }
    fun uuid(text: String): UUID = UUID.fromString(text).also { require(it.toString() == text) }
    fun hash(bytes: ByteArray): String = encode(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    fun strictJson(bytes: ByteArray, max: Int, payload: Boolean = false): JsonElement {
        if (bytes.size > max) throw WireTooLarge()
        val text = utf8(bytes)
        JsonShape(text, payload).validate()
        return json.parseToJsonElement(text)
    }
    fun fingerprint(server: String, ticket: String, signing: String, recipient: String): String = hash(bytes {
        write("USUALS/FINGERPRINT/1\u0000".toByteArray()); write(decode(server, 32)); id(ticket)
        write(decode(signing, 32)); write(decode(recipient, 32))
    })
    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use(block) }.toByteArray()
    private fun DataOutputStream.id(text: String) { val id = uuid(text); writeLong(id.mostSignificantBits); writeLong(id.leastSignificantBits) }
}

class WireTooLarge : IllegalArgumentException()

@Serializable data class ServerInfo(val serverId: String, val receiptPublicKey: String, val protocolVersions: List<Int> = listOf(1))
@Serializable data class DeviceKeys(val id: String, val signingPublicKey: String, val recipientPublicKey: String)

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
