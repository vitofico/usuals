package io.theficos.usuals

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.theficos.usuals.sync.*
import io.theficos.usuals.ui.account.AccountScreen
import io.theficos.usuals.ui.account.AccountSession
import io.theficos.usuals.ui.account.AccountViewModel
import io.theficos.usuals.ui.settings.AccountSummary
import io.theficos.usuals.ui.settings.SettingsScreen
import io.theficos.usuals.ui.settings.SettingsViewModel
import io.theficos.usuals.ui.theme.UsualsTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccountIdentityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsShowTheCurrentPersonAndServerAndForgetThemOnSignOut() {
        var account: AccountSummary? = AccountSummary("usuals.example.org:8443", "Vito")
        val vm = SettingsViewModel(accountSummary = { account })
        var opened = false
        compose.setContent { UsualsTheme { SettingsScreen(vm, {}, onOpenAccount = { opened = true }) } }
        val identity = compose.activity.getString(R.string.account_signed_in_as, "Vito") + "\nusuals.example.org:8443"
        compose.onNodeWithText(identity).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(opened) }
        compose.runOnIdle { account = null; vm.loadIfNeeded() }
        compose.waitUntil { vm.state.value.account == null }
        compose.onNodeWithText(identity).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.settings_offline)).assertIsDisplayed()
    }

    @Test fun accountWithoutAHumanReadableNameShowsTheServer() {
        val vm = SettingsViewModel(accountSummary = { AccountSummary("usuals.example.org", "  ") })
        compose.setContent { UsualsTheme { SettingsScreen(vm, {}) } }
        compose.onNodeWithText(compose.activity.getString(R.string.settings_account_on, "usuals.example.org")).assertIsDisplayed()
    }

    @Test fun accountPageKeepsCachedIdentityVisibleWhileOfflineWithoutAnyLists() {
        val account = ConnectionAccount("synthetic-user", "Vito", "vito")
        val info = InstanceInfo(name = "Usuals", server = ServerInfo("synthetic-server", "synthetic-key"), accounts = true, ai = false)
        val store = object : SyncStateStore {
            private var state = ClientState(connection = ServerConnection("https://usuals.example.org", info, account.id, "synthetic-token", Long.MAX_VALUE, account = account))
            override fun read() = state
            override fun update(change: (ClientState) -> ClientState) = change(state).also { state = it }
        }
        val waiting = CompletableDeferred<Unit>()
        val connection = ConnectionClient(store, object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse {
                waiting.await()
                return WireResponse(503, "{\"code\":\"unavailable\"}".toByteArray())
            }
        })
        val session = object : AccountSession {
            override suspend fun expired() = false
            override suspend fun name() = connection.current()?.account?.label
            override suspend fun refresh() { connection.refreshAccount() }
            override suspend fun connected() {}
            override suspend fun signOut(force: Boolean) = 0
        }
        val vm = AccountViewModel(connection, session)
        compose.setContent { UsualsTheme { AccountScreen(vm, {}) } }
        compose.onNodeWithText(compose.activity.getString(R.string.account_signed_in_as, "Vito")).assertIsDisplayed()
        compose.onNodeWithText("vito").assertIsDisplayed()
        compose.onNodeWithText("https://usuals.example.org").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.account_scan_title)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.account_sign_out)).assertIsEnabled().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.account_sign_out_title)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.edit_cancel)).performClick()
        waiting.complete(Unit)
        compose.waitUntil { !vm.state.value.busy }
    }
}
