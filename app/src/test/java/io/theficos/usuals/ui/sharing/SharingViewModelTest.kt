package io.theficos.usuals.ui.sharing

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.sync.*
import io.theficos.usuals.ui.list.MainDispatcherRule
import org.junit.Rule
import org.junit.Test

class SharingViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private fun vm(store: SyncStateStore, relay: RecordingRelay) =
        SharingViewModel(HouseholdClient(store, relay, CheckpointSource { _, _ -> error("unused") }), connection = ConnectionClient(store, relay))

    @Test fun approvedConnectionWithNoHouseholdPreparesOnceAndNotAgainAfterRecreation() {
        val store = memoryStore(ClientState(connecting = pendingConnection()))
        val relay = RecordingRelay { path -> when (path) {
            "/v1/connect/$ID/complete" -> ConnectResult(approved = true, userId = "synthetic-user", expiresAt = Long.MAX_VALUE)
            "/v1/server-info" -> server
            else -> null
        } }
        val first = vm(store, relay)
        first.load(); first.settled()
        first.checkConnection()
        val connected = first.settled()
        assertThat(connected.connection?.origin).isEqualTo(ORIGIN)
        assertThat(connected.kit).isNotNull()
        assertThat(sharingStage(connected)).isEqualTo(SharingStage.PROTECT)

        val recreated = vm(store, relay)
        recreated.load()
        assertThat(recreated.settled().kit).isNull()
        assertThat(relay.paths.count { it == "/v1/server-info" }).isEqualTo(1)
    }

    @Test fun savingTheKitVerifiesTheWrittenFileWithoutAPastedKey() {
        val relay = RecordingRelay { path -> if (path == "/v1/server-info") server else null }
        val vm = vm(memoryStore(), relay)
        vm.field(SharingField.ORIGIN, ORIGIN); vm.prepare()
        val kit = requireNotNull(vm.settled().kit)
        vm.saveKit(byteArrayOf(1, 2, 3))
        assertThat(vm.settled().verified).isFalse()
        vm.saveKit(kit.file)
        val saved = vm.settled()
        assertThat(saved.verified).isTrue()
        assertThat(saved.problem).isNull()
        assertThat(saved.recoveryKey).isEmpty()
    }

    @Test fun aRequestThisPhoneCannotVerifyIsRefusedWithoutContactingTheRelay() {
        val relay = RecordingRelay { null }
        val vm = vm(memoryStore(), relay)
        vm.approve(JoinCandidate(ID, "123456", null))
        assertThat(vm.settled().problem).isEqualTo(SharingProblem.OTHER_DEVICE)
        assertThat(relay.paths).isEmpty()
    }

    @Test fun scanningAnotherServerOrAnOldInviteSaysWhatIsWrong() {
        val saved = ServerConnection(ORIGIN, InstanceInfo(name = "Server", server = server, accounts = true, ai = false), "synthetic-user", "synthetic-token", Long.MAX_VALUE)
        val relay = RecordingRelay { null }
        val vm = vm(memoryStore(ClientState(connection = saved)), relay)
        vm.load(); vm.settled()
        vm.scanned("https://other.example/connect")
        assertThat(vm.state.value.problem).isEqualTo(SharingProblem.WRONG_SERVER)
        vm.scanned("usuals://join#" + Wire.encode("{\"origin\":\"$ORIGIN\"}".toByteArray()))
        assertThat(vm.state.value.problem).isEqualTo(SharingProblem.UPGRADE)
        vm.scanned("$ORIGIN/connect")
        assertThat(vm.state.value.problem).isNull()
        assertThat(relay.paths).isEmpty()
    }

    @Test fun openingTheScreenWithAPendingJoinChecksItAtOnce() {
        val relay = RecordingRelay { null }
        val vm = vm(memoryStore(ClientState(joining = pendingJoin())), relay)
        vm.load()
        assertThat(sharingStage(vm.settled())).isEqualTo(SharingStage.JOINING)
        assertThat(relay.paths).contains("/v1/server-info")
    }
}
