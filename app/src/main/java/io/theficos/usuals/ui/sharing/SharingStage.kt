package io.theficos.usuals.ui.sharing

/** The one step the Sharing screen shows; everything rare stays under Advanced. */
enum class SharingStage { START, SIGN_IN, PROTECT, READY, JOINING }

fun sharingStage(state: SharingState): SharingStage = when {
    state.joining != null -> SharingStage.JOINING
    state.kit != null -> SharingStage.PROTECT
    state.connecting != null -> SharingStage.SIGN_IN
    state.households.any { !it.removed } -> SharingStage.READY
    else -> SharingStage.START
}
