package io.theficos.usuals.sync

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingTest {
    private val v: JsonObject = Wire.json.parseToJsonElement(File("../testing/src/test/resources/pairing-v2/vectors.json").readText()).jsonObject
    private fun s(name: String) = v.getValue(name).jsonPrimitive.content
    private val key = InviteKey(Wire.decode(s("key"), 32))
    private val assertion = Wire.decode(s("assertion"))

    @Test fun derivedKeysMacAndCodeMatchTheGolden() {
        assertThat(Wire.encode(key.admit())).isEqualTo(s("admit"))
        assertThat(Wire.hash(key.admit())).isEqualTo(s("secretHash"))
        assertThat(Wire.encode(key.macKey())).isEqualTo(s("macKey"))
        assertThat(Pairing.joinMac(key.macKey(), assertion)).isEqualTo(s("mac"))
        assertThat(Pairing.joinCode(key.macKey(), assertion)).isEqualTo(s("code"))
        assertThat(Pairing.verifyJoinMac(key.macKey(), assertion, s("mac"))).isTrue()
    }

    @Test fun oneChangedAssertionByteFailsTheMac() {
        val changed = assertion.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertThat(Pairing.verifyJoinMac(key.macKey(), changed, s("mac"))).isFalse()
        assertThat(Pairing.verifyJoinMac(InviteKey(ByteArray(32)).macKey(), assertion, s("mac"))).isFalse()
    }

    @Test fun anchorAndLinksMatchTheGoldenAndRoundTrip() {
        val anchor = Pairing.descriptorAnchor(Wire.decode(s("descriptor")))
        assertThat(Wire.encode(anchor)).isEqualTo(s("anchor"))
        val code = InviteCode(s("origin"), s("inviteId"), key, anchor)
        assertThat(code.link()).isEqualTo(s("link"))
        assertThat(code.appLink()).isEqualTo(s("appLink"))
        for (text in listOf(s("link"), s("appLink"))) {
            val parsed = InviteCode.parse(text)
            assertThat(parsed.origin).isEqualTo(s("origin"))
            assertThat(parsed.inviteId).isEqualTo(s("inviteId"))
            assertThat(Wire.encode(parsed.key.admit())).isEqualTo(s("admit"))
            assertThat(parsed.anchor.toList()).isEqualTo(anchor.toList())
        }
    }

    @Test fun tamperedDescriptorDoesNotMatchTheAnchor() {
        val descriptor = Wire.decode(s("descriptor")).copyOf().also { it[it.size - 3] = 'x'.code.toByte() }
        assertThat(Pairing.descriptorAnchor(descriptor).toList()).isNotEqualTo(Wire.decode(s("anchor")).toList())
    }

    @Test fun legacyJsonInvitationsAskForAnUpdateAndOtherTextIsRejected() {
        assertThrows(UnsupportedWireVersion::class.java) { InviteCode.parse("usuals://join#" + Wire.encode("{\"version\":1}".toByteArray())) }
        for (bad in listOf("https://usuals.example/join#1.AAAA", "https://usuals.example/join", "https://usuals.example/other#2." + s("link").substringAfter("#2."),
            s("link").dropLast(2), "https://user@usuals.example/join#2." + s("link").substringAfter("#2.")))
            assertThrows(IllegalArgumentException::class.java) { InviteCode.parse(bad) }
    }

    @Test fun connectionCodesAreSixDigitsFromTheFingerprintPrefix() {
        for (pair in v.getValue("connectionCodes").jsonArray) {
            val (fingerprint, expected) = pair.jsonArray.map { it.jsonPrimitive.content }
            assertThat(Pairing.connectionCode(fingerprint)).isEqualTo(expected)
        }
    }

    @Test fun secretsAreRedacted() {
        assertThat(key.toString()).doesNotContain(s("key"))
        assertThat(InviteCode.parse(s("link")).toString()).doesNotContain(s("link").substringAfter("#2."))
        assertThat(ConnectCode.parse(s("connectInviteLink")).toString()).doesNotContain(s("offerSecret"))
        assertThat(ConnectCode.parse(s("connectLink")).claim().toString()).doesNotContain(s("offerSecret"))
    }

    @Test fun connectLinksMatchTheGoldenAndCarryTheOptionalInvite() {
        val anchor = Wire.decode(s("anchor"), 16)
        val plain = ConnectCode(s("origin"), s("offerId"), Wire.decode(s("offerSecret"), 32), null)
        val full = ConnectCode(s("origin"), s("offerId"), Wire.decode(s("offerSecret"), 32), InviteCode(s("origin"), s("inviteId"), key, anchor))
        assertThat(plain.link()).isEqualTo(s("connectLink"))
        assertThat(full.link()).isEqualTo(s("connectInviteLink"))
        for (text in listOf(s("connectLink"), s("connectAppLink"))) {
            val parsed = ConnectCode.parse(text)
            assertThat(parsed.origin).isEqualTo(s("origin"))
            assertThat(parsed.claim()).isEqualTo(OfferClaim(s("offerId"), s("offerSecret")))
            assertThat(parsed.invite).isNull()
        }
        for (text in listOf(s("connectInviteLink"), s("connectInviteAppLink"))) {
            val parsed = ConnectCode.parse(text)
            assertThat(parsed.claim()).isEqualTo(OfferClaim(s("offerId"), s("offerSecret")))
            assertThat(requireNotNull(parsed.invite).link()).isEqualTo(s("link"))
        }
    }

    @Test fun malformedConnectLinksAreRejected() {
        val payload = s("connectLink").substringAfter("#1.")
        for (bad in listOf("https://usuals.example/connect#2.$payload", "https://usuals.example/connect", "https://usuals.example/join#1.$payload",
            s("connectLink").dropLast(2), "https://user@usuals.example/connect#1.$payload", "http://usuals.example/connect#1.$payload",
            "usuals://connect#" + Wire.encode("https://usuals.example".toByteArray())))
            assertThrows(IllegalArgumentException::class.java) { ConnectCode.parse(bad) }
    }
}
