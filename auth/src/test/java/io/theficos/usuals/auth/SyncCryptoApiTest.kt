package io.theficos.usuals.auth

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.sync.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33])
class SyncCryptoApiTest {
    /** Connecting a phone generates device keys; the browser sign-in compares a code derived from them. */
    @Test fun connectKeysWorkAtSupportedApiBoundaries() {
        val device = SyncCrypto.device()
        val code = Pairing.connectionCode(connectionFingerprint(Wire.hash(byteArrayOf(1)), java.util.UUID.randomUUID().toString(), device.publicKeys))
        assertThat(code).matches("[0-9]{6}")
    }
}
