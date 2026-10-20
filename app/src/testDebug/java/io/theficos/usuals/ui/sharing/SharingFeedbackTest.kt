package io.theficos.usuals.ui.sharing

import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import com.google.common.truth.Truth.assertThat
import androidx.compose.ui.unit.dp
import io.theficos.usuals.sync.*
import io.theficos.usuals.ui.theme.UsualsTheme
import kotlinx.serialization.encodeToString
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class SharingFeedbackTest {
    @get:Rule val compose = createComposeRule()
    @Test fun selectedListIsRetainedThroughConnectionSetupAndScreenRecreation() {
        val store = memoryStore()
        val transport = object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse = error("not connected yet")
        }
        fun vm() = SharingViewModel(HouseholdClient(store, transport, CheckpointSource { _, _ -> error("unused") }),
            choices = { listOf(SharingList("chosen", "Weekend", "local", false), SharingList("other", "Work", "local", false)) })
        val first = vm(); var consumed = false
        var current by androidx.compose.runtime.mutableStateOf(first)
        compose.setContent { androidx.compose.runtime.key(current) { UsualsTheme { SharingScreen(current, {}, sourceList = if (consumed) null else "chosen", onSourceConsumed = { consumed = true }) } } }
        compose.waitUntil(5000) { !first.state.value.busy && first.state.value.sharingSource == "chosen" }
        assertThat(store.read().sharingSource).isEqualTo("chosen")
        val recreated = vm()
        compose.runOnIdle { current = recreated }
        compose.waitForIdle()
        try { compose.waitUntil(5000) { !recreated.state.value.busy && recreated.state.value.sharingSource == "chosen" } }
        catch (failure: Exception) { throw AssertionError("recreated busy=${recreated.state.value.busy}, source=${recreated.state.value.sharingSource}, lists=${recreated.state.value.lists.size}, stored=${store.read().sharingSource}, current=${current === recreated}", failure) }
        compose.onNodeWithText("Ready to share “Weekend”. Connect and create a household, or choose an existing one, to continue.").performScrollTo().assertIsDisplayed()
    }
    @Test fun incomingServerHandoffPrefillsConnectionAfterInitialLoad() {
        val vm = viewModel(); var consumed = false
        val link = "usuals://connect#" + Wire.encode("https://usuals.example".toByteArray())
        compose.setContent { UsualsTheme { SharingScreen(vm, {}, invitation = link, onInvitationConsumed = { consumed = true }) } }
        compose.waitUntil(5000) { consumed && vm.state.value.origin == "https://usuals.example" }
        assertThat(vm.state.value.invitation).isEmpty()
        assertThat(vm.state.value.joining).isNull()
        compose.onNodeWithText("Continue").assertIsDisplayed()
    }
    @Test fun returningFromBrowserSignInIsConsumedWithoutAProblem() {
        val vm = viewModel(); var consumed = false
        compose.setContent { UsualsTheme { SharingScreen(vm, {}, invitation = "usuals://connected", onInvitationConsumed = { consumed = true }) } }
        compose.waitUntil(5000) { consumed && !vm.state.value.busy }
        assertThat(vm.state.value.problem).isNull()
        assertThat(vm.state.value.origin).isEmpty()
    }
    @Test fun recoveryFailureRemainsVisibleAfterScrollingToTheFilePicker() {
        val vm = viewModel()
        compose.setContent { UsualsTheme { SharingScreen(vm, {}, Modifier.height(500.dp)) } }
        compose.waitUntil(5000) { !vm.state.value.busy }
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("Open recovery file").performScrollTo().assertIsDisplayed()
        vm.recover(byteArrayOf(1))
        compose.waitUntil(5000) { vm.state.value.problem != null }
        compose.onNodeWithText("Could not verify these details. Check the file, key or invitation and try again.").assertIsDisplayed()
    }
    @Test fun scanIsVisibleFirstAndRequiresConfirmationBeforeContactingTheServer() {
        val vm = viewModel()
        compose.setContent { UsualsTheme { SharingScreen(vm, {}, Modifier.height(500.dp)) } }
        compose.waitUntil(5000) { !vm.state.value.busy }
        compose.onNodeWithText("Scan the server’s QR code").assertIsDisplayed()
        val id = "00000000-0000-4000-8000-000000000001"
        val uri = InviteCode("https://usuals.example", id, InviteKey.random(), ByteArray(16)).appLink()
        vm.scanned(uri)
        compose.onNodeWithText("Join the household on https://usuals.example?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Join").performScrollTo().assertIsDisplayed()
        assertThat(vm.state.value.joining).isNull()
        vm.scanned("usuals://join#bm90LWpzb24")
        assertThat(vm.state.value.invitation).isEmpty()
        assertThat(vm.state.value.problem).isEqualTo(SharingProblem.VERIFICATION)
        compose.onNodeWithText("Join").assertDoesNotExist()
    }
    @Test fun scannedCodesAreRoutedByKind() {
        val vm = viewModel()
        compose.setContent { UsualsTheme { SharingScreen(vm, {}, Modifier.height(500.dp)) } }
        compose.waitUntil(5000) { !vm.state.value.busy }
        vm.scanned("https://usuals.example/connect")
        assertThat(vm.state.value.origin).isEqualTo("https://usuals.example")
        assertThat(vm.state.value.problem).isNull()
        vm.scanned("https://shop.example/product/42")
        assertThat(vm.state.value.problem).isEqualTo(SharingProblem.NOT_USUALS)
        assertThat(vm.state.value.origin).isEqualTo("https://usuals.example")
        compose.onNodeWithText("This QR code is not a Usuals server or invitation.").assertIsDisplayed()
    }
    @Test fun expiredRequestCanBeReplacedOnlyAfterConfirmationWithoutChangingExistingListsOrConnections() {
        val receipt = SyncCrypto.device()
        val server = ServerInfo(Wire.hash(Wire.decode(receipt.publicKeys.signingPublicKey)), receipt.publicKeys.signingPublicKey)
        val id = "00000000-0000-4000-8000-000000000001"
        val household = "00000000-0000-4000-8000-000000000002"
        val origin = "https://usuals.example"
        val owner = SyncCrypto.device()
        val manifest = Manifest(serverId = server.serverId, householdId = household, genesisHash = Wire.zero,
            epoch = 1, previousHash = Wire.zero, action = "genesis", signer = owner.publicKeys.id,
            members = listOf(RosterMember(id, "owner", listOf(owner.publicKeys))), recovery = receipt.publicKeys)
        val draft = SignedManifest(Wire.encode(Wire.json.encodeToString(manifest).toByteArray()), Wire.encode(ByteArray(64)), emptyList(), emptyList())
        val genesis = ManifestProof(draft.manifestBytes, SyncCrypto.sign(owner, draft.signingBytes()))
        val enrolled = SavedHousehold(origin, server, household, genesis, genesis, owner)
        val digest = Wire.encode(ByteArray(32))
        val invitation = InvitationLink(origin = origin, serverId = server.serverId, householdId = id,
            genesisHash = digest, ownerDeviceId = id, ownerFingerprint = digest, inviteId = id,
            memberId = id, role = "editor", secret = digest, expiresAt = 1)
        val device = SyncCrypto.device()
        val assertion = JoinAssertion(invitation.origin, server.serverId, id, digest, id, id, id,
            device.publicKeys, Wire.hash(Wire.decode(digest)), id, digest, 1)
        val pending = PendingJoinState(invitation, device, assertion, SyncCrypto.sign(device, assertion.bytes()))
        val before = ClientState(households = mapOf(household to enrolled), joining = pending)
        val store = memoryStore(before)
        val lists = listOf(SharingList(id, "Saved groceries", household, true))
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val replacement = "00000000-0000-4000-8000-000000000003"
        val descriptor = Wire.json.encodeToString(InviteDescriptor(origin = origin, serverId = server.serverId, householdId = id, genesisHash = digest,
            ownerDeviceId = id, ownerFingerprint = digest, memberId = id, role = "editor")).toByteArray()
        val code = InviteCode(origin, replacement, InviteKey.random(), Pairing.descriptorAnchor(descriptor))
        val transport = object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse {
                val response = when (path) {
                    "/v1/server-info" -> Wire.json.encodeToString(server)
                    "/v1/invites/$replacement/open" -> Wire.json.encodeToString(OpenedInvite(Wire.encode(descriptor), 3_600_000))
                    "/v1/devices/challenge" -> {
                        val request = Wire.json.decodeFromString<ChallengeRequest>(requireNotNull(body).toString(Charsets.UTF_8))
                        Wire.json.encodeToString(Challenge(id, digest, server.serverId, Long.MAX_VALUE, request.purpose))
                    }
                    "/v1/join-requests" -> {
                        requests.incrementAndGet()
                        val request = Wire.json.decodeFromString<JoinRequest>(requireNotNull(body).toString(Charsets.UTF_8))
                        val joining = JoinAssertion.parse(Wire.decode(request.assertion))
                        return WireResponse(202, Wire.json.encodeToString(JoinPending(joining.requestId, joining.expiresAt)).toByteArray())
                    }
                    else -> throw RelayProblem("forbidden")
                }
                return WireResponse(200, response.toByteArray())
            }
        }
        val vm = SharingViewModel(HouseholdClient(store, transport, CheckpointSource { _, _ -> error("unused") }, clock = { 2 }), choices = { lists })
        compose.setContent { UsualsTheme { SharingScreen(vm, {}, Modifier.height(500.dp)) } }
        compose.waitUntil(5000) { !vm.state.value.busy && vm.state.value.joining != null }
        compose.onNodeWithText("Check approval").performScrollTo().assertIsDisplayed()
        vm.scanned(code.link())
        compose.onNodeWithText("Join").performScrollTo().performClick()
        compose.onNodeWithText("Replace the pending request?").assertIsDisplayed()
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        assertThat(store.read()).isEqualTo(before)
        assertThat(vm.state.value.joining).isEqualTo(pending)
        assertThat(requests.get()).isEqualTo(0)
        compose.onNodeWithText("Join").performScrollTo().performClick()
        compose.onNodeWithText("Replace request").performClick()
        compose.waitUntil(5000) { !vm.state.value.busy && requests.get() == 1 }
        assertThat(store.read().joining!!.assertion.requestId).isNotEqualTo(pending.assertion.requestId)
        assertThat(store.read().households).isEqualTo(before.households)
        assertThat(vm.state.value.lists).isEqualTo(lists)
    }
    @Test fun tappingTheStoredKeySentenceTicksItAndAllowsCreating() {
        val relay = RecordingRelay { path -> if (path == "/v1/server-info") server else null }
        val vm = SharingViewModel(HouseholdClient(memoryStore(), relay, CheckpointSource { _, _ -> error("unused") }))
        vm.field(SharingField.ORIGIN, ORIGIN); vm.prepare()
        vm.saveKit(requireNotNull(vm.settled().kit).file); vm.settled()
        compose.setContent { UsualsTheme { SharingScreen(vm, {}) } }
        compose.onNodeWithText("Create household").assertIsNotEnabled()
        compose.onNodeWithText("I stored the recovery key somewhere safe").performClick()
        compose.onNodeWithText("Create household").assertIsEnabled()
    }
    @Test fun offlineConnectionCanAlwaysRevokeAiConsent() {
        val saved = ServerConnection("https://usuals.example", InstanceInfo(name = "Server",
            server = ServerInfo(Wire.encode(ByteArray(32)), Wire.encode(ByteArray(32))), accounts = true, ai = true),
            "synthetic-user", "synthetic-token", Long.MAX_VALUE, aiEnabled = true, aiRevision = "synthetic-revision")
        val store = memoryStore(ClientState(connection = saved))
        val transport = object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse = throw RelayProblem("unavailable", 503)
        }
        val vm = SharingViewModel(HouseholdClient(store, transport, CheckpointSource { _, _ -> error("unused") }), connection = ConnectionClient(store, transport))
        compose.setContent { UsualsTheme { SharingScreen(vm, {}) } }
        compose.waitUntil(5000) { vm.state.value.connection != null && !vm.state.value.busy }
        compose.onNode(isToggleable()).performScrollTo().assertIsOn().assertIsEnabled().performClick()
        compose.waitUntil(5000) { store.read().connection?.aiEnabled == false }
    }
    private fun memoryStore(initial: ClientState = ClientState()): SyncStateStore = object : SyncStateStore {
            private var value = initial
            override fun read() = value
            override fun update(change: (ClientState) -> ClientState) = change(value).also { value = it }
            override fun reserve(scope: String) { error("unused") }
            override fun saveBlob(id: String, bytes: ByteArray) { error("unused") }
            override fun readBlob(id: String): ByteArray? = error("unused")
            override fun removeBlob(id: String) { error("unused") }
    }
    private fun viewModel(): SharingViewModel {
        val transport = object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse = error("Preview and malformed input must not access the network")
        }
        return SharingViewModel(HouseholdClient(memoryStore(), transport, CheckpointSource { _, _ -> error("unused") }))
    }
}
