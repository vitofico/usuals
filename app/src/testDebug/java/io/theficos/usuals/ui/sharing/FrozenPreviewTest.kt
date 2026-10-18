package io.theficos.usuals.ui.sharing

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.sync.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class FrozenPreviewTest {
    @get:Rule val compose = createComposeRule()
    private fun client(): HouseholdClient {
        val store = object : SyncStateStore {
            override fun read(): ClientState = error("unreadable old vault must stay untouched")
            override fun update(change: (ClientState) -> ClientState): ClientState = error("no private writes")
            override fun reserve(scope: String) { error("unused") }
            override fun saveBlob(id: String, bytes: ByteArray) { error("unused") }
            override fun readBlob(id: String): ByteArray? = error("unused")
            override fun removeBlob(id: String) { error("unused") }
        }
        val transport = object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse = error("offline path must not send requests")
        }
        return HouseholdClient(store, transport, CheckpointSource { _, _ -> error("unused") })
    }
    private fun copy(): FrozenCopy = FrozenCopy::class.java.getDeclaredConstructor(String::class.java,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType, ByteArray::class.java).newInstance("Old Home", 0, 0, true, true, SnapshotCodec.encode(Projection(name = "Old Home")))
    @Test fun saveUsesTheExactVerifiedPreviewWithoutReopeningTheFileOrSynchronizing() {
        compose.setContent { Box {} }
        val preview = copy(); var reads = 0; var saved: FrozenCopy? = null; var name: String? = null; var syncs = 0
        val vm = SharingViewModel(client(), readFrozen = { _, _, _ -> reads++; preview },
            saveFrozen = { value, label -> saved = value; name = label }, synchronize = { syncs++ })
        vm.selectFrozen("content://synthetic/backup")
        compose.waitUntil(5000) { !vm.state.value.busy }
        assertThat(vm.state.value.frozenCopy).isSameInstanceAs(preview)
        vm.saveFrozenCopy("Old Home (copy 2)")
        compose.waitUntil(5000) { !vm.state.value.busy }
        assertThat(saved).isSameInstanceAs(preview); assertThat(name).isEqualTo("Old Home (copy 2)")
        assertThat(reads).isEqualTo(1); assertThat(syncs).isEqualTo(0)
        assertThat(vm.state.value.frozenCopy).isNull(); assertThat(vm.state.value.message).isEqualTo(SharingMessage.FROZEN_SAVED)
    }
    @Test fun fileFailureAndCancelledPreviewKeepShoppingUntouchedAndClearTheSeparateKey() {
        compose.setContent { Box {} }
        var saves = 0
        val vm = SharingViewModel(client(), readFrozen = { _, _, _ -> throw java.io.IOException() }, saveFrozen = { _, _ -> saves++ })
        vm.selectFrozen("content://synthetic/missing")
        compose.waitUntil(5000) { !vm.state.value.busy }
        assertThat(vm.state.value.problem).isEqualTo(SharingProblem.FILE)
        vm.field(SharingField.RECOVERY, "secret")
        vm.frozenKit(byteArrayOf(1))
        compose.waitUntil(5000) { !vm.state.value.busy }
        assertThat(vm.state.value.recoveryKey).isEmpty()
        vm.closeFrozen(); vm.saveFrozenCopy("unused")
        assertThat(saves).isEqualTo(0); assertThat(vm.state.value.frozenUri).isNull()
    }
    @Test fun aPublicBackupRequestWorksWithAnUnreadableVaultAndNeverIncludesPrivateKeys() {
        compose.setContent { Box {} }
        val device = SyncCrypto.device()
        val server = ServerInfo(Wire.hash(Wire.decode(device.publicKeys.signingPublicKey)), device.publicKeys.signingPublicKey)
        val setup = RecoveryKits.prepare("https://relay.example", server)
        val vm = SharingViewModel(client())
        vm.field(SharingField.RECOVERY, setup.secret); vm.prepareBackupRequest(setup.file)
        compose.waitUntil(5000) { !vm.state.value.busy }
        val json = requireNotNull(vm.state.value.backupRequest).toString(Charsets.UTF_8)
        assertThat(json).doesNotContain(setup.secret); assertThat(json).doesNotContain("signingSeed"); assertThat(json).doesNotContain("recipientSecret")
        assertThat(vm.state.value.backupFingerprint).hasLength(64); assertThat(vm.state.value.recoveryKey).isEmpty()
    }
}
