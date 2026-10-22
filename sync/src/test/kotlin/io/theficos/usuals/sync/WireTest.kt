package io.theficos.usuals.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Assert.assertThrows
import org.junit.Test

class WireTest {
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

    @Test fun strictJsonComparesDecodedKeysAndAcceptsEscapedStringsAndCanonicalNumbers() {
        val text = """{ "plain" : "x\"y\\z\u00e9\ud83d\ude00", "caf\u00e9":"\/", "a\/b":[true,false,0,-0,12,-3.25,1e9,2E-3,4.5e+6], "q":"\"" }"""
        val parsed = Wire.strictJson(text.toByteArray(), 10000).jsonObject
        assertThat(parsed.keys).containsExactly("plain", "caf\u00e9", "a/b", "q").inOrder()
        assertThat(parsed.getValue("plain").jsonPrimitive.content).isEqualTo("x\"y\\z\u00e9\ud83d\ude00")
        assertThat(parsed.getValue("caf\u00e9").jsonPrimitive.content).isEqualTo("/")
        assertThat(parsed.getValue("a/b").jsonArray.map { it.jsonPrimitive.content }).containsExactly("true", "false", "0", "-0", "12", "-3.25", "1e9", "2E-3", "4.5e+6").inOrder()
        // A key's length is its decoded length.
        assertThat(Wire.strictJson("{\"${"\\u0061".repeat(64)}\":1}".toByteArray(), 10000).jsonObject.keys).containsExactly("a".repeat(64))
        assertThat(Wire.strictJson("[null,{\"a\":null}]".toByteArray(), 10000, payload = true).jsonArray).hasSize(2)
        val rejected = listOf(
            "{\"${"a".repeat(65)}\":1}", "{\"${"\\u0061".repeat(65)}\":1}",
            "{\"caf\u00e9\":1,\"caf\\u00e9\":2}", "{\"a/\":1,\"a\\/\":2}", "{\"\\\"\":1,\"\\u0022\":2}",
            "{\"a\":\"\\x\"}", "{\"\\x\":1}", "{\"a\":\"\\u12\"}", "{\"a\":\"\\u12g4\"}", "[\"\\q\"]",
            "{\"a\":\"abc", "{\"a\":\"abc\\", "\"\\", "{\"a", "[null]",
        ) + listOf("01", "-01", "1.", ".5", "+1", "1e", "1e+", "-", "--1", "0x1", "NaN", "Infinity", "-Infinity", "tru", "True", "1.5.2", "1 2").map { "[$it]" }
        for (bad in rejected) assertThrows(bad, IllegalArgumentException::class.java) { Wire.strictJson(bad.toByteArray(), 10000) }
    }
}
