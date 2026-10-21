package io.theficos.usuals.sync

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
}
