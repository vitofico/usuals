package io.theficos.usuals.relay

import io.theficos.usuals.sync.*
import java.sql.Connection
import kotlinx.serialization.encodeToString

class Relay(val db: Database, val identity: Identity, val auth: Auth) {
    // Invoked only after commit. Socket wakeups are hints, never a source of truth.
    var changed: (String) -> Unit = {}
    fun create(token: String, request: CreateHousehold): ManifestProof = db.transaction { c ->
        val m = ManifestRules.genesis(request.manifest, identity.serverId)
        auth.consumeBootstrap(c, token)
        val signing = auth.consume(c, request.bootstrapProof, setOf("bootstrap")).second
        expect(request.bootstrapProof.deviceId == m.signer && m.members.single().devices.single().signingPublicKey == signing)
        val digest = request.manifest.hash()
        c.execute("INSERT INTO household(id,genesis,epoch,manifest_hash,body) VALUES (?,?,1,?,?)", id(m.householdId), raw(digest), raw(digest), encode(m))
        saveRoster(c, m)
        val proof = request.manifest.proof()
        c.execute("INSERT INTO manifest VALUES (?,1,?)", id(m.householdId), encode(proof))
        proof
    }
    fun membership(token: String, household: String, after: Long): Membership = db.transaction { c ->
        expect(after >= 0)
        auth.authorize(c, token, household, recovery = true)
        val proofs = c.query("SELECT epoch,proof FROM manifest WHERE household=? AND epoch>? ORDER BY epoch LIMIT 1", id(household), after) {
            it.getLong(1) to decode<ManifestProof>(it.getBytes(2))
        }
        Membership(proofs.map { it.second }, proofs.lastOrNull()?.first ?: maxOf(after, 1))
    }
    fun lists(token: String, household: String): Lists = db.transaction { c ->
        auth.authorize(c, token, household, recovery = true)
        Lists(c.query("SELECT id FROM shared_list WHERE household=? ORDER BY id", id(household)) { it.getObject(1).toString() })
    }
    fun packages(token: String, household: String, list: String, epoch: Long): KeyPackages = db.transaction { c ->
        val (caller, state) = auth.authorize(c, token, household, recovery = true)
        expect(epoch == state.manifest.epoch, "epoch_stale", 409)
        expect(state.manifest.lists.any { it.listId == list }, "not_found", 404)
        val device = (state.manifest.members.flatMap { it.devices } + state.manifest.recovery).first { it.id == caller.device }
        val fingerprint = Wire.fingerprint(identity.serverId, household, device.signingPublicKey, device.recipientPublicKey)
        KeyPackages(c.query("SELECT data FROM key_package WHERE household=? AND epoch=? AND recipient=? AND list=? LIMIT 1", id(household), epoch, raw(fingerprint), id(list)) { decode<KeyPackage>(it.getBytes(1)) })
    }
    internal fun listAccess(c: Connection, token: String, list: String, owner: Boolean = false, recovery: Boolean = false): Triple<Caller, Household, Head> {
        // Authenticate before resolving routing; cross-household ids stay indistinguishable.
        auth.caller(c, token)
        val household = c.query("SELECT household FROM shared_list WHERE id=?", id(list)) { it.getObject(1).toString() }.singleOrNull() ?: reject("not_found", 404)
        val (caller, state) = auth.authorize(c, token, household, owner, recovery)
        val head = c.query("SELECT last_seq,head,storage_bytes FROM shared_list WHERE id=? FOR UPDATE", id(list)) { Head(it.getLong(1), Wire.encode(it.getBytes(2)), it.getLong(3)) }.single()
        return Triple(caller, state, head)
    }
    fun append(token: String, list: String, request: Append): AppendResults {
        expect(request.envelopes.size in 1..100)
        // Charge attempts durably before validation; failed writes cannot undo rate usage.
        val actor = db.transaction { c -> listAccess(c, token, list).let { it.first.device to it.second.manifest.householdId } }
        auth.limit("append-device:${actor.first}", 60); auth.limit("append-household:${actor.second}", 300)
        val result = db.transaction { c ->
            val (caller, state, previous) = listAccess(c, token, list)
            var seq = previous.seq; var head = previous.head; var bytes = previous.bytes
            val output = request.envelopes.mapIndexed { index, envelope -> input(index) {
                envelope.validate()
                val h = envelope.parsed()
                expect(h.serverId == identity.serverId && h.householdId == state.manifest.householdId && h.listId == list, "not_found", 404)
                expect(h.authorDeviceId == caller.device, "forbidden", 403)
                val key = state.manifest.members.flatMap { it.devices }.first { it.id == caller.device }
                verify(key.signingPublicKey, envelope.signature, envelope.signingBytes())
                val byEnvelope = c.query("SELECT digest,data,receipt FROM operation WHERE list=? AND envelope=?", id(list), id(h.envelopeId)) { Triple(Wire.encode(it.getBytes(1)), decode<Envelope>(it.getBytes(2)), decode<Receipt>(it.getBytes(3))) }.singleOrNull()
                if (byEnvelope != null) {
                    expect(byEnvelope.first == envelope.digest(), "envelope_conflict", 409)
                    AppendResult(index, h.intentId, h.envelopeId, "duplicate", byEnvelope.third)
                } else {
                    val existingIntent = c.query("SELECT seq,envelope,receipt FROM operation WHERE list=? AND intent=?", id(list), id(h.intentId)) {
                        Triple(it.getLong(1), it.getObject(2).toString(), decode<Receipt>(it.getBytes(3)))
                    }.singleOrNull()
                    if (existingIntent != null) {
                        // A receipt allocated earlier in this batch disappears on rollback.
                        val durable = existingIntent.first <= previous.seq
                        throw RelayError("intent_exists", 409, index, existingIntent.second.takeIf { durable }, existingIntent.third.takeIf { durable })
                    }
                    expect(h.epoch == state.manifest.epoch && h.manifestHash == state.hash, "epoch_stale", 409)
                    expect(h.baseSeq <= seq && (if (h.baseSeq == 0L) h.baseHead == Wire.zero else
                        c.query("SELECT head FROM operation WHERE list=? AND seq=?", id(list), h.baseSeq) { Wire.encode(it.getBytes(1)) }.singleOrNull() == h.baseHead), "base_unknown", 409)
                    expect(seq < Long.MAX_VALUE, "quota_exceeded", 409)
                    val nextHead = Wire.head(head, seq + 1, envelope)
                    val receipt = Receipt(++seq, head, nextHead, identity.sign(Wire.receipt(identity.serverId, h.householdId, list, seq, nextHead)))
                    val encoded = encode(envelope); bytes += encoded.size
                    expect(bytes <= 100L * 1024 * 1024, "quota_exceeded", 409)
                    c.execute("INSERT INTO operation VALUES (?,?,?,?,?,?,?,?)", id(list), seq, id(h.intentId), id(h.envelopeId), raw(envelope.digest()), encoded, encode(receipt), raw(nextHead))
                    head = nextHead
                    AppendResult(index, h.intentId, h.envelopeId, "accepted", receipt)
                }
            } }
            c.execute("UPDATE shared_list SET last_seq=?,head=?,storage_bytes=? WHERE id=?", seq, raw(head), bytes, id(list))
            AppendResults(output, seq, head)
        }
        changed(actor.second)
        return result
    }
    fun operations(token: String, list: String, after: Long, limit: Int = 100): Operations = db.transaction { c ->
        expect(after >= 0 && limit in 1..100)
        val (caller, state, head) = listAccess(c, token, list, recovery = true)
        expect(after <= head.seq, "base_unknown", 409)
        if (caller.kind == "recovery") {
            val cutoff = c.query("SELECT seq FROM published_snapshot WHERE list=? AND epoch=?", id(list), state.manifest.epoch) { it.getLong(1) }.singleOrNull() ?: reject("not_found", 404)
            expect(after >= cutoff, "base_unknown", 409)
        }
        val rows = c.query("SELECT data,receipt FROM operation WHERE list=? AND seq>? ORDER BY seq LIMIT ?", id(list), after, limit) { StoredOperation(decode<Envelope>(it.getBytes(1)), decode<Receipt>(it.getBytes(2))) }
        val next = rows.lastOrNull()?.receipt?.seq ?: after
        Operations(rows, next, next < head.seq)
    }
    fun intent(token: String, list: String, intent: String): StoredOperation = db.transaction { c ->
        val caller = listAccess(c, token, list).first
        val row = c.query("SELECT data,receipt FROM operation WHERE list=? AND intent=?", id(list), id(intent)) { StoredOperation(decode<Envelope>(it.getBytes(1)), decode<Receipt>(it.getBytes(2))) }.singleOrNull() ?: reject("not_found", 404)
        expect(row.envelope.parsed().authorDeviceId == caller.device, "not_found", 404)
        row
    }
    fun snapshot(token: String, list: String): Snapshot = db.transaction { c ->
        val state = listAccess(c, token, list, recovery = true).second
        c.query("SELECT data,seq,head FROM published_snapshot WHERE list=? AND epoch=?", id(list), state.manifest.epoch) { Snapshot(decode<Envelope>(it.getBytes(1)), it.getLong(2), Wire.encode(it.getBytes(3))) }.singleOrNull() ?: reject("not_found", 404)
    }
    fun putSnapshot(token: String, list: String, snapshot: Snapshot) = db.transaction { c ->
        val (caller, state, head) = listAccess(c, token, list, owner = true)
        val h = snapshot.envelope.parsed(); snapshot.envelope.validate(10 * 1024 * 1024)
        validateSnapshot(snapshot, caller, state, list, state.manifest.epoch, state.hash)
        expect(head.seq == snapshot.seq && head.head == snapshot.head, "membership_conflict", 409)
        val oldSize = c.query("SELECT octet_length(data) FROM published_snapshot WHERE list=? AND epoch=?", id(list), h.epoch) { it.getLong(1) }.singleOrNull() ?: 0L
        val bytes = encode(snapshot.envelope)
        expect(head.bytes - oldSize + bytes.size <= 100L * 1024 * 1024, "quota_exceeded", 409)
        c.execute("UPDATE shared_list SET storage_bytes=storage_bytes-?+? WHERE id=?", oldSize, bytes.size, id(list))
        c.execute("INSERT INTO published_snapshot VALUES (?,?,?,?,?) ON CONFLICT(list,epoch) DO UPDATE SET seq=EXCLUDED.seq,head=EXCLUDED.head,data=EXCLUDED.data", id(list), h.epoch, head.seq, raw(head.head), encode(snapshot.envelope))
    }
    internal fun validateSnapshot(snapshot: Snapshot, caller: Caller, state: Household, list: String, epoch: Long, manifestHash: String) {
        snapshot.envelope.validate(10 * 1024 * 1024)
        val h = snapshot.envelope.parsed()
        expect(h.serverId == identity.serverId && h.householdId == state.manifest.householdId && h.listId == list &&
            h.authorDeviceId == caller.device && h.epoch == epoch && h.manifestHash == manifestHash &&
            h.baseSeq == snapshot.seq && h.baseHead == snapshot.head)
        val device = if (caller.kind == "recovery") state.manifest.recovery else state.manifest.members.flatMap { it.devices }.first { it.id == caller.device }
        verify(device.signingPublicKey, snapshot.envelope.signature, snapshot.envelope.signingBytes())
    }
    internal fun saveRoster(c: Connection, m: Manifest) {
        c.execute("UPDATE membership SET active=false WHERE household=?", id(m.householdId))
        val entries = m.members.flatMap { member -> member.devices.map { Triple(it, member.id, member.role) } } + Triple(m.recovery, m.recovery.id, "recovery")
        for ((device, member, role) in entries) {
            val exists = c.query("SELECT signing,recipient FROM principal WHERE id=?", id(device.id)) { Wire.encode(it.getBytes(1)) to Wire.encode(it.getBytes(2)) }.singleOrNull()
            expect(exists == null || exists == (device.signingPublicKey to device.recipientPublicKey))
            c.execute("INSERT INTO principal VALUES (?,?,?) ON CONFLICT(id) DO NOTHING", id(device.id), raw(device.signingPublicKey), raw(device.recipientPublicKey))
            c.execute("INSERT INTO membership VALUES (?,?,?,?,true) ON CONFLICT(household,device) DO UPDATE SET member=EXCLUDED.member,role=EXCLUDED.role,active=true", id(m.householdId), id(device.id), id(member), role)
        }
        c.execute("UPDATE auth_session SET revoked=true WHERE device IN (SELECT device FROM membership WHERE household=? AND active=false)", id(m.householdId))
    }
    internal data class Head(val seq: Long, val head: String, val bytes: Long)
}
internal fun raw(value: String) = Wire.decode(value, 32)
internal inline fun <reified T> encode(value: T) = Wire.json.encodeToString(value).toByteArray()
internal inline fun <reified T> decode(value: ByteArray): T = Wire.json.decodeFromString(value.toString(Charsets.UTF_8))
internal fun SignedManifest.proof() = ManifestProof(manifestBytes, signature, transferAcceptance)
