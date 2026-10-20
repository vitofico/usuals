package io.theficos.usuals

import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.usuals.auth.KeystoreSecretStore
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Explicit two-install emulator check. Host saves/restores original preferences around it. */
class ProviderKeyUpgradeTest {
    @Test fun prepareLegacyKey() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("usualsLegacyStep") == "prepare")
        KeystoreSecretStore(InstrumentationRegistry.getInstrumentation().targetContext).putModelKey(FIXTURE)
        assertTrue(KeystoreSecretStore(InstrumentationRegistry.getInstrumentation().targetContext).modelKey() == FIXTURE)
    }
    @Test fun verifyUpgradedKey() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("usualsLegacyStep") == "verify")
        assertTrue(KeystoreSecretStore(InstrumentationRegistry.getInstrumentation().targetContext).modelKey() == FIXTURE)
    }
    private companion object { const val FIXTURE = "usuals-upgrade-fixture-v1" }
}
