package io.theficos.usuals.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** The same golden the browser checks in web/test/connect.test.mjs. */
class PairingTest {
    private val origin = "https://usuals.example"
    private val offerId = "00000000-0000-4000-8000-000000000002"
    private val secret = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8"
    private val link = "https://usuals.example/connect#1.AAAAAAAAQACAAAAAAAAAAiAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4_"
    private val appLink = "usuals://connect#aHR0cHM6Ly91c3VhbHMuZXhhbXBsZS9jb25uZWN0IzEuQUFBQUFBQUFRQUNBQUFBQUFBQUFBaUFoSWlNa0pTWW5LQ2txS3l3dExpOHdNVEl6TkRVMk56ZzVPanM4UFQ0Xw"

    @Test fun connectionCodesAreSixDigitsFromTheFingerprintPrefix() {
        for ((fingerprint, expected) in listOf("0".repeat(64) to "000000", "f".repeat(64) to "967295", "0001e240" + "0".repeat(56) to "123456"))
            assertThat(Pairing.connectionCode(fingerprint)).isEqualTo(expected)
    }

    @Test fun connectLinksCarryTheOfferIdAndSecret() {
        for (text in listOf(link, appLink)) {
            val parsed = ConnectCode.parse(text)
            assertThat(parsed.origin).isEqualTo(origin)
            assertThat(parsed.claim()).isEqualTo(OfferClaim(offerId, secret))
            assertThat(parsed.toString()).doesNotContain(secret)
            assertThat(parsed.claim().toString()).doesNotContain(secret)
        }
    }

    @Test fun householdInvitesAndMalformedConnectLinksAreRejected() {
        val payload = link.substringAfter("#1.")
        // The retired one-scan form that also carried a household invite.
        val withInvite = link + "AAAAAAAAQACAAAAAAAAAAQABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4f5Uq_XQZOZryKvn_Texd6nQ"
        for (bad in listOf(withInvite, "https://usuals.example/connect#2.$payload", "https://usuals.example/connect", "https://usuals.example/join#1.$payload",
            link.dropLast(2), "https://user@usuals.example/connect#1.$payload", "http://usuals.example/connect#1.$payload",
            "usuals://connect#" + Wire.encode("https://usuals.example".toByteArray())))
            assertThrows(IllegalArgumentException::class.java) { ConnectCode.parse(bad) }
    }
}
