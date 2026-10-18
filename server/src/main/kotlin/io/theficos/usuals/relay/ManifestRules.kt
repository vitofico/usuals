package io.theficos.usuals.relay

import io.theficos.usuals.sync.*

/** Relay and phones enforce the same signed authority rules. */
internal object ManifestRules {
    fun genesis(signed: SignedManifest, serverId: String): Manifest = validation { MembershipProofs.genesis(signed, serverId) }
    fun transition(signed: SignedManifest, state: Household, caller: Caller, serverId: String): Manifest {
        expect(signed.body().signer == caller.device, "forbidden", 403)
        return validation { MembershipProofs.transition(signed, state.manifest, state.hash, state.genesis, serverId) }
    }
    private inline fun <T> validation(block: () -> T): T = try { block() }
        catch (error: ProtocolViolation) { throw RelayError(error.code, error.status) }
}
