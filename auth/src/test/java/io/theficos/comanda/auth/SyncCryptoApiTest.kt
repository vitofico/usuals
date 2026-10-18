package io.theficos.comanda.auth

import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.sync.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 33])
class SyncCryptoApiTest {
    @Test fun profilePrimitivesRoundTripAtSupportedApiBoundaries() {
        val a = SyncCrypto.device(); val b = SyncCrypto.device(); val key = SyncCrypto.randomKey()
        val server = Wire.hash(byteArrayOf(1)); val household = java.util.UUID.randomUUID().toString()
        val p = SyncCrypto.wrap(server, household, java.util.UUID.randomUUID().toString(), 1, Wire.zero, b.publicKeys, key, a)
        assertThat(SyncCrypto.unwrap(server, household, p, b, a.publicKeys.signingPublicKey)).isEqualTo(key)
        val prepared = RecoveryKits.prepare("https://comanda.invalid", ServerInfo(Wire.hash(Wire.decode(a.publicKeys.signingPublicKey, 32)), a.publicKeys.signingPublicKey))
        prepared.verifySaved(prepared.file, prepared.secret)
    }
}
