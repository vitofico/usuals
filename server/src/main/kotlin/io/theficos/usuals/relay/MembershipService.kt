package io.theficos.usuals.relay

import io.theficos.usuals.sync.*
import java.sql.Connection
import java.util.UUID

class MembershipService(private val relay: Relay) {
    private val db get() = relay.db
    private val auth get() = relay.auth
    fun invite(token: String, household: String, request: InviteRequest): Invite = db.transaction { c ->
        val state = auth.authorize(c, token, household, owner = true).second
        id(request.memberId); expect(request.role in setOf("owner", "editor"))
        val member = state.manifest.members.firstOrNull { it.id == request.memberId }
        expect(member == null || member.role == request.role)
        c.execute("DELETE FROM join_request WHERE expires < ?", auth.now())
        c.execute("DELETE FROM invitation WHERE expires < ? AND id NOT IN (SELECT invite FROM join_request)", auth.now())
        expect(c.query("SELECT count(*) FROM invitation WHERE household=? AND expires>? AND consumed=false", id(household), auth.now()) { it.getLong(1) }.single() < 10, "quota_exceeded", 409)
        val descriptor = request.descriptor?.let { encoded -> pairingDescriptor(household, request, Wire.decode(encoded), state) }
        expect((request.secretHash == null) == (descriptor == null))
        val inviteId = UUID.randomUUID().toString()
        val result = if (descriptor == null) Invite(inviteId, randomToken(), auth.now() + 600000) else Invite(inviteId, null, auth.now() + 86_400_000)
        val hash = result.secret?.let { Wire.hash(raw(it)) } ?: Wire.encode(Wire.decode(requireNotNull(request.secretHash), 32))
        c.execute("INSERT INTO invitation(id,household,hash,member,role,expires,descriptor) VALUES (?,?,?,?,?,?,?)", id(result.inviteId), id(household), raw(hash), id(request.memberId), request.role, result.expiresAt, descriptor)
        result
    }
    /** The relay cannot forge a descriptor the link anchors, but refusing a mismatched one catches client bugs early. */
    private fun pairingDescriptor(household: String, request: InviteRequest, bytes: ByteArray, state: Household): ByteArray {
        expect(bytes.size in 1..2048)
        val value = try { Wire.json.decodeFromJsonElement(InviteDescriptor.serializer(), Wire.strictJson(bytes, 2048)).validate(auth.origin) }
            catch (_: Exception) { reject() }
        expect(value.serverId == relay.identity.serverId && value.householdId == household && value.memberId == request.memberId && value.role == request.role &&
            value.genesisHash == state.genesis && state.manifest.members.any { m -> m.role == "owner" && m.devices.any { d -> d.id == value.ownerDeviceId &&
                Wire.fingerprint(value.serverId, household, d.signingPublicKey, d.recipientPublicKey) == value.ownerFingerprint } })
        return bytes
    }
    fun open(invite: String, request: InviteOpen, ip: String): OpenedInvite {
        auth.limit("invite-open:$ip", 10)
        val secretHash = runCatching { Wire.hash(Wire.decode(request.secret, 32)) }.getOrNull() ?: reject("not_found", 404)
        return db.transaction { c ->
            c.query("SELECT descriptor,hash,expires,consumed FROM invitation WHERE id=?", id(invite)) {
                val descriptor = it.getBytes(1)
                if (descriptor == null || Wire.encode(it.getBytes(2)) != secretHash || it.getLong(3) <= auth.now() || it.getBoolean(4)) null
                else OpenedInvite(Wire.encode(descriptor), it.getLong(3))
            }.singleOrNull() ?: reject("not_found", 404)
        }
    }
    fun join(request: JoinRequest, ip: String): JoinPending {
        auth.limit("join:$ip", 5)
        val bytes = Wire.decode(request.assertion)
        val assertion = JoinAssertion.parse(bytes)
        canonicalOrigin(assertion.origin, local = auth.origin.startsWith("http:"))
        expect(assertion.origin == auth.origin && assertion.serverId == relay.identity.serverId)
        expect(Wire.hash(raw(request.secret)) == assertion.secretHash)
        verify(assertion.device.signingPublicKey, request.signature, bytes)
        return db.transaction { c ->
            val state = c.household(assertion.householdId)
            expect(!state.frozen, "recovery_required", 503)
            expect(state.genesis == assertion.genesisHash)
            val invite = c.query("SELECT hash,member,expires,consumed,descriptor IS NOT NULL FROM invitation WHERE id=? AND household=? FOR UPDATE", id(assertion.inviteId), id(assertion.householdId)) {
                listOf(Wire.encode(it.getBytes(1)), it.getObject(2).toString(), it.getLong(3).toString(), it.getBoolean(4).toString(), it.getBoolean(5).toString())
            }.singleOrNull() ?: reject()
            // A v2 owner approves only a request its link key authenticated, so a request without that proof is useless.
            val mac = request.mac?.let { Wire.decode(it, 32) }
            expect(invite[4] == "false" || mac != null)
            expect(invite[0] == assertion.secretHash && invite[1] == assertion.memberId && invite[3] == "false" && invite[2].toLong() > auth.now() && assertion.expiresAt > auth.now() && assertion.expiresAt <= invite[2].toLong())
            val acceptedRequest = c.query("SELECT assertion,signature,expires,denied,mac FROM join_request WHERE id=? AND household=?", id(assertion.requestId), id(assertion.householdId)) {
                listOf(it.getBytes(1), it.getBytes(2), it.getLong(3), it.getBoolean(4), it.getBytes(5))
            }.singleOrNull()
            if (acceptedRequest != null) {
                expect(acceptedRequest[3] == false && (acceptedRequest[0] as ByteArray).contentEquals(bytes) &&
                    (acceptedRequest[1] as ByteArray).contentEquals(Wire.decode(request.signature, 64)) &&
                    (acceptedRequest[4] as ByteArray?).contentEquals(mac))
                return@transaction JoinPending(assertion.requestId, acceptedRequest[2] as Long)
            }
            val challenge = c.query("SELECT nonce,signing,expires,purpose,device FROM auth_challenge WHERE id=? FOR UPDATE", id(assertion.challengeId)) {
                listOf(Wire.encode(it.getBytes(1)), Wire.encode(it.getBytes(2)), it.getLong(3).toString(), it.getString(4), it.getObject(5).toString())
            }.singleOrNull() ?: reject("unauthenticated", 401)
            expect(challenge[0] == assertion.nonce && challenge[1] == assertion.device.signingPublicKey && challenge[2].toLong() > auth.now() && challenge[3] == "join" && challenge[4] == assertion.device.id, "unauthenticated", 401)
            expect(c.query("SELECT count(*) FROM join_request WHERE household=? AND expires>? AND denied=false", id(assertion.householdId), auth.now()) { it.getLong(1) }.single() < 10, "quota_exceeded", 409)
            c.execute("DELETE FROM auth_challenge WHERE id=?", id(assertion.challengeId))
            c.execute("INSERT INTO join_request(id,household,invite,assertion,signature,expires,mac) VALUES (?,?,?,?,?,?,?)", id(assertion.requestId), id(assertion.householdId), id(assertion.inviteId), bytes, Wire.decode(request.signature, 64), assertion.expiresAt, mac)
            JoinPending(assertion.requestId, assertion.expiresAt)
        }
    }
    fun pending(token: String, household: String): PendingRequests = db.transaction { c ->
        auth.authorize(c, token, household, owner = true)
        PendingRequests(c.query("SELECT id,assertion,signature,mac FROM join_request WHERE household=? AND expires>? AND denied=false ORDER BY id LIMIT 10", id(household), auth.now()) {
            PendingJoin(it.getObject(1).toString(), Wire.encode(it.getBytes(2)), Wire.encode(it.getBytes(3)), it.getBytes(4)?.let(Wire::encode))
        })
    }
    fun deny(token: String, household: String, request: String) = db.transaction { c ->
        auth.authorize(c, token, household, owner = true)
        c.execute("UPDATE invitation SET consumed=true WHERE id IN (SELECT invite FROM join_request WHERE id=? AND household=?)", id(request), id(household))
        expect(c.execute("UPDATE join_request SET denied=true WHERE invite IN (SELECT invite FROM join_request WHERE id=? AND household=?)", id(request), id(household)) >= 1, "not_found", 404)
    }
    fun expired(token: String, household: String, request: String, witness: PendingJoin): JoinPending = db.transaction { c ->
        val state = auth.authorize(c, token, household, owner = true).second
        val bytes = Wire.decode(witness.assertion)
        val assertion = JoinAssertion.parse(bytes)
        expect(witness.requestId == request && assertion.requestId == request && assertion.householdId == household &&
            assertion.origin == auth.origin && assertion.serverId == relay.identity.serverId && assertion.genesisHash == state.genesis)
        verify(assertion.device.signingPublicKey, witness.signature, bytes)
        // The signed witness survives TTL cleanup. The household lock serializes this
        // server-clock check with a concurrent approval committing before expiry.
        expect(assertion.expiresAt <= auth.now(), "membership_conflict", 409)
        JoinPending(request, assertion.expiresAt)
    }
    fun stage(token: String, household: String, candidate: String, snapshot: Snapshot): SnapshotRef = db.transaction { c ->
        val (caller, state) = auth.authorize(c, token, household, owner = true, recovery = true)
        id(candidate)
        val header = snapshot.envelope.parsed()
        expect(header.epoch == state.manifest.epoch || header.epoch == state.manifest.epoch + 1, "epoch_stale", 409)
        if (caller.kind == "recovery") expect(header.epoch == state.manifest.epoch + 1, "forbidden", 403)
        relay.validateSnapshot(snapshot, caller, state, header.listId, header.epoch, state.hash)
        val head = c.query("SELECT last_seq,head,household FROM shared_list WHERE id=? FOR UPDATE", id(header.listId)) {
            Triple(it.getLong(1), Wire.encode(it.getBytes(2)), it.getObject(3).toString())
        }.singleOrNull()
        expect(head == null || head.third == household, "not_found", 404)
        expect(head == null && caller.kind != "recovery" && snapshot.seq == 0L && snapshot.head == Wire.zero ||
            head != null && head.third == household && snapshot.seq == head.first && snapshot.head == head.second, "membership_conflict", 409)
        c.execute("DELETE FROM snapshot_candidate WHERE expires < ?", auth.now())
        val digest = snapshot.envelope.digest()
        val existing = c.query("SELECT digest,household,author FROM snapshot_candidate WHERE id=?", id(candidate)) { Triple(Wire.encode(it.getBytes(1)), it.getObject(2).toString(), it.getObject(3).toString()) }.singleOrNull()
        expect(existing == null || existing.second == household, "not_found", 404)
        expect(existing == null || existing == Triple(digest, household, caller.device), "envelope_conflict", 409)
        if (existing == null) {
            expect(c.query("SELECT count(*) FROM snapshot_candidate WHERE household=? AND list=?", id(household), id(header.listId)) { it.getLong(1) }.single() < 2 &&
                c.query("SELECT count(*) FROM snapshot_candidate WHERE household=?", id(household)) { it.getLong(1) }.single() < 42, "quota_exceeded", 409)
            c.execute("INSERT INTO snapshot_candidate VALUES (?,?,?,?,?,?,?,?,?,?)", id(candidate), id(household), id(header.listId), id(caller.device), header.epoch, snapshot.seq, raw(snapshot.head), raw(digest), encode(snapshot.envelope), auth.now() + 3600000)
        }
        SnapshotRef(candidate, digest, header.listId, snapshot.seq, snapshot.head)
    }
    fun change(token: String, household: String, signed: SignedManifest, creatingList: Boolean = false): ManifestProof {
        val proof = db.transaction { c ->
            val (caller, state) = auth.authorize(c, token, household, owner = true, recovery = !creatingList)
            val m = ManifestRules.transition(signed, state, caller, relay.identity.serverId)
            expect((m.action == "create_list") == creatingList)
            validateJoin(c, m, state)
            // Household lock precedes every list lock, always in UUID order.
            val heads = c.query("SELECT id,last_seq,head,storage_bytes FROM shared_list WHERE household=? ORDER BY id FOR UPDATE", id(household)) {
                it.getObject(1).toString() to Relay.Head(it.getLong(2), Wire.encode(it.getBytes(3)), it.getLong(4))
            }.toMap()
            expect(m.lists.map { it.listId }.toSet() == heads.keys + listOfNotNull(m.createdListId))
            val snapshots = m.lists.map { cutoff ->
                val head = heads[cutoff.listId] ?: Relay.Head(0, Wire.zero, 0)
                expect(head.seq == cutoff.seq && head.head == cutoff.head, "membership_conflict", 409)
                val candidate = c.query("SELECT data,digest,household,list,author,epoch,expires FROM snapshot_candidate WHERE id=?", id(cutoff.snapshotId)) {
                    listOf(it.getBytes(1), it.getBytes(2), it.getObject(3).toString(), it.getObject(4).toString(), it.getObject(5).toString(), it.getLong(6), it.getLong(7))
                }.singleOrNull() ?: reject("candidate_expired", 409)
                expect((candidate[6] as Long) > auth.now(), "candidate_expired", 409)
                expect(candidate[2] == household, "not_found", 404)
                expect(candidate[2] == household && candidate[3] == cutoff.listId && candidate[4] == caller.device && candidate[5] == m.epoch)
                val envelope = decode<Envelope>(candidate[0] as ByteArray)
                expect(Wire.encode(candidate[1] as ByteArray) == cutoff.snapshotHash)
                relay.validateSnapshot(Snapshot(envelope, cutoff.seq, cutoff.head), caller, state, cutoff.listId, m.epoch, state.hash)
                expect(head.bytes + (candidate[0] as ByteArray).size <= 100L * 1024 * 1024, "quota_exceeded", 409)
                cutoff to envelope
            }
            val created = m.createdListId
            if (created != null) c.execute("INSERT INTO shared_list(id,household,head) VALUES (?,?,?)", id(created), id(household), raw(Wire.zero))
            for ((cutoff, envelope) in snapshots) {
                c.execute("INSERT INTO published_snapshot VALUES (?,?,?,?,?)", id(cutoff.listId), m.epoch, cutoff.seq, raw(cutoff.head), encode(envelope))
                c.execute("UPDATE shared_list SET storage_bytes=storage_bytes+? WHERE id=?", encode(envelope).size, id(cutoff.listId))
            }
            relay.saveRoster(c, m)
            c.execute("UPDATE household SET epoch=?,manifest_hash=?,body=? WHERE id=?", m.epoch, raw(signed.hash()), encode(m), id(household))
            c.execute("INSERT INTO manifest VALUES (?,?,?)", id(household), m.epoch, encode(signed.proof()))
            signed.packages.forEach { p -> c.execute("INSERT INTO key_package VALUES (?,?,?,?,?)", id(household), m.epoch, id(p.listId), raw(p.recipientFingerprint), encode(p)) }
            signed.snapshotRefs.forEach { c.execute("DELETE FROM snapshot_candidate WHERE id=?", id(it.candidateId)) }
            signed.proof()
        }
        relay.changed(household)
        return proof
    }
    private fun validateJoin(c: Connection, m: Manifest, state: Household) {
        for ((request, hash) in m.approvedJoins) {
            val row = c.query("SELECT j.assertion,j.expires,j.denied,i.expires,i.consumed,i.id,i.role FROM join_request j JOIN invitation i ON i.id=j.invite WHERE j.id=? AND j.household=? FOR UPDATE OF j,i", id(request), id(m.householdId)) {
                listOf(it.getBytes(1), it.getLong(2), it.getBoolean(3), it.getLong(4), it.getBoolean(5), it.getObject(6).toString(), it.getString(7))
            }.singleOrNull() ?: reject()
            expect(row[4] == false, "membership_conflict", 409)
            expect((row[1] as Long) > auth.now() && (row[3] as Long) > auth.now() && row[2] == false)
            val a = JoinAssertion.parse(row[0] as ByteArray)
            expect(Wire.hash(row[0] as ByteArray) == hash && a.genesisHash == state.genesis)
            expect(m.members.any { it.id == a.memberId && it.role == row[6] && a.device in it.devices })
            val added = m.members.flatMap { it.devices }.filter { d -> state.manifest.members.none { d in it.devices } }
            expect(added == listOf(a.device))
            c.execute("UPDATE invitation SET consumed=true WHERE id=?", id(row[5] as String))
            c.execute("UPDATE join_request SET denied=true WHERE invite=?", id(row[5] as String))
        }
    }
}
