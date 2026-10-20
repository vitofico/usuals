package io.theficos.usuals.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Assert.assertThrows
import org.junit.Test

class WireTest {
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun productionFramingMatchesTheIndependentS0Golden() {
        val fixture = Json.parseToJsonElement(javaClass.getResourceAsStream("/shared-list-v1/wire.json")!!.bufferedReader().readText()).jsonObject
        fun text(key: String) = fixture.getValue(key).jsonPrimitive.content
        val bytes = text("headerHex").unhex()
        val header = Header.parse(bytes)
        assertThat(header.bytes()).isEqualTo(bytes)
        assertThat(header.serverId).isEqualTo(Wire.encode(text("serverId").unhex()))
        assertThat(header.epoch).isEqualTo(fixture.getValue("epoch").jsonPrimitive.long)
        val envelope = Envelope(Wire.encode(bytes), Wire.encode(text("ciphertextHex").unhex()), Wire.encode(ByteArray(64)))
        assertThat(envelope.signingBytes()).isEqualTo(text("signingInputHex").unhex())
        assertThat(Wire.hash(envelope.signingBytes())).isEqualTo(Wire.encode(text("signingInputSha256").unhex()))
        assertThat(Wire.domain("AAD", bytes)).isEqualTo(text("aadHex").unhex())
    }
    @Test fun untrustedWireRejectsAmbiguousOrOversizedStructureAndNoncanonicalBinary() {
        for (text in listOf("{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}", "{\"optional\":null}", "[".repeat(34) + "0" + "]".repeat(34), "{\"a\":1} trailing", "{\"a\":01}")) {
            assertThrows(IllegalArgumentException::class.java) { Wire.strictJson(text.toByteArray(), 10000) }
        }
        assertThrows(IllegalArgumentException::class.java) { Wire.decode("AQ==") }
        assertThrows(IllegalArgumentException::class.java) { Wire.decode("AR") }
        assertThrows(IllegalArgumentException::class.java) { Wire.uuid("AAAAAAAA-0000-4000-8000-000000000001") }
        assertThrows(IllegalArgumentException::class.java) { Wire.strictJson("{}".toByteArray(), 1) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { Wire.strictJson(byteArrayOf(0xc0.toByte(), 0xaf.toByte()), 100) }
    }
    @Test fun joinAssertionsRoundTripTheCompleteBindingAndRejectTrailingBytes() {
        fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
        fun hash(n: Int) = Wire.encode(ByteArray(32) { n.toByte() })
        val assertion = JoinAssertion("https://usuals.invalid", hash(1), uuid(1), hash(2), uuid(2), uuid(3), uuid(4),
            DeviceKeys(uuid(5), hash(3), hash(4)), hash(5), uuid(6), hash(6), 123)
        assertThat(JoinAssertion.parse(assertion.bytes())).isEqualTo(assertion)
        assertThrows(IllegalArgumentException::class.java) { JoinAssertion.parse(assertion.bytes() + 0) }
    }
}
