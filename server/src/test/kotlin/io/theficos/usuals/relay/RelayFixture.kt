package io.theficos.usuals.relay

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Random
import com.google.crypto.tink.subtle.AesGcmJce
import io.theficos.usuals.sync.*
import kotlinx.serialization.encodeToString
import java.util.UUID

internal fun uuid() = UUID.randomUUID().toString()
internal class TestDevice {
    val pair = Ed25519Sign.KeyPair.newKeyPair()
    val keys = DeviceKeys(uuid(), Wire.encode(pair.publicKey), Wire.encode(Random.randBytes(32)))
    fun sign(bytes: ByteArray) = Wire.encode(Ed25519Sign(pair.privateKey).sign(bytes))
}
internal class RelayFixture(val db: Database, val identity: Identity, val clock: () -> Long) {
    val origin = "https://usuals.invalid"
    val auth = Auth(db, identity, origin, clock = clock)
    val relay = Relay(db, identity, auth)
    val membership = MembershipService(relay)
    val owner = TestDevice()
    val recovery = TestDevice()
    val household = uuid()
    val member = uuid()
    lateinit var current: SignedManifest
    lateinit var token: String
    private var ip = 0
    val encryptionKey = Random.randBytes(32)
    fun signed(body: Manifest, author: TestDevice = owner, packages: List<KeyPackage> = emptyList(), refs: List<SnapshotRef> = emptyList(), acceptance: TransferAcceptance? = null): SignedManifest {
        val draft = SignedManifest(Wire.encode(Wire.json.encodeToString(body).toByteArray()), Wire.encode(ByteArray(64)), packages, refs, acceptance)
        return draft.copy(signature = author.sign(draft.signingBytes()))
    }
    fun bootstrapRequest(): Pair<String, CreateHousehold> {
        val challenge = auth.challenge(ChallengeRequest(owner.keys.id, "bootstrap", owner.keys.signingPublicKey), "fixture-${ip++}")
        val body = Manifest(serverId = identity.serverId, householdId = household, genesisHash = Wire.zero, epoch = 1, previousHash = Wire.zero,
            action = "genesis", signer = owner.keys.id, members = listOf(RosterMember(member, "owner", listOf(owner.keys))), recovery = recovery.keys)
        current = signed(body)
        return auth.bootstrapToken() to CreateHousehold(current, proof(owner, challenge))
    }
    fun bootstrap(): RelayFixture {
        val (bootstrapToken, request) = bootstrapRequest()
        relay.create(bootstrapToken, request)
        token = session(owner)
        return this
    }
    fun proof(device: TestDevice, challenge: Challenge) = Proof(challenge.challengeId, device.keys.id,
        device.sign(Wire.auth(origin, identity.serverId, device.keys.id, challenge.challengeId, challenge.nonce, challenge.expiresAt, challenge.purpose, device.keys.signingPublicKey)))
    fun session(device: TestDevice, purpose: String = "session"): String {
        val challenge = auth.challenge(ChallengeRequest(device.keys.id, purpose), "fixture-${ip++}")
        return auth.session(proof(device, challenge)).token
    }
    fun envelope(list: String, author: TestDevice = owner, intent: String = uuid(), envelope: String = uuid(),
        base: Long = 0, head: String = Wire.zero, epoch: Long = current.body().epoch, manifest: String = current.hash(), clear: String = "synthetic-secret-milk"): Envelope {
        val h = Header(identity.serverId, household, list, author.keys.id, intent, envelope, epoch, manifest, base, head)
        val cipher = AesGcmJce(encryptionKey).encrypt(clear.toByteArray(), Wire.domain("AAD", h.bytes()))
        val draft = Envelope(Wire.encode(h.bytes()), Wire.encode(cipher), Wire.encode(ByteArray(64)))
        return draft.copy(signature = author.sign(draft.signingBytes()))
    }
    fun next(action: String, listIds: List<String> = current.body().lists.map { it.listId },
        members: List<RosterMember> = current.body().members, newRecovery: DeviceKeys = current.body().recovery,
        author: TestDevice = owner, session: String = token, joins: Map<String,String> = emptyMap()): SignedManifest {
        val old = current.body()
        val refs = listIds.sorted().map { list ->
            val head = db.transaction { c -> c.query("SELECT last_seq,head FROM shared_list WHERE id=?", id(list)) { it.getLong(1) to Wire.encode(it.getBytes(2)) }.singleOrNull() } ?: (0L to Wire.zero)
            val envelope = envelope(list, author, base = head.first, head = head.second, epoch = old.epoch + 1, manifest = current.hash(), clear = "{\"snapshot\":\"synthetic-secret-milk\"}")
            membership.stage(session, household, uuid(), Snapshot(envelope, head.first, head.second))
        }
        val recipients = members.flatMap { it.devices } + newRecovery
        val packages = listIds.flatMap { list -> recipients.map { device ->
            val p = KeyPackage(list, Wire.fingerprint(identity.serverId, household, device.signingPublicKey, device.recipientPublicKey), old.epoch + 1, current.hash(), Wire.encode(Random.randBytes(80)), Wire.encode(ByteArray(64)))
            p.copy(signature = author.sign(p.signingBytes(identity.serverId, household)))
        } }
        return signed(Manifest(serverId = identity.serverId, householdId = household,
            genesisHash = if (old.epoch == 1L) current.hash() else old.genesisHash, epoch = old.epoch + 1, previousHash = current.hash(), action = action,
            signer = author.keys.id, members = members, recovery = newRecovery,
            lists = refs.map { ListCutoff(it.listId, it.seq, it.head, it.candidateId, it.hash) },
            packageHashes = packages.map { it.hash(identity.serverId, household) }, approvedJoins = joins,
            createdListId = if (action == "create_list") listIds.single { id -> old.lists.none { it.listId == id } } else null), author, packages, refs)
    }
    fun createList(): String {
        val list = uuid(); val proposed = next("create_list", current.body().lists.map { it.listId } + list)
        membership.change(token, household, proposed, creatingList = true); current = proposed
        return list
    }
    fun join(device: TestDevice, memberId: String = uuid(), role: String = "editor"): Pair<String, String> {
        val invite = membership.invite(token, household, InviteRequest(memberId, role))
        return requestJoin(invite, device, memberId)
    }
    fun requestJoin(invite: Invite, device: TestDevice, memberId: String, secret: String = requireNotNull(invite.secret), mac: String? = null): Pair<String, String> {
        val challenge = auth.challenge(ChallengeRequest(device.keys.id, "join", device.keys.signingPublicKey), "fixture-${ip++}")
        val body = JoinAssertion(origin, identity.serverId, household, if (current.body().epoch == 1L) current.hash() else current.body().genesisHash,
            invite.inviteId, uuid(), memberId, device.keys, Wire.hash(raw(secret)), challenge.challengeId, challenge.nonce, invite.expiresAt)
        membership.join(JoinRequest(secret, Wire.encode(body.bytes()), device.sign(body.bytes()), mac), "fixture-${ip++}")
        return body.requestId to Wire.hash(body.bytes())
    }
    fun addMember(device: TestDevice): String {
        val id = uuid(); val join = join(device, id)
        val next = next("approve_join", members = current.body().members + RosterMember(id, "editor", listOf(device.keys)), joins = mapOf(join))
        membership.change(token, household, next); current = next
        return session(device)
    }
}

internal class MemorySyncStore : SyncStateStore {
    private var state = ClientState()
    private val counters = mutableMapOf<String, Int>()
    private val files = mutableMapOf<String, ByteArray>()
    @Synchronized override fun read() = state
    @Synchronized override fun update(change: (ClientState) -> ClientState): ClientState = change(state).also { state = it }
    @Synchronized override fun reserve(scope: String) { val value = counters[scope] ?: 0; check(value < 4096); counters[scope] = value + 1 }
    @Synchronized override fun saveBlob(id: String, bytes: ByteArray) { files[id] = bytes.copyOf() }
    @Synchronized override fun readBlob(id: String) = files[id]?.copyOf()
    @Synchronized override fun removeBlob(id: String) { files.remove(id) }
}
