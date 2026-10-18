package io.theficos.comanda.ui.sharing

import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.theficos.comanda.sync.*
import io.theficos.comanda.ui.theme.ComandaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class SharingFeedbackTest {
    @get:Rule val compose = createComposeRule()
    @Test fun recoveryFailureRemainsVisibleAfterScrollingToTheFilePicker() {
        val store = object : SyncStateStore {
            private var value = ClientState()
            override fun read() = value
            override fun update(change: (ClientState) -> ClientState) = change(value).also { value = it }
            override fun reserve(scope: String) { error("unused") }
            override fun saveBlob(id: String, bytes: ByteArray) { error("unused") }
            override fun readBlob(id: String): ByteArray? = error("unused")
            override fun removeBlob(id: String) { error("unused") }
        }
        val transport = object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse = error("Malformed recovery must not access the network")
        }
        val vm = SharingViewModel(HouseholdClient(store, transport, CheckpointSource { _, _ -> error("unused") }))
        compose.setContent { ComandaTheme { SharingScreen(vm, {}, Modifier.height(500.dp)) } }
        compose.waitUntil(5000) { !vm.state.value.busy }
        compose.onNodeWithText("Open recovery file").performScrollTo().assertIsDisplayed()
        vm.recover(byteArrayOf(1))
        compose.waitUntil(5000) { vm.state.value.problem != null }
        compose.onNodeWithText("Could not verify these details. Check the file, key or invitation and try again.").assertIsDisplayed()
    }
}
