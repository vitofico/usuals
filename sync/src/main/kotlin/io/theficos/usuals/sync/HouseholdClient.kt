package io.theficos.usuals.sync

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class HouseholdClient(private val store: SyncStateStore, private val transport: RelayTransport,
    private val checkpoints: CheckpointSource, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    fun sharingSource() = store.read().sharingSource
    fun pendingShares() = store.read().pendingShares.values.toList()
    fun rememberSharingSource(source: String?) { store.update { it.copy(sharingSource = source) } }
    fun reserveShare(household: String, source: String, includeHistory: Boolean): String {
        val key = "$household:$source"
        val state = store.update { state ->
            val previous = state.pendingShares[key]
            require(previous == null || previous.includeHistory == includeHistory)
            if (previous != null) state else state.copy(pendingShares = state.pendingShares +
                (key to PendingShare(household, source, UUID.randomUUID().toString(), includeHistory)))
        }
        return requireNotNull(state.pendingShares[key]).target
    }
    fun completeShare(household: String, target: String) { store.update { state ->
        val completed = state.pendingShares.values.firstOrNull { it.household == household && it.target == target }
        state.copy(pendingShares = state.pendingShares.filterValues { it != completed },
            sharingSource = if (state.sharingSource == completed?.source) null else state.sharingSource)
    } }
    private data class Authorization(val token: String, val normal: SavedHousehold? = null) {
        override fun toString() = "Authorization([redacted])"
    }
    suspend fun previewFrozen(input: java.io.InputStream, kitFile: ByteArray? = null, secret: String? = null): FrozenCopy = mutex.withLock {
        val kit = kitFile?.let { RecoveryKits.open(it, requireNotNull(secret)) }
        val state = try { store.read() } catch (error: Exception) {
            if (kit == null || error is kotlinx.coroutines.CancellationException) throw error
            // An explicitly verified kit can recover facts even when old private pins cannot be read.
            null
        }
        FrozenArchive.read(input) { inventory ->
            val cached = state?.households?.get(inventory.household)
            val origin = kit?.origin ?: cached?.origin ?: throw RelayProblem("frozen_key_required")
            val server = kit?.server ?: requireNotNull(cached).server
            val genesis = kit?.genesis?.signed()?.hash() ?: requireNotNull(cached).genesisHash
            require(cached == null || cached.origin == origin && cached.server == server && cached.genesisHash == genesis)
            require(kit == null || kit.household == inventory.household)
            val memberships = mutableMapOf<Long, String>()
            fun pin(proof: ManifestProof) {
                val epoch = proof.signed().body().epoch; val hash = proof.signed().hash()
                require(memberships[epoch] == null || memberships[epoch] == hash); memberships[epoch] = hash
            }
            cached?.let { pin(it.genesis); it.previous?.let(::pin); pin(it.current) }
            kit?.let { pin(it.genesis); it.anchor.forEach(::pin) }
            val heads = cached?.receiptPins?.get(inventory.list).orEmpty().toMutableMap()
            cached?.headPins?.get(inventory.list)?.let { pin -> require(heads[pin.seq] == null || heads[pin.seq] == pin.head); heads[pin.seq] = pin.head }
            FrozenTrust(origin, server, inventory.household, genesis, kit?.recovery ?: requireNotNull(cached).device, memberships, heads, ancestryUnavailable = state == null)
        }
    }
    suspend fun forgetLocalHousehold(household: String) = mutex.withLock {
        val old = store.read().households[household] ?: return@withLock
        store.update { it.copy(households = it.households - household) }
        old.pending?.candidates?.values?.forEach(store::removeBlob)
        // Reservations remain durable even after an identity is explicitly forgotten.
    }
    suspend fun resetUnavailable(clear: () -> Boolean): Boolean = mutex.withLock { clear() }
    suspend fun hintAccess(household: String): RelayAccess = mutex.withLock {
        val saved = refresh(household)
        if (saved.removed) throw RelayProblem("forbidden", 403)
        RelayAccess(saved.origin, saved.id, session(saved).token)
    }
    fun members(household: String): List<RosterMember> = saved(household).current.signed().body().members
    fun deviceFingerprints(household: String): Map<String, String> {
        val saved = saved(household)
        return saved.current.signed().body().members.flatMap { it.devices }.associate { device ->
            device.id to Wire.decode(Wire.fingerprint(saved.server.serverId, household, device.signingPublicKey, device.recipientPublicKey), 32)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
    fun deviceId(household: String): String = saved(household).device.publicKeys.id
    suspend fun prepare(origin: String): NewHousehold = RecoveryKits.prepare(origin, info(origin))
    fun households(): List<HouseholdSummary> = store.read().households.values.map { saved ->
        val m = saved.current.signed().body()
        HouseholdSummary(saved.id, saved.origin, m.members.firstOrNull { member -> member.devices.any { it.id == saved.device.publicKeys.id } }?.role,
            m.epoch, m.lists.map { it.listId }, saved.removed, saved.pending != null)
    }
    suspend fun bootstrap(setup: VerifiedSetup, token: String): String = mutex.withLock {
        Wire.decode(token, 32)
        if (store.read().households.containsKey(setup.genesis.body().householdId)) throw RelayProblem("already_joined")
        val pending = PendingBootstrap(setup.origin, setup.server, setup.genesis, setup.device, token)
        store.update { it.copy(bootstrap = pending) }
        finishBootstrap(pending)
    }
    suspend fun bootstrapAccount(setup: VerifiedSetup, connection: ServerConnection): String = mutex.withLock {
        require(setup.origin == connection.origin && setup.server == connection.info.server)
        val pending = PendingBootstrap(setup.origin, setup.server, setup.genesis, setup.device, connection.token, account = true)
        store.update { it.copy(bootstrap = pending) }
        finishBootstrap(pending)
    }
    suspend fun resumeBootstrap(): String = mutex.withLock { finishBootstrap(store.read().bootstrap ?: throw RelayProblem("not_found")) }
    private suspend fun finishBootstrap(pending: PendingBootstrap): String {
        val body = pending.genesis.body(); val household = body.householdId
        if (store.read().households.containsKey(household)) throw RelayProblem("already_joined")
        require(info(pending.origin) == pending.server)
        var session: Session? = null
        try { session = login(pending.origin, pending.server, pending.device) }
        catch (problem: RelayProblem) { if (problem.code != "forbidden") throw problem }
        if (session == null) {
            val challenge = call<Challenge>(pending.origin, "POST", "/v1/devices/challenge", ChallengeRequest(pending.device.publicKeys.id, "bootstrap", pending.device.publicKeys.signingPublicKey))
            val proof = proof(pending.origin, pending.server, pending.device, challenge, "bootstrap")
            val response = call<Membership>(pending.origin, "POST", if (pending.account) "/v1/account/households" else "/v1/households", CreateHousehold(pending.genesis, proof), Authorization(pending.token), expected = 201)
            require(response.manifests.single().signed().hash() == pending.genesis.hash())
            session = login(pending.origin, pending.server, pending.device)
        }
        val genesis = ManifestProof(pending.genesis.manifestBytes, pending.genesis.signature)
        val cached = SavedHousehold(pending.origin, pending.server, household, genesis, genesis, pending.device, session = session)
        VerifiedMembership.genesis(genesis, pending.server, household, pending.genesis.hash())
        store.update { it.copy(households = it.households + (household to cached), bootstrap = null) }
        refresh(household)
        return household
    }
    suspend fun invitation(household: String, member: String = UUID.randomUUID().toString(), role: String = "editor"): InviteCode = mutex.withLock {
        val saved = refresh(household); val body = saved.current.signed().body()
        require(body.members.any { it.role == "owner" && it.devices.any { d -> d.id == saved.device.publicKeys.id } })
        val token = session(saved)
        val key = InviteKey.random()
        val descriptor = Wire.json.encodeToString(InviteDescriptor.serializer(), InviteDescriptor(origin = saved.origin, serverId = saved.server.serverId,
            householdId = household, genesisHash = saved.genesisHash, ownerDeviceId = saved.device.publicKeys.id, ownerFingerprint = Wire.fingerprint(
                saved.server.serverId, household, saved.device.publicKeys.signingPublicKey, saved.device.publicKeys.recipientPublicKey),
            memberId = member, role = role)).toByteArray()
        val invite = call<Invite>(saved.origin, "POST", "/v1/households/$household/invites", InviteRequest(member, role, Wire.hash(key.admit()), Wire.encode(descriptor)), token)
        require(invite.secret == null && invite.expiresAt > clock() && invite.expiresAt <= clock() + Pairing.MAX_INVITE_LIFETIME)
        store.update { state -> state.copy(invites = state.invites.filterValues { it.expiresAt > clock() } +
            (invite.inviteId to OutgoingInvite(household, key.encoded(), member, role, invite.expiresAt))) }
        InviteCode(saved.origin, invite.inviteId, key, Pairing.descriptorAnchor(descriptor))
    }
    /** Opens any form of invite link; the same link again resumes the attempt already waiting for approval. */
    suspend fun requestJoin(text: String): PendingJoinState = mutex.withLock {
        val code = InviteCode.parse(text)
        store.read().joining?.takeIf { it.invitation.inviteId == code.inviteId }?.let { sendJoin(it); return@withLock it }
        val admit = Wire.encode(code.key.admit())
        val opened = call<OpenedInvite>(code.origin, "POST", "/v1/invites/${code.inviteId}/open", InviteOpen(admit))
        val bytes = Wire.decode(opened.descriptor)
        require(MessageDigest.isEqual(Pairing.descriptorAnchor(bytes), code.anchor))
        val d = Wire.json.decodeFromJsonElement(InviteDescriptor.serializer(), Wire.strictJson(bytes, 2048)).validate(code.origin)
        if (store.read().households.containsKey(d.householdId)) throw RelayProblem("already_joined")
        val server = info(code.origin)
        require(server.serverId == d.serverId && opened.expiresAt > clock() && opened.expiresAt <= clock() + Pairing.MAX_INVITE_LIFETIME)
        val invitation = InvitationLink(2, code.origin, d.serverId, d.householdId, d.genesisHash, d.ownerDeviceId, d.ownerFingerprint, code.inviteId,
            d.memberId, d.role, admit, opened.expiresAt, code.key.encoded())
        val device = SyncCrypto.device()
        val challenge = call<Challenge>(invitation.origin, "POST", "/v1/devices/challenge", ChallengeRequest(device.publicKeys.id, "join", device.publicKeys.signingPublicKey))
        require(challenge.serverId == server.serverId && challenge.purpose == "join")
        val assertion = JoinAssertion(invitation.origin, server.serverId, invitation.householdId, invitation.genesisHash, invitation.inviteId,
            UUID.randomUUID().toString(), invitation.memberId, device.publicKeys, Wire.hash(Wire.decode(invitation.secret, 32)),
            challenge.challengeId, challenge.nonce, invitation.expiresAt)
        val pending = PendingJoinState(invitation, device, assertion, SyncCrypto.sign(device, assertion.bytes()))
        store.update { it.copy(joining = pending) }
        sendJoin(pending)
        pending
    }
    suspend fun resumeJoin(): PendingJoinState = mutex.withLock { (store.read().joining ?: throw RelayProblem("not_found")).also { sendJoin(it) } }
    private suspend fun sendJoin(pending: PendingJoinState) {
        val mac = pending.invitation.key?.let { Pairing.joinMac(InviteKey(Wire.decode(it, 32)).macKey(), pending.assertion.bytes()) }
        val response = call<JoinPending>(pending.invitation.origin, "POST", "/v1/join-requests", JoinRequest(pending.invitation.secret,
            Wire.encode(pending.assertion.bytes()), pending.signature, mac), expected = 202)
        require(response.requestId == pending.assertion.requestId && response.expiresAt == pending.assertion.expiresAt)
    }
    fun bootstrapPending(): Boolean = store.read().bootstrap != null
    fun joining(): PendingJoinState? = store.read().joining
    /** Forgets a join that was denied or expired, so this device can use a new invite. */
    suspend fun cancelJoin() = mutex.withLock { store.update { it.copy(joining = null) } }
    /** The six digits the owner sees beside this request; null for a legacy join that has no invite key. */
    fun joinCode(pending: PendingJoinState): String? =
        pending.invitation.key?.let { Pairing.joinCode(InviteKey(Wire.decode(it, 32)).macKey(), pending.assertion.bytes()) }
    suspend fun completeJoin(): String? = mutex.withLock {
        val pending = store.read().joining ?: return@withLock null
        val invite = pending.invitation; val server = info(invite.origin)
        require(server.serverId == invite.serverId)
        val session = try { login(invite.origin, server, pending.device) }
            catch (problem: RelayProblem) { if (problem.code == "forbidden") return@withLock null else throw problem }
        val chain = chain(invite.origin, server, invite.householdId, Authorization(session.token), invite.genesisHash)
        val approval = chain.first.firstOrNull { proof -> proof.signed().body().approvedJoins[pending.assertion.requestId] == Wire.hash(pending.assertion.bytes()) }
            ?: throw RelayProblem("unverified_join")
        val current = chain.second.manifest
        require(current.members.any { it.id == invite.memberId && it.role == invite.role && pending.device.publicKeys in it.devices })
        // The trusted link's inviter must occur as an authorized owner in this exact chain.
        require(chain.first.any { proof -> proof.signed().body().members.any { member -> member.role == "owner" && member.devices.any {
            it.id == invite.ownerDeviceId && Wire.fingerprint(invite.serverId, invite.householdId, it.signingPublicKey, it.recipientPublicKey) == invite.ownerFingerprint
        } } })
        require(approval.signed().body().epoch <= current.epoch)
        if (store.read().households.containsKey(invite.householdId)) throw RelayProblem("already_joined")
        val saved = SavedHousehold(invite.origin, server, invite.householdId, chain.first.first(), chain.first.last(), pending.device,
            previous = chain.first.dropLast(1).lastOrNull(), session = session)
        val keys = receiveKeys(saved, chain.first, session.token)
        store.update {
            if (saved.id in it.households) throw RelayProblem("already_joined")
            it.copy(households = it.households + (saved.id to saved.copy(keys = keys)), joining = null)
        }
        saved.id
    }
    /** Every waiting request; only those whose MAC proves they hold this device's invite key come back verified. */
    suspend fun pending(household: String): List<JoinCandidate> = mutex.withLock {
        val saved = refresh(household); val invites = store.read().invites; val members = saved.current.signed().body().members
        call<PendingRequests>(saved.origin, "GET", "/v1/households/$household/join-requests", token = session(saved)).requests.map { request ->
            val verified = runCatching {
                val assertion = validateApplicant(saved, request)
                val invite = requireNotNull(invites[assertion.inviteId])
                val key = InviteKey(Wire.decode(invite.key, 32))
                require(invite.household == household && invite.memberId == assertion.memberId && invite.expiresAt == assertion.expiresAt && members.none { it.id == invite.memberId } &&
                    assertion.secretHash == Wire.hash(key.admit()) && Pairing.verifyJoinMac(key.macKey(), assertion.bytes(), requireNotNull(request.mac)))
                VerifiedApplicant(request, assertion, invite.role, Pairing.joinCode(key.macKey(), assertion.bytes()))
            }.getOrNull()
            JoinCandidate(request.requestId, verified?.code, verified)
        }
    }
    suspend fun approve(household: String, verified: VerifiedApplicant, role: String = verified.role) = mutex.withLock {
        val saved = refresh(household); val assertion = validateApplicant(saved, verified.pending)
        val before = saved.current.signed().body(); val invite = store.read().invites[assertion.inviteId]
        // Every invite names a fresh member, so it is spent once that member is in the roster, even when the approval's response was lost.
        require(invite != null && invite.household == household && invite.memberId == assertion.memberId && invite.expiresAt > clock() &&
            before.members.none { it.id == assertion.memberId })
        change(saved, "approve_join", before.members + RosterMember(assertion.memberId, role, listOf(assertion.device)), joins = mapOf(assertion.requestId to Wire.hash(assertion.bytes())))
        store.update { it.copy(invites = it.invites - assertion.inviteId) }
    }
    suspend fun deny(household: String, request: String) = mutex.withLock {
        val saved = refresh(household); Wire.uuid(request)
        val response = authorizedRequest(saved.origin, "POST", "/v1/households/$household/join-requests/$request/deny", byteArrayOf(), session(saved), 1024 * 1024)
        ensure(response, 200)
    }
    suspend fun resume(household: String) = mutex.withLock { refresh(household); Unit }
    suspend fun createList(household: String, list: String) = mutex.withLock {
        Wire.uuid(list)
        val saved = refresh(household)
        require(saved.current.signed().body().lists.none { it.listId == list })
        change(saved, "create_list", saved.current.signed().body().members, created = list)
    }
    suspend fun rotate(household: String) = mutex.withLock {
        val saved = refresh(household)
        change(saved, "rotate", saved.current.signed().body().members)
    }
    suspend fun removeDevice(household: String, device: String) = mutex.withLock {
        val saved = refresh(household)
        val members = saved.current.signed().body().members.map { it.copy(devices = it.devices.filterNot { d -> d.id == device }) }.filter { it.devices.isNotEmpty() }
        change(saved, "remove", members)
    }
    suspend fun prepareReplacement(household: String): ReplacementKit = mutex.withLock {
        val saved = refresh(household)
        require(saved.current.signed().body().members.any { it.role == "owner" && saved.device.publicKeys in it.devices })
        val proofs = chain(saved.origin, saved.server, household, session(saved), saved.genesisHash).first
        require(proofs.last().signed().hash() == saved.hash)
        RecoveryKits.replacement(saved, proofs)
    }
    suspend fun replaceRecovery(replacement: VerifiedReplacement) = mutex.withLock {
        val saved = refresh(replacement.household)
        if (saved.hash != replacement.previous) throw RelayProblem("membership_conflict", 409)
        change(saved, "replace_recovery", saved.current.signed().body().members, recovery = replacement.recovery)
    }
    fun pendingTransfer(household: String): ByteArray? = saved(household).pending?.manifest?.takeIf {
        it.body().action == "transfer" && it.transferAcceptance == null
    }?.let { Wire.json.encodeToString(it).toByteArray() }
    suspend fun prepareTransfer(household: String, targetDevice: String): ByteArray = mutex.withLock {
        val saved = refresh(household); val before = saved.current.signed().body()
        val source = before.members.first { saved.device.publicKeys in it.devices }
        require(source.role == "owner")
        val target = before.members.first { m -> m.devices.any { it.id == targetDevice } }
        require(target.id != source.id)
        val members = before.members.map { if (it.id == target.id) it.copy(role = "owner") else if (it.id == source.id) it.copy(role = "editor") else it }
        change(saved, "transfer", members, transferTo = targetDevice)
        Wire.json.encodeToString(requireNotNull(saved(household).pending?.manifest)).toByteArray()
    }
    suspend fun reviewTransfer(file: ByteArray): TransferReview = mutex.withLock {
        val proposal = Wire.json.decodeFromJsonElement<SignedManifest>(Wire.strictJson(file, 8 * 1024 * 1024))
        val body = proposal.body(); val saved = refresh(body.householdId)
        require(body.transferToDeviceId == saved.device.publicKeys.id && body.previousHash == saved.hash)
        verifyCutoffs(saved, body.lists, session(saved))
        val acceptance = TransferAcceptance(SyncCrypto.sign(saved.device, transferBytes(proposal)))
        MembershipProofs.transferProposal(proposal, acceptance, saved.current.signed().body(), saved.hash, saved.genesisHash, saved.server.serverId)
        TransferReview(saved.id, saved.hash, proposal)
    }
    suspend fun acceptTransfer(review: TransferReview): ByteArray = mutex.withLock {
        val saved = refresh(review.household)
        if (saved.hash != review.previous) throw RelayProblem("membership_conflict", 409)
        require(review.proposal.body().transferToDeviceId == saved.device.publicKeys.id)
        Wire.json.encodeToString(TransferReply(saved.id, Wire.hash(Wire.decode(review.proposal.manifestBytes)),
            SyncCrypto.sign(saved.device, transferBytes(review.proposal)))).toByteArray()
    }
    suspend fun completeTransfer(file: ByteArray) = mutex.withLock {
        val reply = Wire.json.decodeFromJsonElement<TransferReply>(Wire.strictJson(file, 4096))
        val saved = saved(reply.household); val pending = requireNotNull(saved.pending); val proposal = requireNotNull(pending.manifest)
        require(proposal.body().action == "transfer" && proposal.body().previousHash == saved.hash && reply.manifestHash == Wire.hash(Wire.decode(proposal.manifestBytes)))
        val draft = proposal.copy(transferAcceptance = TransferAcceptance(reply.signature))
        val final = draft.copy(signature = SyncCrypto.sign(saved.device, draft.signingBytes()))
        MembershipProofs.transition(final, saved.current.signed().body(), saved.hash, saved.genesisHash, saved.server.serverId)
        store.update { it.copy(households = it.households + (saved.id to saved.copy(pending = pending.copy(manifest = final)))) }
        resumeMembership(saved.id)
        reply.household
    }
    private fun transferBytes(proposal: SignedManifest) = "USUALS/TRANSFER/1\u0000".toByteArray() + Wire.decode(Wire.hash(Wire.decode(proposal.manifestBytes)), 32)
    private suspend fun verifyCutoffs(saved: SavedHousehold, cutoffs: List<ListCutoff>, token: Authorization): Map<String, HeadPin> {
        cutoffs.forEach { c -> require(c.seq >= (saved.receiptPins[c.listId]?.keys?.maxOrNull() ?: 0)); verifyBridge(saved, c.listId, c.seq, c.head, token) }
        return saved.headPins + cutoffs.associate { it.listId to HeadPin(it.seq, it.head) }
    }
    /** Ciphertext receipts prove extension of a known head even across key rotations. */
    private suspend fun verifyBridge(saved: SavedHousehold, list: String, target: Long, targetHead: String, token: Authorization) {
        val pin = saved.headPins[list] ?: return
        require(target >= pin.seq)
        var seq = pin.seq; var head = pin.head
        while (seq < target) {
            val limit = minOf(100L, target - seq)
            val page = try { call<Operations>(saved.origin, "GET", "/v1/lists/$list/operations?after=$seq&limit=$limit", token = token, max = 10 * 1024 * 1024) }
                catch (problem: RelayProblem) { if (problem.code == "base_unknown") throw RelayProblem("history_unverifiable") else throw problem }
            require(page.entries.isNotEmpty() && page.entries.size <= limit)
            page.entries.forEach { entry ->
                val header = entry.envelope.parsed()
                require(header.serverId == saved.server.serverId && header.householdId == saved.id && header.listId == list && header.epoch <= saved.epoch)
                SyncCrypto.verifyReceipt(entry.envelope, entry.receipt, saved.server.receiptPublicKey, seq + 1, head)
                seq = entry.receipt.seq; head = entry.receipt.head
                saved.receiptPins[list]?.get(seq)?.let { require(it == head) }
            }
            require(page.nextCursor == seq)
        }
        require(head == targetHead)
    }
    private fun preservePins(saved: SavedHousehold, state: ClientState): SavedHousehold {
        val durable = state.households[saved.id]
        val existing = durable?.headPins.orEmpty()
        val session = if (durable != null && durable.origin == saved.origin && durable.server == saved.server && durable.device.publicKeys == saved.device.publicKeys) durable.session else saved.session
        return saved.copy(session = session, receiptPins = state.households[saved.id]?.receiptPins ?: saved.receiptPins, headPins = (saved.headPins.keys + existing.keys).associateWith { list ->
            val a = saved.headPins[list]; val b = existing[list]
            if (a != null && b != null && a.seq == b.seq) require(a.head == b.head)
            if (a == null || b != null && b.seq > a.seq) requireNotNull(b) else a
        })
    }
    /** A valid or ambiguous change is retried, never silently cancelled. */
    suspend fun discardInvalidPending(household: String): Boolean = mutex.withLock {
        var saved = saved(household); val pending = saved.pending ?: return@withLock false
        var invalid = pending.manifest == null || pending.manifest.body().action == "transfer" && pending.manifest.transferAcceptance == null
        if (!invalid) {
            val page = call<Membership>(saved.origin, "GET", "/v1/households/$household/membership?afterEpoch=${saved.epoch}", token = session(saved), max = 8 * 1024 * 1024)
            if (page.manifests.isNotEmpty()) {
                require(page.manifests.size == 1)
                val proof = page.manifests.single()
                val verified = VerifiedMembership.restoreAuthenticated(saved.genesis, saved.current).advance(proof)
                val pins = verifyCutoffs(saved, verified.manifest.lists, session(saved))
                if (proof.signed().hash() == requireNotNull(pending.manifest).hash()) { resumeMembership(household); return@withLock false }
                saved = saved.copy(previous = saved.current, current = proof, headPins = pins,
                    removed = verified.manifest.members.none { saved.device.publicKeys in it.devices })
                invalid = true
            } else for (cutoff in requireNotNull(pending.manifest).body().lists) {
                if (cutoff.listId !in saved.current.signed().body().lists.map { it.listId }) continue
                val checkpoint = remoteCheckpoint(saved, cutoff.listId)
                require(checkpoint.seq >= cutoff.seq)
                if (checkpoint.seq > cutoff.seq) invalid = true else require(checkpoint.head == cutoff.head)
            }
        }
        if (!invalid) throw RelayProblem("membership_conflict", 409)
        store.update { state -> state.copy(households = state.households + (household to preservePins(saved.copy(pending = null), state))) }
        pending.candidates.values.forEach(store::removeBlob)
        true
    }
    private fun saved(household: String) = store.read().households[household] ?: throw RelayProblem("not_found")
    private fun validateApplicant(saved: SavedHousehold, p: PendingJoin): JoinAssertion {
        val a = JoinAssertion.parse(Wire.decode(p.assertion))
        require(a.requestId == p.requestId && a.origin == saved.origin && a.serverId == saved.server.serverId && a.householdId == saved.id && a.genesisHash == saved.genesisHash)
        SyncCrypto.verify(a.device.signingPublicKey, p.signature, a.bytes())
        require(a.expiresAt > clock())
        return a
    }
    private suspend fun info(origin: String): ServerInfo = call<ServerInfo>(origin, "GET", "/v1/server-info").also {
        require(Wire.hash(Wire.decode(it.receiptPublicKey, 32)) == it.serverId && 1 in it.protocolVersions)
    }
    private fun proof(origin: String, server: ServerInfo, device: DeviceSecrets, challenge: Challenge, purpose: String): Proof {
        require(challenge.serverId == server.serverId && challenge.purpose == purpose)
        return Proof(challenge.challengeId, device.publicKeys.id, SyncCrypto.sign(device,
            Wire.auth(origin, server.serverId, device.publicKeys.id, challenge.challengeId, challenge.nonce, challenge.expiresAt, purpose, device.publicKeys.signingPublicKey)))
    }
    private suspend fun login(origin: String, server: ServerInfo, device: DeviceSecrets, purpose: String = "session"): Session {
        val challenge = call<Challenge>(origin, "POST", "/v1/devices/challenge", ChallengeRequest(device.publicKeys.id, purpose))
        return call(origin, "POST", "/v1/devices/session", proof(origin, server, device, challenge, purpose))
    }
    private suspend fun session(saved: SavedHousehold): Authorization {
        require(saved.device.publicKeys != saved.current.signed().body().recovery)
        if (saved.removed) throw RelayProblem("forbidden", 403)
        val latest = store.read().households[saved.id]?.takeIf { it.origin == saved.origin && it.server == saved.server && it.device.publicKeys == saved.device.publicKeys } ?: saved
        if (latest.removed) throw RelayProblem("forbidden", 403)
        val value = latest.session?.takeIf { it.expiresAt > clock() + 30000 } ?: login(saved.origin, saved.server, saved.device).also { token ->
            store.update { state -> state.copy(households = state.households + (saved.id to (state.households[saved.id] ?: saved).copy(session = token))) }
        }
        return Authorization(value.token, latest)
    }
    private suspend fun refresh(household: String): SavedHousehold {
        var saved = saved(household)
        if (saved.pending != null) { resumeMembership(household); saved = saved(household) }
        var verified = VerifiedMembership.restoreAuthenticated(saved.genesis, saved.current)
        val token = session(saved)
        while (true) {
            val page = call<Membership>(saved.origin, "GET", "/v1/households/$household/membership?afterEpoch=${verified.manifest.epoch}", token = token, max = 8 * 1024 * 1024)
            if (page.manifests.isEmpty()) break
            require(page.manifests.size == 1)
            val proof = page.manifests.single(); verified = verified.advance(proof)
            val pins = verifyCutoffs(saved, verified.manifest.lists, token)
            saved = saved.copy(previous = saved.current, current = proof, headPins = pins)
            val retained = verified.manifest.members.any { member -> saved.device.publicKeys in member.devices }
            if (!retained) saved = saved.copy(removed = true)
            store.update { state -> state.copy(households = state.households + (household to preservePins(saved, state))) }
        }
        if (!saved.removed && saved.current.signed().body().lists.any { "${it.listId}:${saved.epoch}" !in saved.keys }) {
            val keys = receiveKeys(saved, listOfNotNull(saved.previous, saved.current))
            saved = saved.copy(keys = keys)
            store.update { state -> state.copy(households = state.households + (household to preservePins(saved, state))) }
        }
        return saved
    }
    private suspend fun chain(origin: String, server: ServerInfo, household: String, token: Authorization, genesis: String): Pair<List<ManifestProof>, VerifiedMembership> {
        val proofs = mutableListOf<ManifestProof>(); var verified: VerifiedMembership? = null
        while (true) {
            val page = call<Membership>(origin, "GET", "/v1/households/$household/membership?afterEpoch=${verified?.manifest?.epoch ?: 0}", token = token, max = 8 * 1024 * 1024)
            if (page.manifests.isEmpty()) break
            require(page.manifests.size == 1)
            val proof = page.manifests.single()
            verified = verified?.advance(proof) ?: VerifiedMembership.genesis(proof, server, household, genesis)
            proofs += proof
        }
        return proofs to requireNotNull(verified)
    }
    private suspend fun receiveKeys(saved: SavedHousehold, proofs: List<ManifestProof>, restrictedToken: String? = null): Map<String, String> {
        val body = saved.current.signed().body(); val prior = proofs.firstOrNull { it.signed().body().epoch == body.epoch - 1 }?.signed()?.body()
        val signer = if (body.signer == prior?.recovery?.id) prior.recovery else prior?.members?.flatMap { it.devices }?.firstOrNull { it.id == body.signer }
        if (body.lists.isEmpty()) return saved.keys
        requireNotNull(signer)
        val token = restrictedToken?.let { Authorization(it) } ?: session(saved)
        return saved.keys + body.lists.associate { list ->
            val packages = call<KeyPackages>(saved.origin, "GET", "/v1/households/${saved.id}/key-packages?listId=${list.listId}&epoch=${body.epoch}", token = token)
            val p = packages.packages.single()
            require(p.listId == list.listId && p.epoch == body.epoch && p.predecessorHash == body.previousHash && p.hash(saved.server.serverId, saved.id) in body.packageHashes)
            "${list.listId}:${body.epoch}" to Wire.encode(SyncCrypto.unwrap(saved.server.serverId, saved.id, p, saved.device, signer.signingPublicKey))
        }
    }
    /** Only confirmed Room state at an exact wrapped pin may skip snapshot retrieval. */
    suspend fun synchronize(household: String, list: String, replica: SharedReplica): SyncProgress = mutex.withLock {
        var saved = refresh(household)
        if (saved.removed) throw RelayProblem("forbidden", 403)
        val cached = replica.confirmed(list)
        val base = cached?.takeIf { it.epoch == saved.epoch && it.manifestHash == saved.hash &&
            saved.headPins[list] == HeadPin(it.checkpoint.seq, it.checkpoint.head) }?.checkpoint
        val checkpoint = remoteCheckpoint(saved, list, base = base)
        replica.confirm(list, checkpoint, saved.epoch, saved.hash)
        saved = saved(household)
        val pending = replica.pending(list)
        if (pending.isEmpty()) return@withLock SyncProgress(0, false)
        val key = Wire.decode(saved.keys["$list:${saved.epoch}"] ?: throw RelayProblem("needs_key"), 32)
        val encoded = mutableListOf<ByteArray>(); val envelopes = mutableListOf<Envelope>()
        var size = 16
        for (command in pending.take(100)) {
            require(command.intent.listId == list)
            val bytes = Wire.json.encodeToString(command.intent).toByteArray()
            if (bytes.size + 288 > 65536) throw RelayProblem("payload_too_large")
            val previous = command.envelope?.let { Wire.json.decodeFromJsonElement<Envelope>(Wire.strictJson(it, 100000)) }
            val usable = previous?.takeIf { envelope ->
                val header = envelope.parsed()
                header.serverId == saved.server.serverId && header.householdId == household && header.listId == list &&
                    header.authorDeviceId == saved.device.publicKeys.id && header.intentId == command.intent.id &&
                    header.epoch == saved.epoch && header.manifestHash == saved.hash
            }
            val envelope: Envelope
            val wire: ByteArray
            if (usable != null) {
                require(SyncCrypto.open(usable, key, saved.device.publicKeys.signingPublicKey).contentEquals(bytes))
                envelope = usable; wire = requireNotNull(command.envelope)
            } else {
                val header = Header(saved.server.serverId, household, list, saved.device.publicKeys.id, command.intent.id,
                    UUID.randomUUID().toString(), saved.epoch, saved.hash, checkpoint.seq, checkpoint.head)
                envelope = SyncCrypto.seal(header, bytes, key, saved.device) {
                    store.reserve("$household:$list:${saved.epoch}:${saved.device.publicKeys.id}:${Wire.hash(key)}")
                }
                wire = Wire.json.encodeToString(envelope).toByteArray()
                replica.saveEnvelope(list, command.intent, wire)
            }
            if (size + wire.size + 1 > 1024 * 1024) break
            size += wire.size + 1; encoded += wire; envelopes += envelope
        }
        require(envelopes.isNotEmpty())
        val body = java.io.ByteArrayOutputStream().apply {
            write("{\"envelopes\":[".toByteArray())
            encoded.forEachIndexed { index, bytes -> if (index > 0) write(','.code); write(bytes) }
            write("]}".toByteArray())
        }.toByteArray()
        require(body.size <= 1024 * 1024)
        val response = authorizedRequest(saved.origin, "POST", "/v1/lists/$list/operations", body, session(saved), 1024 * 1024)
        if (response.status == 409) {
            val code = runCatching { Wire.strictJson(response.body, 65536).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull()
            if (code == "intent_exists") {
                // A prior epoch or an ambiguous acknowledgement may already contain this stable intent.
                val caught = remoteCheckpoint(saved(household), list, base = checkpoint)
                replica.confirm(list, caught, saved.epoch, saved.hash)
                return@withLock SyncProgress(0, replica.pending(list).isNotEmpty())
            }
        }
        ensure(response, 200)
        val result = Wire.json.decodeFromJsonElement<AppendResults>(Wire.strictJson(response.body, 1024 * 1024))
        require(result.results.size == envelopes.size)
        result.results.forEachIndexed { index, entry ->
            val envelope = envelopes[index]; val header = envelope.parsed()
            require(entry.index == index && entry.intentId == header.intentId && entry.envelopeId == header.envelopeId && entry.status in setOf("accepted", "duplicate"))
            require(entry.receipt.seq > checkpoint.seq)
            SyncCrypto.verifyReceipt(envelope, entry.receipt, saved.server.receiptPublicKey, entry.receipt.seq, entry.receipt.previousHead)
        }
        store.update { state ->
            val latest = state.households.getValue(household)
            val pins = latest.receiptPins[list].orEmpty().toMutableMap()
            result.results.forEach { entry ->
                val receipt = entry.receipt
                val prior = latest.headPins[list]
                if (prior != null && receipt.seq == prior.seq) require(receipt.head == prior.head)
                pins[receipt.seq]?.let { require(it == receipt.head) }
                if (prior == null || receipt.seq > prior.seq) pins[receipt.seq] = receipt.head
            }
            state.copy(households = state.households + (household to latest.copy(receiptPins = latest.receiptPins + (list to pins))))
        }
        val after = remoteCheckpoint(saved(household), list, base = checkpoint)
        replica.confirm(list, after, saved.epoch, saved.hash)
        SyncProgress(envelopes.size, replica.pending(list).isNotEmpty())
    }
    suspend fun download(household: String, list: String): ReplicaCheckpoint = mutex.withLock {
        val saved = refresh(household)
        ReplicaCheckpoint(remoteCheckpoint(saved, list), saved.epoch, saved.hash)
    }
    suspend fun checkpoint(household: String, list: String): ListCheckpoint = mutex.withLock { remoteCheckpoint(refresh(household), list) }
    private suspend fun remoteCheckpoint(saved: SavedHousehold, list: String, tokenOverride: String? = null, base: ListCheckpoint? = null): ListCheckpoint {
        val m = saved.current.signed().body(); val previous = saved.previous?.signed()?.body()
        require(m.lists.any { it.listId == list })
        val key = Wire.decode(saved.keys["$list:${m.epoch}"] ?: throw RelayProblem("needs_key"), 32)
        val token = tokenOverride?.let { Authorization(it) } ?: session(saved)
        val initial = base ?: run {
            val snapshot = call<Snapshot>(saved.origin, "GET", "/v1/lists/$list/snapshot", token = token, max = 10 * 1024 * 1024)
            val header = snapshot.envelope.parsed()
            require(header.serverId == saved.server.serverId && header.householdId == saved.id && header.listId == list &&
                header.epoch == m.epoch && header.baseSeq == snapshot.seq && header.baseHead == snapshot.head)
            if (snapshot.seq >= (saved.headPins[list]?.seq ?: 0)) verifyBridge(saved, list, snapshot.seq, snapshot.head, token)
            val author = if (header.manifestHash == saved.hash) {
                m.members.firstOrNull { it.role == "owner" && it.devices.any { d -> d.id == header.authorDeviceId } }?.devices?.first { it.id == header.authorDeviceId }
            } else {
                require(header.manifestHash == m.previousHash && header.authorDeviceId == m.signer && m.lists.any {
                    it.listId == list && it.seq == snapshot.seq && it.head == snapshot.head && it.snapshotHash == snapshot.envelope.digest()
                })
                if (m.action == "recover" && previous?.recovery?.id == header.authorDeviceId) previous.recovery else
                    previous?.members?.firstOrNull { it.role == "owner" && it.devices.any { d -> d.id == header.authorDeviceId } }?.devices?.first { it.id == header.authorDeviceId }
            } ?: throw RelayProblem("unverified_snapshot")
            val projection = SnapshotCodec.decode(SyncCrypto.open(snapshot.envelope, key, author.signingPublicKey, 10 * 1024 * 1024))
            ListCheckpoint(snapshot.seq, snapshot.head, SnapshotCodec.encode(projection))
        }
        var projection = SnapshotCodec.decode(initial.payload)
        var seq = initial.seq; var head = initial.head
        val reducer = Reducer(io.theficos.usuals.model.NORMALIZER_VERSION)
        while (true) {
            val page = call<Operations>(saved.origin, "GET", "/v1/lists/$list/operations?after=$seq&limit=100", token = token, max = 10 * 1024 * 1024)
            for (entry in page.entries) {
                val h = entry.envelope.parsed()
                require(h.serverId == saved.server.serverId && h.householdId == saved.id && h.listId == list && h.epoch == m.epoch && h.manifestHash == saved.hash)
                val signer = m.members.flatMap { it.devices }.firstOrNull { it.id == h.authorDeviceId } ?: throw RelayProblem("unverified_author")
                SyncCrypto.verifyReceipt(entry.envelope, entry.receipt, saved.server.receiptPublicKey, seq + 1, head)
                val bytes = SyncCrypto.open(entry.envelope, key, signer.signingPublicKey)
                val intent = Wire.json.decodeFromJsonElement<Intent>(Wire.strictJson(bytes, 65536, payload = true))
                require(intent.id == h.intentId && intent.listId == list)
                val reduced = reducer.apply(projection, intent)
                if (reduced.outcome == Outcome.UPGRADE_REQUIRED) throw UnsupportedWireVersion()
                projection = reduced.projection; seq = entry.receipt.seq; head = entry.receipt.head
                saved.headPins[list]?.takeIf { it.seq == seq }?.let { require(it.head == head) }
                saved.receiptPins[list]?.get(seq)?.let { require(it == head) }
            }
            require(page.nextCursor == seq && (!page.hasMore || page.entries.isNotEmpty()))
            if (!page.hasMore) break
        }
        val pin = saved.headPins[list]
        require(pin == null || seq > pin.seq || seq == pin.seq && head == pin.head)
        require(seq >= (saved.receiptPins[list]?.keys?.maxOrNull() ?: 0))
        val stored = store.read().households[saved.id]
        if (stored != null) store.update { state ->
            val latest = state.households.getValue(saved.id)
            val prior = latest.headPins[list]
            require(prior == null || seq > prior.seq || seq == prior.seq && head == prior.head)
            state.copy(households = state.households + (saved.id to latest.copy(headPins = latest.headPins + (list to HeadPin(seq, head)), receiptPins = latest.receiptPins - list)))
        }
        return ListCheckpoint(seq, head, SnapshotCodec.encode(projection))
    }
    suspend fun recover(file: ByteArray, secret: String): String = mutex.withLock {
        val kit = RecoveryKits.open(file, secret)
        require(info(kit.origin) == kit.server)
        val old = store.read().households[kit.household]
        require(old == null || old.origin == kit.origin && old.server == kit.server && old.genesisHash == kit.genesis.signed().hash())
        if (old?.pending?.draft?.action == "recover") {
            try { resumeMembership(kit.household); return@withLock kit.household }
            catch (problem: RelayProblem) { if (problem.code != "forbidden") throw problem }
        }
        val recoverySession = login(kit.origin, kit.server, kit.recovery, "recovery")
        val verified = chain(kit.origin, kit.server, kit.household, Authorization(recoverySession.token), kit.genesis.signed().hash())
        require(verified.second.manifest.recovery == kit.recovery.publicKeys)
        if (old != null) require(verified.second.manifest.epoch >= old.epoch &&
            verified.first.firstOrNull { it.signed().body().epoch == old.epoch }?.signed()?.hash() == old.hash)
        val authority = SavedHousehold(kit.origin, kit.server, kit.household, verified.first.first(), verified.first.last(), kit.recovery,
            previous = verified.first.dropLast(1).lastOrNull(), session = recoverySession, headPins = old?.headPins.orEmpty(), receiptPins = old?.receiptPins.orEmpty())
        if (old != null && authority.epoch > old.epoch) verifyCutoffs(authority, verified.second.manifest.lists, Authorization(recoverySession.token))
        val recoveryKeys = receiveKeys(authority, verified.first, recoverySession.token)
        val reader = authority.copy(keys = recoveryKeys)
        val interrupted = old?.takeIf { it.pending?.draft?.action == "recover" }
        val advanced = interrupted != null && authority.epoch > interrupted.epoch
        val committedPreviously = interrupted?.pending?.manifest?.hash()?.let { hash -> verified.first.any { it.signed().hash() == hash } } == true
        val owner = if (committedPreviously) SyncCrypto.device() else interrupted?.device ?: SyncCrypto.device()
        val future = reader.copy(device = owner, session = null)
        if (interrupted != null && !advanced) {
            // No recovery private key is written to the vault, including interrupted recovery.
            resumeMembership(kit.household, kit.recovery, recoverySession.token, source = { list -> remoteCheckpoint(reader, list, recoverySession.token) })
        } else {
            // A verified newer epoch makes the interrupted old CAS permanently unusable.
            change(future, "recover", listOf(RosterMember(UUID.randomUUID().toString(), "owner", listOf(owner.publicKeys))),
                authority = kit.recovery, recoveryToken = recoverySession.token, source = { list -> remoteCheckpoint(reader, list, recoverySession.token) })
            interrupted?.pending?.candidates?.values?.forEach(store::removeBlob)
        }
        kit.household
    }
    private suspend fun authorizedRequest(origin: String, method: String, path: String, bytes: ByteArray?, token: Authorization?, max: Int): WireResponse {
        val normal = token?.normal
        require(normal == null || normal.origin == origin)
        // A normal request retains its identity context across pages, not a stale bearer string.
        val current = normal?.let { session(it).token } ?: token?.token
        val response = transport.request(origin, method, path, bytes, current, max)
        if (response.status !in setOf(401, 403) || normal == null) return response
        val latest = store.read().households[normal.id] ?: return response
        if (latest.removed || latest.device.publicKeys != normal.device.publicKeys || latest.origin != normal.origin || latest.server != normal.server) return response
        store.update { state ->
            val saved = state.households.getValue(normal.id)
            state.copy(households = state.households + (saved.id to if (saved.session?.token == current) saved.copy(session = null) else saved))
        }
        return transport.request(origin, method, path, bytes, session(saved(normal.id)).token, max)
    }
    private suspend inline fun <reified R> call(origin: String, method: String, path: String, body: Any? = null, token: Authorization? = null,
        expected: Int = 200, max: Int = 1024 * 1024): R {
        val bytes = body?.let { encodeBody(it) }
        val response = authorizedRequest(origin, method, path, bytes, token, max)
        ensure(response, expected)
        return Wire.json.decodeFromJsonElement(Wire.strictJson(response.body, max))
    }
    private fun encodeBody(value: Any): ByteArray = when (value) {
        is ChallengeRequest -> Wire.json.encodeToString(value)
        is Proof -> Wire.json.encodeToString(value)
        is CreateHousehold -> Wire.json.encodeToString(value)
        is InviteRequest -> Wire.json.encodeToString(value)
        is InviteOpen -> Wire.json.encodeToString(value)
        is JoinRequest -> Wire.json.encodeToString(value)
        is Snapshot -> Wire.json.encodeToString(value)
        is SignedManifest -> Wire.json.encodeToString(value)
        else -> error("unsupported")
    }.toByteArray()
    private fun ensure(response: WireResponse, expected: Int) {
        if (response.status != expected) {
            val code = runCatching { Wire.strictJson(response.body, 65536).jsonObject.getValue("code").jsonPrimitive.content }.getOrDefault("invalid")
            throw RelayProblem(code.takeIf { it in setOf("unauthenticated", "forbidden", "not_found", "epoch_stale", "membership_conflict", "candidate_expired", "intent_conflict", "intent_exists", "envelope_conflict", "base_unknown", "recovery_required", "rate_limited", "quota_exceeded", "too_large", "invalid", "unavailable") } ?: "invalid", response.status)
        }
    }
    // Membership staging/commit is implemented below; the original local list is untouched.
    private suspend fun change(saved: SavedHousehold, action: String, members: List<RosterMember>, joins: Map<String, String> = emptyMap(), created: String? = null,
        transferTo: String? = null, recovery: DeviceKeys = saved.current.signed().body().recovery, authority: DeviceSecrets = saved.device, recoveryToken: String? = null, source: (suspend (String) -> ListCheckpoint)? = null) {
        if (saved.pending != null) throw RelayProblem("membership_conflict", 409)
        val before = saved.current.signed().body()
        val lists = before.lists + listOfNotNull(created?.let { ListCutoff(it, 0, Wire.zero, UUID.randomUUID().toString(), Wire.zero) })
        val newKeys = lists.associate { it.listId to Wire.encode(SyncCrypto.randomKey()) }
        val draft = before.copy(epoch = before.epoch + 1, genesisHash = saved.genesisHash, previousHash = saved.hash,
            action = action, signer = authority.publicKeys.id, members = members, recovery = recovery, lists = lists, packageHashes = emptyList(), approvedJoins = joins,
            createdListId = created, transferToDeviceId = transferTo)
        store.update { state -> state.copy(households = state.households + (saved.id to saved.copy(pending = PendingMembership(null, draft, newKeys)))) }
        resumeMembership(saved.id, authority, recoveryToken, source)
    }
    private suspend fun resumeMembership(household: String, authorityOverride: DeviceSecrets? = null, tokenOverride: String? = null,
        source: (suspend (String) -> ListCheckpoint)? = null, retryExpired: Boolean = true) {
        var saved = saved(household); var pending = saved.pending ?: return
        val authority = authorityOverride ?: saved.device
        val token = tokenOverride?.let { Authorization(it) } ?: session(saved)
        val existing = call<Membership>(saved.origin, "GET", "/v1/households/$household/membership?afterEpoch=${saved.epoch}", token = token, max = 8 * 1024 * 1024)
        if (existing.manifests.isNotEmpty()) {
            if (existing.manifests.size != 1 || pending.manifest == null || existing.manifests.single().signed().hash() != requireNotNull(pending.manifest).hash())
                throw RelayProblem("membership_conflict", 409)
        } else {
            if (pending.manifest == null) {
                val refs = pending.draft.lists.sortedBy { it.listId }.map { list ->
                    val checkpoint = source?.invoke(list.listId) ?: if (pending.draft.createdListId == list.listId)
                        checkpoints.checkpoint(household, list.listId) else remoteCheckpoint(saved, list.listId)
                    val candidate = pending.candidates[list.listId] ?: UUID.randomUUID().toString()
                    var envelope = store.readBlob(candidate)?.let { Wire.json.decodeFromString<Envelope>(it.toString(Charsets.UTF_8)) }
                    if (envelope == null) {
                        val header = Header(saved.server.serverId, household, list.listId, authority.publicKeys.id, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                            pending.draft.epoch, saved.hash, checkpoint.seq, checkpoint.head)
                        val key = Wire.decode(pending.keys.getValue(list.listId), 32)
                        envelope = SyncCrypto.seal(header, checkpoint.payload, key, authority, 7 * 1024 * 1024) {
                            store.reserve("$household:${list.listId}:${pending.draft.epoch}:${authority.publicKeys.id}:${Wire.hash(key)}")
                        }
                        store.saveBlob(candidate, Wire.json.encodeToString(envelope).toByteArray())
                        pending = pending.copy(candidates = pending.candidates + (list.listId to candidate))
                        val nextPending = pending
                        store.update { state -> state.copy(households = state.households + (household to preservePins(saved.copy(pending = nextPending), state))) }
                    }
                    val h = envelope.parsed()
                    call<SnapshotRef>(saved.origin, "PUT", "/v1/households/$household/snapshot-candidates/$candidate", Snapshot(envelope, h.baseSeq, h.baseHead), token)
                }
                val recipients = pending.draft.members.flatMap { it.devices } + pending.draft.recovery
                val packages = pending.draft.lists.flatMap { list -> recipients.map { recipient ->
                    SyncCrypto.wrap(saved.server.serverId, household, list.listId, pending.draft.epoch, saved.hash, recipient,
                        Wire.decode(pending.keys.getValue(list.listId), 32), authority)
                } }
                val body = pending.draft.copy(lists = refs.map { ListCutoff(it.listId, it.seq, it.head, it.candidateId, it.hash) },
                    packageHashes = packages.map { it.hash(saved.server.serverId, household) })
                val draft = SignedManifest(Wire.encode(Wire.json.encodeToString(body).toByteArray()), Wire.encode(ByteArray(64)), packages, refs)
                val signed = draft.copy(signature = SyncCrypto.sign(authority, draft.signingBytes()))
                if (pending.draft.action != "transfer") MembershipProofs.transition(signed, saved.current.signed().body(), saved.hash, saved.genesisHash, saved.server.serverId)
                pending = pending.copy(manifest = signed)
                val nextPending = pending
                store.update { state -> state.copy(households = state.households + (household to preservePins(saved.copy(pending = nextPending), state))) }
            }
            val signed = requireNotNull(pending.manifest)
            if (signed.body().action == "transfer" && signed.transferAcceptance == null) return
            val creating = pending.draft.action == "create_list"
            val response = try { call<Membership>(saved.origin, "POST", "/v1/households/$household/${if (creating) "lists" else "membership"}", signed, token,
                expected = if (creating) 201 else 200, max = 8 * 1024 * 1024) } catch (problem: RelayProblem) {
                if (problem.code != "candidate_expired" || !retryExpired) throw problem
                val ids = pending.candidates.mapValues { (_, old) -> UUID.randomUUID().toString().also { next -> store.saveBlob(next, requireNotNull(store.readBlob(old))) } }
                val restaged = pending.copy(manifest = null, candidates = ids)
                store.update { state -> state.copy(households = state.households + (household to preservePins(saved.copy(pending = restaged), state))) }
                pending.candidates.values.forEach(store::removeBlob)
                resumeMembership(household, authorityOverride, tokenOverride, source, retryExpired = false)
                return
            }
            require(response.manifests.single().signed().hash() == signed.hash())
        }
        val signed = requireNotNull(pending.manifest)
        VerifiedMembership.restoreAuthenticated(saved.genesis, saved.current).advance(ManifestProof(signed.manifestBytes, signed.signature, signed.transferAcceptance))
        saved = saved.copy(previous = saved.current, current = ManifestProof(signed.manifestBytes, signed.signature, signed.transferAcceptance),
            keys = saved.keys + pending.keys.mapKeys { "${it.key}:${pending.draft.epoch}" }, pending = null)
        store.update { state -> state.copy(households = state.households + (household to preservePins(saved, state))) }
        pending.candidates.values.forEach(store::removeBlob)
    }
    override fun toString() = "HouseholdClient([redacted])"
}
data class HouseholdSummary(val id: String, val origin: String, val role: String?, val epoch: Long, val lists: List<String>, val removed: Boolean, val pending: Boolean)
class JoinCandidate(val requestId: String, val code: String?, val verified: VerifiedApplicant?)
class VerifiedApplicant internal constructor(internal val pending: PendingJoin, internal val assertion: JoinAssertion, internal val role: String, val code: String) {
    override fun toString() = "VerifiedApplicant([redacted])"
}

class TransferReview internal constructor(val household: String, internal val previous: String, internal val proposal: SignedManifest) {
    override fun toString() = "TransferReview([redacted])"
}
@kotlinx.serialization.Serializable private data class TransferReply(val household: String, val manifestHash: String, val signature: String)

data class RelayAccess(val origin: String, val household: String, val token: String) { override fun toString() = "RelayAccess([redacted])" }
