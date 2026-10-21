package io.theficos.usuals.sync

import java.nio.ByteBuffer
import java.util.UUID

object Pairing {
    /** The connection fingerprint's first four bytes, short enough to compare across two apps on one phone. */
    fun connectionCode(fingerprintHex: String): String {
        require(fingerprintHex.length == 64 && fingerprintHex.all { it in '0'..'9' || it in 'a'..'f' })
        val bytes = ByteArray(4) { fingerprintHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return "%06d".format((ByteBuffer.wrap(bytes).int.toLong() and 0xffffffffL) % 1_000_000)
    }
}

/** A signed-in browser's one-scan QR: its account approves this phone without a second sign-in. */
class ConnectCode private constructor(val origin: String, val offerId: String, secret: ByteArray) {
    private val secret = secret.copyOf()
    fun claim() = OfferClaim(offerId, Wire.encode(secret))
    override fun toString() = "ConnectCode([redacted])"

    companion object {
        /** True for both the web link and the app link that wraps it; a bare server address is not a connect code. */
        fun matches(text: String): Boolean {
            val link = if (text.startsWith("usuals://connect#")) runCatching { Wire.decode(text.substringAfter('#')).toString(Charsets.UTF_8) }.getOrNull() else text
            return link != null && (link.startsWith("https://") || link.startsWith("http://")) && link.substringBefore('#').endsWith("/connect") && link.contains('#')
        }
        /** Only the 16-byte offer id and 32-byte secret; the household version's longer codes are refused. */
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
            val bytes = ByteBuffer.wrap(Wire.decode(fragment.removePrefix("1."), 48))
            return ConnectCode(serverOrigin(base.removeSuffix("/connect")), UUID(bytes.long, bytes.long).toString(), ByteArray(32).also { bytes.get(it) })
        }
    }
}
