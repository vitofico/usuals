package io.theficos.comanda.relay

import java.net.InetAddress

/** One directly connected, explicitly trusted ingress hop. Never trust a client-supplied earlier hop. */
class TrustedIngress(cidrs: List<String> = emptyList()) {
    private val networks = cidrs.map { value ->
        val parts = value.split('/'); require(parts.size == 2)
        val address = requireNotNull(address(parts[0])); val bits = parts[1].toInt()
        require(bits in 1..address.size * 8)
        address to bits
    }.also { require(it.size <= 20) }
    fun client(peer: String, forwarded: List<String>): String {
        val remote = address(peer) ?: return "unknown-peer"
        val trusted = networks.any { (network, bits) ->
            remote.size == network.size && remote.indices.all { index ->
                val mask = when { index * 8 >= bits -> 0; index * 8 + 8 <= bits -> 255; else -> 255 shl (8 - bits % 8) and 255 }
                (remote[index].toInt() and mask) == (network[index].toInt() and mask)
            }
        }
        if (trusted && forwarded.size == 1 && forwarded.single().length <= 2048) {
            val hops = forwarded.single().split(',')
            if (hops.size <= 16) address(hops.last().trim())?.let { return InetAddress.getByAddress(it).hostAddress }
        }
        return InetAddress.getByAddress(remote).hostAddress
    }
    companion object {
        private fun address(value: String): ByteArray? {
            if (value.length !in 2..45 || value.any { it !in "0123456789abcdefABCDEF:." }) return null
            if (':' !in value && (value.split('.').size != 4 || value.split('.').any { it.toIntOrNull() !in 0..255 })) return null
            return runCatching { InetAddress.getByName(value).address }.getOrNull()
        }
    }
}
