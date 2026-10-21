package io.theficos.usuals

import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.compose.setContent
import io.theficos.usuals.di.ServiceLocator
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.sync.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in local two-emulator harness. Credentials enter through app-private fixture files, never argv. */
class SharingDeviceScenarioTest {
    @Test fun localRelayScenario() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "sharing-device-qa")
        val input = File(directory, "input.json")
        assumeTrue(input.isFile)
        val fixture = JSONObject(input.readText())
        val origin = java.net.URI(fixture.getString("origin"))
        assumeTrue(fixture.optBoolean("syntheticLocalOnly") && origin.scheme == "http" && origin.host == "10.0.2.2" &&
            origin.port in 1024..65535 && origin.rawPath.isNullOrEmpty() && origin.rawQuery == null && origin.rawFragment == null && origin.userInfo == null)
        ServiceLocator.reset(); ServiceLocator.init(context)
        val client = ServiceLocator.householdClient
        val lists = ServiceLocator.listRepository
        val items = ServiceLocator.itemRepository
        val history = ServiceLocator.historyRepository
        fun write(name: String, value: String) { File(directory, name).apply { writeText(value); android.system.Os.chmod(path, 384) } }
        val result = JSONObject()
        withTimeout(if (fixture.getString("action") in setOf("recovery-ui", "pairing-ui")) 600_000 else 120_000) {
            when (fixture.getString("action")) {
                "pairing-ui" -> {
                    val invitation = InviteCode.parse(fixture.getString("invitationUri"))
                    require(invitation.origin == "http://localhost:${origin.port}")
                    androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        lateinit var vm: io.theficos.usuals.ui.sharing.SharingViewModel
                        scenario.onActivity { activity ->
                            vm = androidx.lifecycle.ViewModelProvider(activity, io.theficos.usuals.di.UsualsViewModelFactories.sharing())[io.theficos.usuals.ui.sharing.SharingViewModel::class.java]
                            activity.setContent { io.theficos.usuals.ui.theme.UsualsTheme { io.theficos.usuals.ui.sharing.SharingScreen(vm, {}) } }
                        }
                        while (vm.state.value.busy) delay(100)
                        vm.scanned(fixture.getString("invitationUri"))
                        delay(500)
                        // Only this opt-in synthetic harness permits capture of its join code screen.
                        scenario.onActivity { it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
                        write("ui-ready", "ready")
                        var request: String? = null
                        while (!File(directory, "ui-done").isFile) {
                            vm.state.value.joining?.let { pending ->
                                if (request != pending.assertion.requestId) {
                                    request = pending.assertion.requestId
                                    write("join-code", vm.joinCode(pending).orEmpty())
                                }
                            }
                            vm.state.value.households.firstOrNull()?.let { write("paired-ready", it.id) }
                            delay(200)
                        }
                    }
                }
                "await-item", "send-measure" -> {
                    val coordinator = ServiceLocator.sharingCoordinator
                    coordinator.foreground(true)
                    assertFalse(coordinator.syncOnce())
                    delay(1_000)
                    val measurements = org.json.JSONArray()
                    val count = fixture.optInt("count", 1).also { require(it in 1..4) }
                    repeat(count) { index ->
                        val name = fixture.getString("name") + if (count == 1) "" else " $index"
                        val timing = JSONObject()
                        if (fixture.getString("action") == "await-item") {
                            write("listener-ready", "ready")
                            history.observeShopping().first { snapshot -> snapshot.items.any { it.name == name } }
                            timing.put("observedAt", System.currentTimeMillis())
                        } else {
                            timing.put("submittedAt", System.currentTimeMillis())
                            items.add(ParsedItem(name))
                            timing.put("committedAt", System.currentTimeMillis())
                            ServiceLocator.replica.observeStatus(lists.selected()).first { it.pending == 0 }
                            timing.put("uploadedAt", System.currentTimeMillis())
                            delay(1_000)
                        }
                        measurements.put(timing)
                    }
                    result.put("measurements", measurements)
                    coordinator.foreground(false)
                }
                "recovery-ui" -> {
                    androidx.test.core.app.ActivityScenario.launch<MainActivity>(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                        lateinit var vm: io.theficos.usuals.ui.sharing.SharingViewModel
                        scenario.onActivity { activity ->
                            vm = androidx.lifecycle.ViewModelProvider(activity, io.theficos.usuals.di.UsualsViewModelFactories.sharing())[io.theficos.usuals.ui.sharing.SharingViewModel::class.java]
                            activity.setContent { io.theficos.usuals.ui.theme.UsualsTheme { io.theficos.usuals.ui.sharing.SharingScreen(vm, {}) } }
                        }
                        while (vm.state.value.busy) delay(100)
                        write("ui-ready", "ready")
                        while (!File(directory, "ui-done").isFile) {
                            val key = File(directory, "ui-key")
                            if (key.isFile) {
                                val value = key.readText()
                                if (fixture.optBoolean("clipboardKey")) {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    if (value.isEmpty()) clipboard.clearPrimaryClip()
                                    else clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Synthetic QA key", value).apply {
                                        description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                                    })
                                } else vm.field(io.theficos.usuals.ui.sharing.SharingField.RECOVERY, value)
                                key.delete()
                            }
                            delay(100)
                        }
                    }
                }
                "scan-ui" -> {
                    androidx.test.core.app.ActivityScenario.launch<MainActivity>(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                        lateinit var vm: io.theficos.usuals.ui.list.ListViewModel
                        scenario.onActivity { activity ->
                            vm = androidx.lifecycle.ViewModelProvider(activity, io.theficos.usuals.di.UsualsViewModelFactories.list)[io.theficos.usuals.ui.list.ListViewModel::class.java]
                            vm.onScanned(requireNotNull(Gtin.parse(fixture.optString("gtin", "4006381333931"))))
                        }
                        vm.state.first { it.scanNames.isNotEmpty() }
                        write("ui-ready", "ready")
                        while (!File(directory, "ui-done").isFile) delay(100)
                    }
                }
                "setup" -> {
                    assertTrue(client.households().isEmpty())
                    val prepared = client.prepare(fixture.getString("origin"))
                    val household = client.bootstrap(prepared.verifySaved(prepared.file, prepared.secret), fixture.getString("bootstrap"))
                    val source = lists.create("Sharing QA")
                    items.add(ParsedItem("Synthetic milk", Quantity(1.0, MeasureUnit.LITRE)))
                    write("kit.json", prepared.file.toString(Charsets.UTF_8)); write("recovery-key", prepared.secret)
                    result.put("household", household); result.put("source", source)
                }
                "invite" -> {
                    val household = client.households().single().id
                    write("invitation", client.invitation(household).link())
                    result.put("household", household)
                }
                "request" -> {
                    write("join-code", client.joinCode(client.requestJoin(fixture.getString("invitation"))).orEmpty())
                }
                "approve" -> {
                    val household = client.households().single().id
                    val candidate = client.pending(household).single()
                    require(candidate.code == fixture.getString("joinCode"))
                    client.approve(household, requireNotNull(candidate.verified))
                }
                "complete" -> { assertNotNull(client.completeJoin()); assertFalse(ServiceLocator.sharingCoordinator.syncOnce()) }
                "sync" -> { assertFalse(ServiceLocator.sharingCoordinator.syncOnce()) }
                "rotate" -> { client.rotate(client.households().single().id); assertFalse(ServiceLocator.sharingCoordinator.syncOnce()) }
                "select" -> { lists.select(fixture.getString("list")) }
                "add" -> { items.add(ParsedItem(fixture.getString("name"), if (fixture.has("amount")) Quantity(fixture.getDouble("amount"), MeasureUnit.LITRE) else null)) }
                "forget" -> { client.forgetLocalHousehold(client.households().single().id) }
                "edit" -> {
                    val row = items.items().single { it.name == fixture.getString("name") }
                    if (fixture.has("newName")) items.rename(row.id, fixture.getString("newName"))
                    if (fixture.has("amount")) items.setQuantity(row.id, Quantity(fixture.getDouble("amount"), MeasureUnit.LITRE), row.version)
                }
                "check" -> {
                    val row = items.items().single { it.name == fixture.getString("name") }
                    items.setChecked(row.id, fixture.getBoolean("checked"), row.version)
                }
                "finish" -> {
                    val versions = items.items().filter { it.checked }.map { requireNotNull(it.version) }
                    val trip = history.finish(versions = versions)
                    if (trip != null) write("trip", trip.trip.id)
                }
                "undo" -> { history.undo(File(directory, "trip").readText()) }
                "remove-other" -> {
                    val household = client.households().single().id
                    val me = client.deviceId(household)
                    val device = client.members(household).flatMap { it.devices }.single { it.id != me }
                    client.removeDevice(household, device.id)
                }
                else -> error("Unknown local QA action")
            }
            val scopes = lists.all().filter { it.shared }
            result.put("shared", org.json.JSONArray(scopes.map { JSONObject().put("id", it.id).put("name", it.name) }))
            result.put("pending", scopes.sumOf { ServiceLocator.replica.pending(it.id).size })
            result.put("purchases", history.snapshot().purchases.size)
            result.put("trips", history.snapshot().trips.size)
            result.put("active", org.json.JSONArray(items.items().map { JSONObject().put("id", it.id.value).put("name", it.name).put("checked", it.checked).put("amount", it.quantity?.amount) }))
            write("result.json", result.toString())
        }
        input.delete()
        ServiceLocator.reset()
    }
}
