package io.theficos.usuals.ui.account

import io.theficos.usuals.sync.ConnectCode
import io.theficos.usuals.sync.Wire
import io.theficos.usuals.sync.serverOrigin

/** A scanned or opened code, read before anything contacts a server. */
sealed interface ConnectScan {
    /** The web app's "Connect a phone" QR: a 16-byte offer id and a 32-byte secret. */
    data class Offer(val link: String, val origin: String) : ConnectScan { override fun toString() = "Offer([redacted])" }
    /** A plain server address, for signing in through the browser. */
    data class Server(val origin: String) : ConnectScan
    /** A connect code of another length, such as an invitation from the household version. */
    data object Outdated : ConnectScan
    data object Unknown : ConnectScan

    companion object {
        fun read(text: String): ConnectScan {
            val value = text.trim()
            if (value.length > 512) return Unknown
            if (!ConnectCode.matches(value)) return runCatching { Server(serverOrigin(value)) }.getOrDefault(Unknown)
            val link = if (value.startsWith("usuals://connect#")) runCatching { Wire.decode(value.substringAfter('#')).toString(Charsets.UTF_8) }.getOrNull() else value
            val fragment = link?.substringAfter('#')?.takeIf { it.startsWith("1.") } ?: return Unknown
            val bytes = runCatching { Wire.decode(fragment.removePrefix("1.")) }.getOrNull() ?: return Unknown
            if (bytes.size != OFFER_BYTES) return Outdated
            return runCatching { Offer(value, ConnectCode.parse(value).origin) }.getOrDefault(Unknown)
        }
        fun recognizes(text: String) = read(text) != Unknown
        private const val OFFER_BYTES = 48
    }
}
