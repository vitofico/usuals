package io.theficos.usuals

import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.usuals.auth.SyncVault
import io.theficos.usuals.di.OkHttpRelayTransport
import io.theficos.usuals.di.VaultSyncStateStore
import io.theficos.usuals.llm.ServerLlmClient
import io.theficos.usuals.llm.VisionChallenge
import io.theficos.usuals.sync.*
import io.theficos.usuals.ui.sharing.*
import io.theficos.usuals.ui.theme.UsualsTheme
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit local QA: host approves the public ticket in the disposable preview browser. */
class ConnectionDeviceScenarioTest {
    @Test fun browserApprovalNativeHouseholdAndGateway(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("connectionQa") == "synthetic")
        val context = instrumentation.targetContext
        val namespace = File(context.noBackupFilesDir, "connectionqa")
        require(!namespace.exists())
        val origin = InstrumentationRegistry.getArguments().getString("connectionOrigin") ?: "http://localhost:18746"
        require(origin.matches(Regex("http://localhost:[0-9]{4,5}")))
        val vault = SyncVault(context, "connectionqa")
        val store = VaultSyncStateStore(context, vault)
        val transport = OkHttpRelayTransport(true)
        val connection = ConnectionClient(store, transport)
        val household = HouseholdClient(store, transport, CheckpointSource { _, _ -> error("no shopping data in account QA") })
        val vm = SharingViewModel(household, connection = connection)
        val viewModels = ViewModelStore().apply { put("qa", vm) }
        val publicTicket = File(context.filesDir, "connection-qa.json")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity -> activity.setContent { UsualsTheme { SharingScreen(vm, {}, connectionOnly = true) } } }
            withContext(Dispatchers.Main) { vm.field(SharingField.ORIGIN, origin) }
            withTimeout(10000) { while (vm.state.value.busy) delay(100) }
            withContext(Dispatchers.Main) { vm.connectServer() }
            withTimeout(20000) { while (connection.pending() == null) delay(100) }
            val pending = requireNotNull(connection.pending())
            publicTicket.writeText(JSONObject().put("id", pending.ticket.id).put("fingerprint", pending.fingerprint).toString())
            withTimeout(180000) { while (connection.current() == null) delay(250) }
            withTimeout(15000) { while (vm.state.value.ai?.revision == null || vm.state.value.busy) delay(100) }
            withContext(Dispatchers.Main) { vm.enableServerAi(true) }
            val model = ServerLlmClient(connection)
            assertTrue(model.probe().supportsStructuredOutput)
            assertTrue(model.parseUtterance("synthetic shopping recipe").isNotEmpty())
            assertTrue(model.probeImages().supportsImages)
            assertTrue(model.parseImage(VisionChallenge.create().image).isNotEmpty())
            withContext(Dispatchers.Main) { vm.enableServerAi(false) }
            assertFalse(requireNotNull(connection.current()).aiEnabled)
            val draft = household.prepare(origin)
            val id = household.bootstrapAccount(draft.verifySaved(draft.file, draft.secret), requireNotNull(connection.current()))
            assertEquals("owner", household.households().single { it.id == id }.role)
            connection.disconnect()
            assertNull(connection.current())
            assertEquals(1, household.households().size)
        } finally {
            scenario.close()
            withContext(Dispatchers.Main) { viewModels.clear() }
            publicTicket.delete()
            namespace.deleteRecursively()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("usuals_sync_wrap_v1_connectionqa") }
        }
    }
}
