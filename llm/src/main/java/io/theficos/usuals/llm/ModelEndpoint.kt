package io.theficos.usuals.llm

import java.net.InetAddress
import java.net.URI

/** Transport consent applies to the configured URL. Provider privacy labels do not grant it. */
object ModelEndpoint {
    fun permits(baseUrl: String, allowLocalHttp: Boolean, hasKey: Boolean): Boolean {
        val uri = try { URI(baseUrl) } catch (_: java.net.URISyntaxException) { return false }
        val host = uri.host?.lowercase() ?: return false
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return false
        if (uri.port != -1 && uri.port !in 1..65535) return false
        return uri.scheme.equals("https", true) ||
            (uri.scheme.equals("http", true) && allowLocalHttp && !hasKey && localHost(host))
    }

    private fun localHost(host: String): Boolean {
        if (host == "localhost" || host == "host.docker.internal" || host.endsWith(".localhost") || host.endsWith(".local")) return true
        if (host.startsWith("[") && host.endsWith("]")) {
            // URI validates the IPv6 literal. No host name is resolved here.
            val address = InetAddress.getByName(host.removeSurrounding("[", "]").substringBefore('%'))
            return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
                (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
        }
        val octets = host.split('.')
        if (octets.size != 4) return false
        val numbers = octets.map { part ->
            part.toIntOrNull()?.takeIf { it in 0..255 && it.toString() == part } ?: return false
        }
        return numbers[0] == 127 || numbers[0] == 10 ||
            (numbers[0] == 192 && numbers[1] == 168) || (numbers[0] == 172 && numbers[1] in 16..31)
    }
}
