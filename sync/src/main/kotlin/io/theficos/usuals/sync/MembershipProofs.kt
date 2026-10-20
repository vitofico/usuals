package io.theficos.usuals.sync

import java.security.GeneralSecurityException

/** No client-supplied role can authorize its own transition. */
object MembershipProofs {
    fun shape(m: Manifest, serverId: String) {
        expect(m.version == 1 && m.serverId == serverId && m.epoch > 0)
        id(m.householdId); Wire.decode(m.genesisHash, 32); Wire.decode(m.previousHash, 32)
        expect(m.members.size in 1..20 && m.members.map { it.id }.distinct().size == m.members.size)
        expect(m.members.any { it.role == "owner" })
        m.members.forEach { member ->
            id(member.id); expect(member.role in setOf("owner", "editor") && member.devices.size in 1..8)
        }
        val devices = m.members.flatMap { it.devices } + m.recovery
        expect(devices.map { it.id }.distinct().size == devices.size)
        expect(devices.map { it.signingPublicKey }.distinct().size == devices.size)
        expect(devices.map { it.recipientPublicKey }.distinct().size == devices.size)
        devices.forEach { id(it.id); Wire.decode(it.signingPublicKey, 32); Wire.decode(it.recipientPublicKey, 32) }
        expect(m.lists.size <= 20 && m.lists.map { it.listId }.distinct().size == m.lists.size)
        m.lists.forEach { id(it.listId); id(it.snapshotId); Wire.decode(it.head, 32); Wire.decode(it.snapshotHash, 32); expect(it.seq >= 0 && (it.seq != 0L || it.head == Wire.zero)) }
        expect(m.packageHashes.size == m.packageHashes.distinct().size)
        m.packageHashes.forEach { Wire.decode(it, 32) }
    }
    fun genesis(signed: SignedManifest, serverId: String): Manifest {
        val m = signed.body(); shape(m, serverId)
        expect(m.epoch == 1L && m.genesisHash == Wire.zero && m.previousHash == Wire.zero && m.action == "genesis")
        expect(m.members.size == 1 && m.members.single().role == "owner" && m.members.single().devices.size == 1)
        expect(m.lists.isEmpty() && signed.packages.isEmpty() && signed.snapshotRefs.isEmpty() && m.packageHashes.isEmpty() &&
            m.approvedJoins.isEmpty() && m.createdListId == null && m.transferToDeviceId == null && signed.transferAcceptance == null)
        val creator = m.members.single().devices.single(); expect(m.signer == creator.id)
        verify(creator.signingPublicKey, signed.signature, signed.signingBytes())
        return m
    }
    fun transition(signed: SignedManifest, old: Manifest, previousHash: String, genesisHash: String, serverId: String, full: Boolean = true): Manifest {
        return transitionChecked(signed, old, previousHash, genesisHash, serverId, full, true)
    }
    internal fun transferProposal(signed: SignedManifest, acceptance: TransferAcceptance, old: Manifest, previousHash: String, genesisHash: String, serverId: String) {
        expect(signed.body().action == "transfer" && signed.transferAcceptance == null)
        val signer = old.members.firstOrNull { it.role == "owner" && it.devices.any { d -> d.id == signed.body().signer } }
            ?.devices?.first { it.id == signed.body().signer } ?: reject("forbidden", 403)
        verify(signer.signingPublicKey, signed.signature, signed.signingBytes())
        transitionChecked(signed.copy(transferAcceptance = acceptance), old, previousHash, genesisHash, serverId, true, false)
    }
    private fun transitionChecked(signed: SignedManifest, old: Manifest, previousHash: String, genesisHash: String, serverId: String, full: Boolean, verifyOwner: Boolean): Manifest {
        val m = signed.body(); shape(m, serverId)
        val caller = if (m.signer == old.recovery.id) ProofCaller(m.signer, "recovery", old.recovery.id)
            else old.members.firstOrNull { member -> member.role == "owner" && member.devices.any { it.id == m.signer } }
                ?.let { ProofCaller(m.signer, "device", it.id) } ?: reject("forbidden", 403)
        expect(m.householdId == old.householdId && m.genesisHash == genesisHash && m.epoch == old.epoch + 1 && m.previousHash == previousHash,
            "membership_conflict", 409)
        expect(m.signer == caller.device, "forbidden", 403)
        val signing = if (caller.kind == "recovery") old.recovery.also { expect(it.id == caller.device, "forbidden", 403) }
            else old.members.firstOrNull { member -> member.role == "owner" && member.devices.any { it.id == caller.device } }
                ?.devices?.first { it.id == caller.device } ?: reject("forbidden", 403)
        if (verifyOwner) verify(signing.signingPublicKey, signed.signature, signed.signingBytes())
        val oldDevices = old.members.flatMap { it.devices }.associateBy { it.id }
        val newDevices = m.members.flatMap { it.devices }.associateBy { it.id }
        // A registered identity never changes its keys or member by mutation.
        val oldMembers = old.members.flatMap { member -> member.devices.map { it.id to member.id } }.toMap()
        val newMembers = m.members.flatMap { member -> member.devices.map { it.id to member.id } }.toMap()
        expect(oldDevices.keys.intersect(newDevices.keys).all { oldDevices[it] == newDevices[it] && oldMembers[it] == newMembers[it] })
        val precedingKeys = old.members.flatMap { it.devices } + old.recovery
        fun fresh(device: DeviceKeys) = precedingKeys.none {
            it.id == device.id || it.signingPublicKey == device.signingPublicKey || it.recipientPublicKey == device.recipientPublicKey
        }
        if (m.action == "replace_recovery") expect(fresh(m.recovery))
        if (caller.kind == "recovery") {
            expect(m.members.flatMap { it.devices }.all(::fresh), "forbidden", 403)
            expect(m.action == "recover" && m.recovery == old.recovery && m.members.size == 1 && m.members.single().role == "owner" &&
                m.members.single().devices.size == 1 && oldDevices.keys.intersect(newDevices.keys).isEmpty() &&
                m.members.single().id !in old.members.map { it.id } && m.approvedJoins.isEmpty() &&
                m.createdListId == null && m.transferToDeviceId == null && signed.transferAcceptance == null, "forbidden", 403)
        } else when (m.action) {
            "rotate", "create_list" -> expect(m.members == old.members && m.recovery == old.recovery && m.approvedJoins.isEmpty())
            "replace_recovery" -> expect(m.members == old.members && m.recovery != old.recovery && m.recovery.id != old.recovery.id && m.approvedJoins.isEmpty())
            "remove" -> expect(m.recovery == old.recovery && m.approvedJoins.isEmpty() && newDevices.keys.all { it in oldDevices } &&
                m.members.all { member -> old.members.any { it.id == member.id && it.role == member.role } })
            "approve_join" -> {
                expect(m.recovery == old.recovery && m.approvedJoins.size == 1 && newDevices.keys.minus(oldDevices.keys).size == 1 && oldDevices.keys.all { it in newDevices })
                expect(old.members.all { before -> m.members.any { it.id == before.id && it.role == before.role } })
            }
            "transfer" -> {
                expect(m.recovery == old.recovery && m.approvedJoins.isEmpty() && newDevices == oldDevices && m.members.map { it.id }.toSet() == old.members.map { it.id }.toSet())
                val target = m.transferToDeviceId ?: reject()
                val before = old.members.firstOrNull { it.devices.any { d -> d.id == target } } ?: reject()
                expect(before.id != caller.member && m.members.first { it.id == before.id }.role == "owner")
                expect(m.members.all { after -> after.role == old.members.first { it.id == after.id }.role || after.id in setOf(before.id, caller.member) })
                val acceptance = signed.transferAcceptance ?: reject()
                verify(oldDevices.getValue(target).signingPublicKey, acceptance.signature,
                    "USUALS/TRANSFER/1\u0000".toByteArray() + Wire.decode(Wire.hash(Wire.decode(signed.manifestBytes)), 32))
            }
            else -> reject("forbidden", 403)
        }
        if (m.action != "transfer") expect(m.transferToDeviceId == null && signed.transferAcceptance == null)
        if (m.action != "create_list") expect(m.createdListId == null)
        val expectedLists = old.lists.map { it.listId }.toSet() + if (m.action == "create_list") {
            val created = m.createdListId ?: reject(); id(created); expect(old.lists.none { it.listId == created }); setOf(created)
        } else emptySet()
        expect(m.lists.map { it.listId }.toSet() == expectedLists)
        old.lists.forEach { prior ->
            val next = m.lists.single { it.listId == prior.listId }
            expect(next.seq >= prior.seq && (next.seq != prior.seq || next.head == prior.head), "membership_conflict", 409)
        }
        expect(m.packageHashes.size == m.lists.size * (newDevices.size + 1))
        if (!full) return m
        expect(signed.snapshotRefs.size == m.lists.size && signed.snapshotRefs.map { it.listId }.distinct().size == m.lists.size)
        val recipients = (newDevices.values + m.recovery).map { Wire.fingerprint(serverId, m.householdId, it.signingPublicKey, it.recipientPublicKey) }
        val expectedPackages = m.lists.flatMap { list -> recipients.map { list.listId to it } }.toSet()
        expect(signed.packages.size == expectedPackages.size && signed.packages.map { it.listId to it.recipientFingerprint }.toSet() == expectedPackages)
        signed.packages.forEach { p ->
            expect(p.epoch == m.epoch && p.predecessorHash == previousHash && Wire.decode(p.ciphertext).size in 48..4096)
            verify(signing.signingPublicKey, p.signature, p.signingBytes(serverId, m.householdId))
        }
        expect(signed.packages.map { it.hash(serverId, m.householdId) }.toSet() == m.packageHashes.toSet())
        expect(signed.snapshotRefs.all { r -> m.lists.any { it.listId == r.listId && it.snapshotId == r.candidateId && it.snapshotHash == r.hash && it.seq == r.seq && it.head == r.head } })
        return m
    }
}

