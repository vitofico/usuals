package io.theficos.usuals.sync

/** Everything a person may scan or paste into Usuals' single server-or-invitation entry point. */
sealed interface PairingLink {
    data class Server(val origin: String) : PairingLink
    /** Kept verbatim: the private key in an invitation's fragment is validated only by the join flow. */
    data class Invite(val text: String) : PairingLink { override fun toString() = "Invite([redacted])" }

    companion object {
        fun parse(text: String): PairingLink {
            require(text.length <= 4096)
            if (text.startsWith("usuals://join#")) return Invite(text)
            if (text.startsWith("https://") || text.startsWith("http://")) {
                val path = text.substringAfter("://").substringAfter('/', "").substringBefore('#')
                if (path == "join" && text.contains('#')) return Invite(text)
            }
            return Server(try { serverOrigin(text) } catch (_: java.net.URISyntaxException) { throw IllegalArgumentException("not_usuals") })
        }

        fun recognize(text: String) = runCatching { parse(text) }.isSuccess
    }
}
