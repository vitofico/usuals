package io.theficos.usuals.ui.sharing

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.sync.RecoveryDraft
import org.junit.Test

class SharingStageTest {
    private val kit = object : RecoveryDraft { override val file = byteArrayOf(1); override val secret = "secret" }

    @Test fun nothingSetUpStartsWithTheTwoChoices() {
        assertThat(sharingStage(SharingState())).isEqualTo(SharingStage.START)
        assertThat(sharingStage(SharingState(households = listOf(household(removed = true))))).isEqualTo(SharingStage.START)
    }

    @Test fun aLiveHouseholdIsReady() {
        assertThat(sharingStage(SharingState(households = listOf(household(role = "editor"))))).isEqualTo(SharingStage.READY)
    }

    @Test fun aPendingConnectionAsksToSignIn() {
        assertThat(sharingStage(SharingState(connecting = pendingConnection()))).isEqualTo(SharingStage.SIGN_IN)
        assertThat(sharingStage(SharingState(connecting = pendingConnection(), households = listOf(household())))).isEqualTo(SharingStage.SIGN_IN)
    }

    @Test fun anUnsavedRecoveryKitMustBeProtectedFirst() {
        assertThat(sharingStage(SharingState(kit = kit, households = listOf(household())))).isEqualTo(SharingStage.PROTECT)
    }

    @Test fun joiningWinsOverEverything() {
        val everything = SharingState(joining = pendingJoin(), kit = kit, connecting = pendingConnection(), households = listOf(household()))
        assertThat(sharingStage(everything)).isEqualTo(SharingStage.JOINING)
    }
}