class ProtocolViolation(val code: String, val status: Int = 400) : RuntimeException(code)
private data class ProofCaller(val device: String, val kind: String, val member: String)
private fun id(text: String) = Wire.uuid(text)
private fun reject(code: String = "invalid", status: Int = 400): Nothing = throw ProtocolViolation(code, status)
private fun expect(condition: Boolean, code: String = "invalid", status: Int = 400) { if (!condition) reject(code, status) }
private fun verify(public: String, signature: String, bytes: ByteArray) {
    try { SyncCrypto.verify(public, signature, bytes) } catch (_: GeneralSecurityException) { reject("forbidden", 403) }
}

/** A verified local anchor, never constructed from an unverified directory response. */
class VerifiedMembership internal constructor(val manifest: Manifest, val hash: String, val genesisHash: String) {
    fun advance(proof: ManifestProof): VerifiedMembership {
        val next = MembershipProofs.transition(proof.signed(), manifest, hash, genesisHash, manifest.serverId, full = false)
        return VerifiedMembership(next, proof.signed().hash(), genesisHash)
    }
    companion object {
        /** Only for the authenticated Keystore cache, never a network response. */
        fun restoreAuthenticated(genesis: ManifestProof, current: ManifestProof): VerifiedMembership {
            val pin = genesis.signed().hash()
            val body = current.signed().body()
            MembershipProofs.genesis(genesis.signed(), body.serverId)
            MembershipProofs.shape(body, body.serverId)
            require(body.householdId == genesis.signed().body().householdId)
            require(if (body.epoch == 1L) current.signed().hash() == pin else body.genesisHash == pin)
            return VerifiedMembership(body, current.signed().hash(), pin)
        }
        fun genesis(proof: ManifestProof, server: ServerInfo, household: String, genesisPin: String): VerifiedMembership {
            require(Wire.hash(Wire.decode(server.receiptPublicKey, 32)) == server.serverId && 1 in server.protocolVersions)
            val body = MembershipProofs.genesis(proof.signed(), server.serverId)
            require(body.householdId == household && proof.signed().hash() == genesisPin)
            return VerifiedMembership(body, genesisPin, genesisPin)
        }
    }
}
