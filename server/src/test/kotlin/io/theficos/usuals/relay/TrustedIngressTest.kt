package io.theficos.usuals.relay

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TrustedIngressTest {
    @Test fun separateClientsBehindTheConfiguredHopHaveSeparateBudgetsAndEarlierSpoofsAreIgnored() {
        val ingress = TrustedIngress(listOf("10.1.0.0/16"))
        assertThat(ingress.client("10.1.2.3", listOf("192.0.2.1"))).isEqualTo("192.0.2.1")
        assertThat(ingress.client("10.1.2.3", listOf("192.0.2.2"))).isEqualTo("192.0.2.2")
        assertThat(ingress.client("10.1.2.3", listOf("198.51.100.7, 192.0.2.1"))).isEqualTo("192.0.2.1")
    }
    @Test fun untrustedPeersDuplicateHeadersAndMalformedOrAbsentForwardingCannotPickAnIdentity() {
        val ingress = TrustedIngress(listOf("10.1.0.0/16"))
        assertThat(ingress.client("198.51.100.2", listOf("192.0.2.1"))).isEqualTo("198.51.100.2")
        assertThat(ingress.client("10.1.2.3", listOf("192.0.2.1", "192.0.2.2"))).isEqualTo("10.1.2.3")
        assertThat(ingress.client("10.1.2.3", listOf("host.example"))).isEqualTo("10.1.2.3")
        assertThat(ingress.client("10.1.2.3", emptyList())).isEqualTo("10.1.2.3")
        assertThat(TrustedIngress().client("10.1.2.3", listOf("192.0.2.1"))).isEqualTo("10.1.2.3")
        assertThrows(IllegalArgumentException::class.java) { TrustedIngress(listOf("not-inventoried")) }
        assertThrows(IllegalArgumentException::class.java) { TrustedIngress(listOf("0.0.0.0/0")) }
    }
}
