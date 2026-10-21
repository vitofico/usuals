package io.theficos.usuals.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingLinkTest {
    @Test fun serverLinksInEveryShareableFormGiveTheOrigin() {
        for (text in listOf("https://usuals.example/connect", "https://usuals.example", "https://usuals.example/",
            "usuals://connect#" + Wire.encode("https://usuals.example".toByteArray()))) {
            assertThat(PairingLink.parse(text)).isEqualTo(PairingLink.Server("https://usuals.example"))
            assertThat(PairingLink.recognize(text)).isTrue()
        }
    }

    @Test fun invitationLinksAreKeptVerbatimForTheJoinFlow() {
        for (text in listOf("usuals://join#e30", "https://usuals.example/join#2.AAAA")) {
            assertThat(PairingLink.parse(text)).isEqualTo(PairingLink.Invite(text))
            assertThat(PairingLink.recognize(text)).isTrue()
        }
    }

    @Test fun unrelatedCodesAreNotUsualsLinks() {
        for (text in listOf("https://usuals.example/other", "hello", "4006381333931", "usuals://device#x", "")) {
            assertThrows(IllegalArgumentException::class.java) { PairingLink.parse(text) }
            assertThat(PairingLink.recognize(text)).isFalse()
        }
    }
}
