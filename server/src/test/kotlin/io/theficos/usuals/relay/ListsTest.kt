package io.theficos.usuals.relay

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.theficos.usuals.model.NORMALIZER_VERSION
import io.theficos.usuals.parse.Normalizer
import io.theficos.usuals.sync.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.nio.file.attribute.PosixFilePermissions

class ListsTest {
    private val database = TestDatabase()
    private val db = database.db
    private val identity = Identity.generate()
    private var now = 1_800_000_000_000L
    private lateinit var f: RelayFixture
    private lateinit var alice: Account
    private lateinit var bob: Account
    private lateinit var carol: Account
    @Before fun before() {
        db.migrate(); db.fence(identity.serverId, "original")
        f = RelayFixture(db, identity) { now }
        alice = f.account("Alice", "alice@example.org"); bob = f.account("Bob Builder", "bob@example.org"); carol = f.account("Carol")
    }
    @After fun after() = database.close()

    @Test fun membersReusingTheSamePurchaseKeepOneOriginalQuantity() = http {
        val list = client.create(alice)
        client.share(alice, list.id, bob)
        fun reuse(kind: String = "reuse") = Intent(uuid(), list.id, kind, buildJsonObject {
            put("proposedId", uuid()); put("key", "milk"); put("name", "Milk")
            put("quantity", "2.0"); put("unit", "LITRE"); put("normalizerVersion", NORMALIZER_VERSION)
        })
        val commands = listOf(alice to reuse(), bob to reuse())
        val replies = coroutineScope {
            commands.map { (who, command) -> async {
                val response = client.apply(who, list.id, command)
                assertThat(response.status).isEqualTo(HttpStatusCode.OK)
                Wire.json.decodeFromString<IntentBatchResult>(response.bodyAsText())
            } }.awaitAll()
        }
        assertThat(replies.map { it.results.single().outcome }).containsExactly("applied", "no_op")
        val current = client.changes(bob).lists.single { it.id == list.id }.projection
        assertThat(current.rows).hasSize(1)
        assertThat(current.rows.values.single().facts.getValue("quantity").jsonPrimitive.content).isEqualTo("2.0")
        assertThat(current.redirects).isEmpty()
        assertThat(client.apply(carol, list.id, reuse()).status).isEqualTo(HttpStatusCode.NotFound)
        val captured = client.apply(alice, list.id, reuse("capture"))
        val merged = Wire.json.decodeFromString<IntentBatchResult>(captured.bodyAsText())
        assertThat(merged.results.single().outcome).isEqualTo("merged")
        assertThat(merged.list.projection.rows.values.single().facts.getValue("quantity").jsonPrimitive.content).isEqualTo("4.0")
    }

    @Test fun concurrentPacksNotifyOnlyForInsertionAndPreserveSharedRowEdits() = http {
        val list = client.create(alice).id
        client.share(alice, list, bob)
        fun pack(category: String) = capture(list).let { command -> command.copy(kind = "pack", payload =
            JsonObject(command.payload + mapOf("quantity" to JsonPrimitive("2.0"), "unit" to JsonPrimitive("LITRE"),
                "packCategory" to JsonPrimitive(category)))) }
        val commands = listOf(alice to pack("DAIRY"), bob to pack("PRODUCE"))
        val replies = coroutineScope {
            commands.map { (who, command) -> async {
                val response = client.apply(who, list, command)
                assertThat(response.status).isEqualTo(HttpStatusCode.OK)
                Wire.json.decodeFromString<IntentBatchResult>(response.bodyAsText())
            } }.awaitAll()
        }
        assertThat(replies.map { it.results.single().outcome }).containsExactly("applied", "no_op")
        val winner = commands[replies.indexOfFirst { it.results.single().outcome == "applied" }]
        val observer = if (winner.first == alice) bob else alice
        val inserted = client.changes(observer, activity = true).lists.single()
        assertThat(inserted.projection.rows).hasSize(1)
        assertThat(inserted.activity).containsExactly(ListActivity(Wire.hash(winner.second.id.toByteArray()), "items_added", 1, now))
        val row = inserted.projection.rows.values.single()
        fun edit(field: String, value: String) = Intent(uuid(), list, "edit", buildJsonObject {
            put("target", row.id); put("changes", buildJsonObject { put(field, value) })
            put("expected", buildJsonObject { put(field, row.revisions[field]?.let(::JsonPrimitive) ?: JsonNull) })
        })
        coroutineScope {
            listOf(alice to edit("note", "For visitors"), bob to edit("store", "Market")).map { (who, command) -> async {
                val response = client.apply(who, list, command)
                assertThat(response.status).isEqualTo(HttpStatusCode.OK)
                assertThat(Wire.json.decodeFromString<IntentBatchResult>(response.bodyAsText()).results.single().outcome).isEqualTo("applied")
            } }.awaitAll()
        }
        val check = Intent(uuid(), list, "check", buildJsonObject {
            put("target", row.id); put("checked", true); put("checkedAt", now)
            put("expected", buildJsonObject { put("check", row.revisions["check"]?.let(::JsonPrimitive) ?: JsonNull) })
        })
        assertThat(client.apply(alice, list, check).status).isEqualTo(HttpStatusCode.OK)
        val beforeDuplicate = client.changes(observer, activity = true).lists.single()
        assertThat(beforeDuplicate.projection.rows.getValue(row.id).facts["note"]).isEqualTo(JsonPrimitive("For visitors"))
        assertThat(beforeDuplicate.projection.rows.getValue(row.id).facts["store"]).isEqualTo(JsonPrimitive("Market"))
        val duplicate = pack("BAKERY")
        val skipped = Wire.json.decodeFromString<IntentBatchResult>(client.apply(bob, list, duplicate).bodyAsText())
        assertThat(skipped.results.single().outcome).isEqualTo("no_op")
        client.apply(bob, list, duplicate)
        val afterDuplicate = client.changes(observer, activity = true).lists.single()
        assertThat(afterDuplicate.projection.rows).isEqualTo(beforeDuplicate.projection.rows)
        assertThat(afterDuplicate.projection.preferences).isEqualTo(beforeDuplicate.projection.preferences)
        assertThat(afterDuplicate.activity).isEqualTo(inserted.activity)
        assertThat(client.apply(carol, list, pack("BAKERY")).status).isEqualTo(HttpStatusCode.NotFound)
    }

    @Test fun sharedActivityIsServerAttributedBoundedAndRetrySafe() = http {
        val list = client.create(alice).id
        client.share(alice, list, bob)
        assertThat(client.changes(bob, activity = true).lists.single().activity).isEmpty()
        val milk = capture(list)
        val self = Wire.json.decodeFromString<IntentBatchResult>(client.apply(alice, list, milk).bodyAsText()).list
        assertThat(self.activity).isEmpty()
        val event = client.changes(bob, activity = true).lists.single().activity.single()
        assertThat(event).isEqualTo(ListActivity(Wire.hash(milk.id.toByteArray()), "items_added", 1, now))
        client.apply(alice, list, milk)
        client.apply(alice, list, capture(list)) // A merged quantity is not a newly added row.
        client.apply(alice, list, milk.copy(id = uuid(), kind = "unknown"))
        assertThat(client.changes(bob, activity = true).lists.single().activity).containsExactly(event)
        val reused = capture(list, "Bread").copy(kind = "reuse")
        client.apply(alice, list, reused)
        client.apply(alice, list, capture(list, "Bread").copy(kind = "reuse"))
        assertThat(client.changes(bob, activity = true).lists.single().activity).hasSize(2)
        val sent = (1..70).map { capture(list, "Item $it") }
        assertThat(client.apply(alice, list, *sent.toTypedArray()).status).isEqualTo(HttpStatusCode.OK)
        val recent = client.changes(bob, activity = true).lists.single().activity
        assertThat(recent.map { it.id }).containsExactlyElementsIn(sent.takeLast(64).map { Wire.hash(it.id.toByteArray()) }).inOrder()
        val stored = db.transaction { c -> c.query("SELECT activity FROM list WHERE id=?", id(list)) { it.getBytes(1) }.single() }
        assertThat(stored.decodeToString()).doesNotContain(alice.id)
        assertThat(stored.decodeToString()).doesNotContain("items_added")
        assertThat(client.apply(carol, list, capture(list, "Eggs")).status).isEqualTo(HttpStatusCode.NotFound)
        client.send(HttpMethod.Delete, "/api/lists/$list", alice)
        assertThat(db.transaction { c -> c.query("SELECT activity FROM list WHERE id=?", id(list)) { it.getBytes(1) }.single() }).isNull()
    }

    @Test fun finishedShopActivityRequiresPurchasesAndUndoRetractsIt() = http {
        val list = client.create(alice).id
        client.share(alice, list, bob)
        client.apply(alice, list, capture(list))
        var projection = client.changes(alice).lists.single().projection
        val row = projection.rows.values.single()
        val checked = Intent(uuid(), list, "check", buildJsonObject {
            put("target", row.id); put("checked", true); put("checkedAt", now)
            put("expected", buildJsonObject { put("check", row.revisions["check"]?.let(::JsonPrimitive) ?: JsonNull) })
        })
        client.apply(alice, list, checked)
        projection = client.changes(alice).lists.single().projection
        val ready = projection.rows.getValue(row.id)
        val trip = uuid()
        val finish = Intent(uuid(), list, "finish", buildJsonObject {
            put("tripId", trip); put("closedAt", now)
            put("rows", buildJsonArray { add(buildJsonObject {
                put("id", ready.id); put("facts", ready.facts)
                put("expectedLifecycle", ready.lifecycle?.let(::JsonPrimitive) ?: JsonNull)
                put("expectedFields", buildJsonObject { ready.revisions.forEach { (field, revision) -> put(field, revision?.let(::JsonPrimitive) ?: JsonNull) } })
            }) })
        })
        val undo = Intent(uuid(), list, "undo", buildJsonObject { put("finishId", finish.id); put("tripId", trip) })
        val first = client.apply(alice, list, finish, undo)
        assertThat(Wire.json.decodeFromString<IntentBatchResult>(first.bodyAsText()).results.map { it.outcome }).containsExactly("applied", "applied").inOrder()
        assertThat(client.changes(bob, activity = true).lists.single().activity.map { it.kind }).containsExactly("items_added")
        // A new finish after Undo has a new trip and intent id, and the restored row's lifecycle.
        val restored = client.changes(alice).lists.single().projection.rows.getValue(row.id)
        val next = finish.copy(id = uuid(), payload = buildJsonObject {
            put("tripId", uuid()); put("closedAt", now)
            put("rows", buildJsonArray { add(buildJsonObject {
                put("id", restored.id); put("facts", restored.facts)
                put("expectedLifecycle", restored.lifecycle?.let(::JsonPrimitive) ?: JsonNull)
                put("expectedFields", buildJsonObject { restored.revisions.forEach { (field, revision) -> put(field, revision?.let(::JsonPrimitive) ?: JsonNull) } })
            }) })
        })
        client.apply(alice, list, next)
        client.apply(alice, list, next)
        val activity = client.changes(bob, activity = true).lists.single().activity
        assertThat(activity.map { it.kind }).containsExactly("items_added", "shop_finished").inOrder()
        assertThat(activity.last().count).isEqualTo(1)
        val undoNext = undo.copy(id = uuid(), payload = buildJsonObject {
            put("finishId", next.id); put("tripId", next.payload.getValue("tripId"))
        })
        client.apply(alice, list, undoNext)
        assertThat(client.changes(bob, activity = true).lists.single().activity.map { it.kind }).containsExactly("items_added")
        val emptyFinish = Intent(uuid(), list, "finish", buildJsonObject { put("tripId", uuid()); put("closedAt", now); put("rows", JsonArray(emptyList())) })
        client.apply(alice, list, emptyFinish)
        assertThat(client.changes(bob, activity = true).lists.single().activity.map { it.kind }).containsExactly("items_added")
    }

    @Test fun activityKeyRotationUsesASeparateAuthenticatedContext() {
        lateinit var list: String
        lateinit var events: List<ListActivity>
        http {
            list = client.create(alice).id; client.share(alice, list, bob)
            client.apply(alice, list, capture(list))
            events = client.changes(bob, activity = true).lists.single().activity
        }
        val next = ListCipher(ByteArray(32) { 42 })
        f.relay.cipher.rewrap(db, next)
        next.check(db)
        http(Relay(db, identity, f.auth, next)) { assertThat(client.changes(bob, activity = true).lists.single().activity).isEqualTo(events) }
        db.transaction { it.execute("UPDATE list SET activity=projection WHERE id=?", id(list)) }
        http(Relay(db, identity, f.auth, next)) {
            assertThat(client.send(HttpMethod.Get, "/api/lists/changes?activity=1", bob).status).isEqualTo(HttpStatusCode.ServiceUnavailable)
        }
    }

    @Test fun activityRequiresFeedOptInAndPreservesLegacyMutationReplies() = http {
        val list = client.create(alice).id
        client.share(alice, list, bob)
        client.apply(bob, list, capture(list))
        val changed = client.apply(alice, list, capture(list, "Bread"))
        assertThat(Wire.json.parseToJsonElement(changed.bodyAsText()).jsonObject.getValue("list").jsonObject.keys).doesNotContain("activity")
        val legacy = client.send(HttpMethod.Get, "/api/lists/changes", alice)
        assertThat(legacy.status).isEqualTo(HttpStatusCode.OK)
        val entry = Wire.json.parseToJsonElement(legacy.bodyAsText()).jsonObject.getValue("lists").jsonArray.single().jsonObject
        assertThat(entry.keys).doesNotContain("activity")
        assertThat(client.changes(alice).lists.single().activity).isEmpty()
        assertThat(client.changes(alice, activity = true).lists.single().activity.map { it.kind }).containsExactly("items_added")
    }

    @Test fun activityMigrationPreservesExistingLists() = http {
        val existing = client.create(alice)
        db.transaction { c -> c.execute("ALTER TABLE list DROP COLUMN activity"); c.execute("DELETE FROM schema_migration WHERE version=13") }
        db.migrate(); db.checkSchema()
        assertThat(client.changes(alice).lists.single()).isEqualTo(existing)
    }

    @Test fun listColorsAreSharedGuardedAndResettable() = http {
        val list = client.create(alice)
        client.share(alice, list.id, bob)
        fun color(id: String, value: String?, expected: String? = null) = Intent(id, list.id, "list_color", buildJsonObject {
            put("color", value?.let(::JsonPrimitive) ?: JsonNull); put("expected", expected?.let(::JsonPrimitive) ?: JsonNull)
        })
        val saved = client.apply(alice, list.id, color(uuid(), "rose"))
        assertThat(saved.status).isEqualTo(HttpStatusCode.OK)
        val after = Wire.json.decodeFromString<IntentBatchResult>(saved.bodyAsText()).list
        val read = client.changes(bob).lists.single { it.id == list.id }
        assertThat(ListAppearance.color(read.projection)).isEqualTo(io.theficos.usuals.model.ListColor.ROSE)
        val stale = client.apply(bob, list.id, color(uuid(), "sage"))
        assertThat(Wire.json.decodeFromString<IntentBatchResult>(stale.bodyAsText()).results.single().outcome).isEqualTo("conflict")
        val reset = client.apply(bob, list.id, color(uuid(), null, ListAppearance.revision(after.projection)))
        assertThat(ListAppearance.color(Wire.json.decodeFromString<IntentBatchResult>(reset.bodyAsText()).list.projection)).isNull()
        assertThat(client.apply(carol, list.id, color(uuid(), "teal")).status).isEqualTo(HttpStatusCode.NotFound)
    }

    private fun http(relay: Relay = f.relay, operations: RelayMetrics = RelayMetrics(db), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { relayModule(relay, operations, webIdentity = f.web) }
        block()
    }
    private suspend fun HttpClient.send(method: HttpMethod, path: String, who: Account?, body: String? = null, cookie: Boolean = false, csrf: Boolean = cookie): HttpResponse =
        request(path) {
            this.method = method
            if (who != null && cookie) header(HttpHeaders.Cookie, "__Host-usuals-session=${who.cookie}")
            if (who != null && !cookie) bearerAuth(who.bearer)
            if (who != null && csrf) { header(HttpHeaders.Origin, f.origin); header("X-CSRF-Token", who.csrf) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
    private suspend fun HttpClient.create(who: Account, list: String = uuid(), name: String = "Groceries"): SharedList {
        val response = send(HttpMethod.Put, "/api/lists/$list", who, created(name))
        assertThat(response.status).isEqualTo(HttpStatusCode.Created)
        return Wire.json.decodeFromString(SharedList.serializer(), response.bodyAsText())
    }
    private suspend fun HttpClient.apply(who: Account, list: String, vararg intents: Intent): HttpResponse =
        send(HttpMethod.Post, "/api/lists/$list/intents", who, Wire.json.encodeToString(IntentBatch.serializer(), IntentBatch(intents.toList())))
    private suspend fun HttpClient.share(who: Account, list: String, with: Account) =
        send(HttpMethod.Post, "/api/lists/$list/members", who, Wire.json.encodeToString(AddMember.serializer(), AddMember(with.id)))
    private suspend fun HttpClient.changes(who: Account, cursor: String? = null, limit: Int? = null, wait: Int? = null, activity: Boolean = false): ListChanges {
        val query = listOfNotNull(cursor?.let { "cursor=$it" }, limit?.let { "limit=$it" }, wait?.let { "wait=$it" }, if (activity) "activity=1" else null).joinToString("&")
        val response = send(HttpMethod.Get, "/api/lists/changes?$query", who)
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        return Wire.json.decodeFromString(ListChanges.serializer(), response.bodyAsText())
    }
    private suspend fun HttpClient.archive(who: Account, list: String, archived: Boolean): SharedList {
        val response = send(HttpMethod.Put, "/api/lists/$list/archived", who, Wire.json.encodeToString(ArchiveList.serializer(), ArchiveList(archived)))
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        return Wire.json.decodeFromString(SharedList.serializer(), response.bodyAsText())
    }
    private fun flags(list: SharedList) = list.archived to list.archivedByOwner
    private fun created(name: String) = """{"projection":{"name":"$name"}}"""
    private fun capture(list: String, name: String = "Milk", intent: String = uuid()) = Intent(intent, list, "capture", buildJsonObject {
        put("proposedId", uuid()); put("key", Normalizer.normalize(name)); put("name", name); put("position", 1.0)
    }, normalizer = NORMALIZER_VERSION)
    private fun code(response: String) = Wire.json.parseToJsonElement(response).jsonObject.getValue("code").jsonPrimitive.content
    private fun seq(list: String) = db.transaction { c -> c.query("SELECT seq FROM list WHERE id=?", id(list)) { it.getLong(1) }.single() }
    /** Returns once another transaction queues behind a lock that [holder] holds. */
    private fun awaitBlockedBy(holder: Connection) {
        val pid = holder.query("SELECT pg_backend_pid()") { it.getInt(1) }.single()
        val deadline = System.nanoTime() + 10_000_000_000
        while (!db.transaction { c -> c.query("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE NOT granted AND ?=ANY(pg_blocking_pids(pid)))", pid) { it.getBoolean(1) }.single() }) {
            check(System.nanoTime() < deadline) { "nothing queued behind the held lock" }
            Thread.sleep(10)
        }
    }

    @Test fun createAnswersCreatedThenRetriesWithoutOverwritingAndRefusesTakenIds() = http {
        val list = uuid()
        val first = client.send(HttpMethod.Put, "/api/lists/$list", alice, created("Groceries"))
        assertThat(first.status).isEqualTo(HttpStatusCode.Created)
        assertThat(Wire.json.parseToJsonElement(first.bodyAsText()).jsonObject.keys).containsExactly("id", "seq", "role", "owner", "members", "projection", "archived", "archivedByOwner")
        val shared = Wire.json.decodeFromString(SharedList.serializer(), first.bodyAsText())
        assertThat(shared.role).isEqualTo("owner"); assertThat(flags(shared)).isEqualTo(false to false)
        assertThat(shared.owner).isEqualTo(Person(alice.id, "Alice", "alice@example.org"))
        assertThat(shared.members).containsExactly(Member(alice.id, "Alice", "owner"))
        assertThat(shared.projection.name).isEqualTo("Groceries")
        val retry = client.send(HttpMethod.Put, "/api/lists/$list", alice, created("Overwritten"))
        assertThat(retry.status).isEqualTo(HttpStatusCode.OK)
        assertThat(Wire.json.decodeFromString(SharedList.serializer(), retry.bodyAsText())).isEqualTo(shared)
        val taken = client.send(HttpMethod.Put, "/api/lists/$list", bob, created("Mine"))
        assertThat(taken.status).isEqualTo(HttpStatusCode.Conflict); assertThat(code(taken.bodyAsText())).isEqualTo("list_exists")
        assertThat(client.send(HttpMethod.Delete, "/api/lists/$list", alice).status).isEqualTo(HttpStatusCode.NoContent)
        for (who in listOf(alice, bob)) {
            val deleted = client.send(HttpMethod.Put, "/api/lists/$list", who, created("Again"))
            assertThat(deleted.status).isEqualTo(HttpStatusCode.Conflict); assertThat(code(deleted.bodyAsText())).isEqualTo("list_exists")
        }
        db.transaction { c -> c.execute("INSERT INTO list(id,owner,projection,seq,created_at) SELECT gen_random_uuid(),?,'\\x00',0,0 FROM generate_series(1,500)", id(alice.id)) }
        val full = client.send(HttpMethod.Put, "/api/lists/${uuid()}", alice, created("One too many"))
        assertThat(full.status).isEqualTo(HttpStatusCode.Conflict); assertThat(code(full.bodyAsText())).isEqualTo("limit_reached")
        client.create(bob)
    }

    @Test fun intentsApplyOnceRejectForeignListIdsAndRefuseOversizedResults() = http {
        val list = client.create(alice).id
        val milk = capture(list)
        val applied = client.apply(alice, list, milk)
        assertThat(applied.status).isEqualTo(HttpStatusCode.OK)
        assertThat(Wire.json.parseToJsonElement(applied.bodyAsText()).jsonObject.keys).containsExactly("results", "list")
        val result = Wire.json.decodeFromString(IntentBatchResult.serializer(), applied.bodyAsText())
        assertThat(result.results).containsExactly(IntentResult(milk.id, "applied"))
        assertThat(result.list.projection.rows).hasSize(1)
        assertThat(result.list.seq).isEqualTo(seq(list))
        val resent = Wire.json.decodeFromString(IntentBatchResult.serializer(), client.apply(alice, list, milk).bodyAsText())
        assertThat(resent.results).containsExactly(IntentResult(milk.id, "no_op"))
        assertThat(resent.list).isEqualTo(result.list)
        val foreign = client.apply(alice, list, capture(list, "Bread"), capture(uuid(), "Eggs"))
        assertThat(foreign.status).isEqualTo(HttpStatusCode.BadRequest); assertThat(code(foreign.bodyAsText())).isEqualTo("invalid")
        assertThat(client.apply(alice, list).status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(seq(list)).isEqualTo(result.list.seq)

        val limit = 7 * 1024 * 1024 - 288
        fun big(filler: Int) = Projection(name = "Big", preferences = mapOf("filler" to buildJsonObject { put("text", "x".repeat(filler)) }))
        val filler = limit - SnapshotCodec.encode(big(0)).size - 64
        val large = uuid()
        val put = client.send(HttpMethod.Put, "/api/lists/$large", alice, Wire.json.encodeToString(CreateList.serializer(), CreateList(big(filler))))
        assertThat(put.status).isEqualTo(HttpStatusCode.Created)
        val before = seq(large)
        val tooLarge = client.apply(alice, large, capture(large))
        assertThat(tooLarge.status).isEqualTo(HttpStatusCode.PayloadTooLarge); assertThat(code(tooLarge.bodyAsText())).isEqualTo("too_large")
        assertThat(seq(large)).isEqualTo(before)
        val oversized = client.send(HttpMethod.Put, "/api/lists/${uuid()}", alice, Wire.json.encodeToString(CreateList.serializer(), CreateList(big(filler + 128))))
        assertThat(oversized.status).isEqualTo(HttpStatusCode.PayloadTooLarge)
        // A client's snapshot has its facts replayed through the reducer, unlike a list the server reduced itself.
        val dangling = Projection(purchases = mapOf("p" to buildJsonObject { put("id", "p"); put("tripId", "missing") }))
        val refused = client.send(HttpMethod.Put, "/api/lists/${uuid()}", alice, Wire.json.encodeToString(CreateList.serializer(), CreateList(dangling)))
        assertThat(refused.status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test fun aListRemembersTheLastThousandIntentsForResends() = http {
        val list = client.create(alice).id
        val sent = (1..1200).map { capture(list, "Item $it") }
        var shared: SharedList? = null
        for (batch in sent.chunked(100)) {
            val response = client.apply(alice, list, *batch.toTypedArray())
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            shared = Wire.json.decodeFromString(IntentBatchResult.serializer(), response.bodyAsText()).list
        }
        assertThat(shared!!.projection.seen.keys.toList()).isEqualTo(sent.takeLast(1000).map { it.id })
        val inside = Wire.json.decodeFromString(IntentBatchResult.serializer(), client.apply(alice, list, sent[200]).bodyAsText())
        assertThat(inside.results).containsExactly(IntentResult(sent[200].id, "no_op"))
        assertThat(inside.list).isEqualTo(shared)
        val older = Wire.json.decodeFromString(IntentBatchResult.serializer(), client.apply(alice, list, sent[199]).bodyAsText())
        assertThat(older.results).containsExactly(IntentResult(sent[199].id, "rejected"))
        assertThat(older.list.projection.rows).isEqualTo(shared.projection.rows)
    }

    @Test fun feedPagesInSeqOrderAndResetsWithoutAKnownCursor() = http {
        val lists = (1..3).map { client.create(alice, name = "List $it").id }
        val reset = client.changes(alice)
        assertThat(reset.reset).isTrue(); assertThat(reset.hasMore).isFalse(); assertThat(reset.removed).isEmpty()
        assertThat(reset.lists.map { it.id }).containsExactlyElementsIn(lists).inOrder()
        assertThat(reset.cursor).isEqualTo("original.${reset.lists.last().seq}")
        val first = client.changes(alice, limit = 2)
        assertThat(first.lists.map { it.id }).containsExactly(lists[0], lists[1]).inOrder(); assertThat(first.hasMore).isTrue(); assertThat(first.reset).isTrue()
        val second = client.changes(alice, first.cursor, limit = 2)
        assertThat(second.lists.map { it.id }).containsExactly(lists[2]); assertThat(second.hasMore).isFalse(); assertThat(second.reset).isFalse()
        assertThat(client.changes(alice, second.cursor).lists).isEmpty()
        client.create(bob)
        val unrelated = client.changes(alice, second.cursor)
        assertThat(unrelated.lists).isEmpty(); assertThat(unrelated.cursor).isNotEqualTo(second.cursor)
        client.apply(alice, lists[0], capture(lists[0]))
        assertThat(client.changes(alice, unrelated.cursor).lists.map { it.id }).containsExactly(lists[0])
        for (stale in listOf("restored.1", "original.999999")) {
            val again = client.changes(alice, stale)
            assertThat(again.reset).isTrue(); assertThat(again.lists).hasSize(3)
        }
        for (bad in listOf("original", "original.x", "original.-1", ".1")) assertThat(client.send(HttpMethod.Get, "/api/lists/changes?cursor=$bad", alice).status).isEqualTo(HttpStatusCode.BadRequest)
        for (bad in listOf("limit=0", "limit=201", "wait=26", "wait=-1")) assertThat(client.send(HttpMethod.Get, "/api/lists/changes?$bad", alice).status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test fun membersLeaveAreRemovedAndReAddedThroughRemovedEntries() = http {
        val list = client.create(alice).id
        val shared = Wire.json.decodeFromString(SharedList.serializer(), client.share(alice, list, bob).bodyAsText())
        assertThat(shared.members).containsExactly(Member(alice.id, "Alice", "owner"), Member(bob.id, "Bob Builder", "editor")).inOrder()
        val joined = client.changes(bob)
        assertThat(joined.lists.single().role).isEqualTo("editor")
        assertThat(client.send(HttpMethod.Delete, "/api/lists/$list/members/${bob.id}", bob).status).isEqualTo(HttpStatusCode.NoContent)
        val left = client.changes(bob, joined.cursor)
        assertThat(left.lists).isEmpty(); assertThat(left.removed.single().id).isEqualTo(list)
        assertThat(client.changes(alice).lists.single().members.map { it.id }).containsExactly(alice.id)
        client.share(alice, list, bob)
        val readded = client.changes(bob, left.cursor)
        assertThat(readded.lists.single().id).isEqualTo(list); assertThat(readded.removed).isEmpty()
        assertThat(client.send(HttpMethod.Delete, "/api/lists/$list/members/${bob.id}", alice).status).isEqualTo(HttpStatusCode.NoContent)
        val removed = client.changes(bob, readded.cursor)
        assertThat(removed.removed.single()).isEqualTo(RemovedList(list, seq(list)))
        client.share(alice, list, bob)
        val beforeDelete = client.changes(bob, removed.cursor).cursor
        val aliceCursor = client.changes(alice).cursor
        assertThat(client.send(HttpMethod.Delete, "/api/lists/$list", alice).status).isEqualTo(HttpStatusCode.NoContent)
        assertThat(client.changes(bob, beforeDelete).removed.map { it.id }).containsExactly(list)
        assertThat(client.changes(alice, aliceCursor).removed.map { it.id }).containsExactly(list)
        assertThat(client.changes(alice).lists).isEmpty()
        assertThat(client.apply(bob, list, capture(list)).status).isEqualTo(HttpStatusCode.NotFound)
        assertThat(client.send(HttpMethod.Put, "/api/lists/$list/archived", alice, """{"archived":true}""").status).isEqualTo(HttpStatusCode.NotFound)
    }

    @Test fun addingAnAccountWhoseRequestHoldsItsRowAndWaitsForTheCounterDoesNotDeadlock() = http {
        val list = client.create(alice).id
        database.pool.connection.use { phone ->
            // Bob's phone creating a list: his account row FOR UPDATE first, the change counter last.
            withContext(Dispatchers.IO) {
                phone.execute("SET LOCAL lock_timeout = '3s'")
                phone.query("SELECT 1 FROM web_user WHERE id=? FOR UPDATE", id(bob.id)) { Unit }
            }
            coroutineScope {
                val share = async { client.share(alice, list, bob) }
                withContext(Dispatchers.IO) {
                    awaitBlockedBy(phone)
                    phone.query("UPDATE list_counter SET seq=seq+1 RETURNING seq") { it.getLong(1) }
                    phone.commit()
                }
                val shared = share.await()
                assertThat(shared.status).isEqualTo(HttpStatusCode.OK)
                assertThat(Wire.json.decodeFromString(SharedList.serializer(), shared.bodyAsText()).members.map { it.id }).containsExactly(alice.id, bob.id)
            }
        }
    }

    @Test fun aPhoneRequestInFlightDoesNotHoldUpAddingItsAccountToAList() = http {
        val list = client.create(alice).id
        database.pool.connection.use { phone ->
            // Bob's phone mid-request: the sign-in check keeps his account row locked until the request commits.
            withContext(Dispatchers.IO) { Connections(db, f.relay, f.web).allowed(phone, bob.id) }
            assertThat(client.share(alice, list, bob).status).isEqualTo(HttpStatusCode.OK)
            phone.commit()
        }
    }

    @Test fun sharingDoesNotWaitForTheOwnersPhoneAccountLockWhileHoldingItsList() = http {
        val list = client.create(alice).id
        client.share(alice, list, bob)
        val lists = Lists(f.relay, f.web.config)
        val authorized = CompletableDeferred<Unit>()
        val proceed = CountDownLatch(1)
        coroutineScope {
            val phone = async(Dispatchers.IO) {
                lists.apply(AccountAccess(alice.id) { c ->
                    assertThat(Connections(db, f.relay, f.web).caller(c, alice.bearer)).isEqualTo(alice.id)
                    authorized.complete(Unit)
                    check(proceed.await(10, TimeUnit.SECONDS)) { "phone was not released" }
                }, list, IntentBatch(listOf(capture(list))))
            }
            try {
                withTimeout(5000) { authorized.await() }
                // The editor's browser reaches membership while the owner's phone retains its account row.
                val share = client.send(HttpMethod.Post, "/api/lists/$list/members", bob,
                    Wire.json.encodeToString(AddMember.serializer(), AddMember(carol.id)), cookie = true)
                assertThat(share.status).isEqualTo(HttpStatusCode.OK)
            } finally { proceed.countDown() }
            assertThat(phone.await().list.members.map { it.id }).containsExactly(alice.id, bob.id, carol.id)
            assertThat(client.changes(alice).lists.single().projection.rows.values.single().facts["name"]?.jsonPrimitive?.content).isEqualTo("Milk")
        }
    }

    @Test fun targetPolicyUpdatesDoNotBlockItsMembershipForeignKey() = http {
        val list = client.create(alice).id
        val limits = f.web.config.accountLimits
        val policy = AccountPolicy(db, limits, ByteArray(32)) { now }
        database.pool.connection.use { gate ->
            withContext(Dispatchers.IO) { limits.lockMemberPolicy(gate, carol.id) }
            coroutineScope {
                val updating = async(Dispatchers.IO) {
                    policy.put(carol.id, AccountPolicyUpdate(AccountLimit(10, 10), AccountLimit(1, 1), now + 300000))
                }
                try {
                    // The real policy writer has authorized and locked its account before waiting here.
                    withContext(Dispatchers.IO) { awaitBlockedBy(gate) }
                    val share = client.send(HttpMethod.Post, "/api/lists/$list/members", alice,
                        Wire.json.encodeToString(AddMember.serializer(), AddMember(carol.id)), cookie = true)
                    assertThat(share.status).isEqualTo(HttpStatusCode.OK)
                } finally { withContext(Dispatchers.IO) { gate.rollback() } }
                assertThat(updating.await().maxListMembers).isEqualTo(10)
                assertThat(client.changes(carol).lists.single().id).isEqualTo(list)
            }
        }
    }

    @Test fun aFeedRequestIsNotIdleWhileItsInitialReadStillHoldsARequestSlot() {
        val operations = RelayMetrics(db)
        http(operations = operations) {
            val cursor = client.changes(bob).cursor
            coroutineScope {
                val waiting = database.pool.connection.use { holder ->
                    holder.execute("LOCK TABLE list_counter IN ACCESS EXCLUSIVE MODE")
                    val request = async { client.changes(bob, cursor, wait = 25) }
                    try {
                        withContext(Dispatchers.IO) { awaitBlockedBy(holder) }
                        assertThat(operations.waiting.get()).isEqualTo(0)
                    } finally { holder.rollback() }
                    request
                }
                withTimeout(10000) { while (operations.waiting.get() < 1) delay(10) }
                val own = client.create(bob).id
                assertThat(withTimeout(10000) { waiting.await() }.lists.single().id).isEqualTo(own)
                assertThat(operations.waiting.get()).isEqualTo(0)
            }
        }
    }

    @Test fun aChangeByOneAccountWakesAnotherAccountsWaitingRequestWithoutHoldingASlot() {
        val operations = RelayMetrics(db)
        http(operations = operations) {
            val list = client.create(alice).id
            client.share(alice, list, bob)
            val cursor = client.changes(bob).cursor
            val idle = client.changes(bob, cursor, wait = 1)
            assertThat(idle.lists).isEmpty(); assertThat(idle.cursor).isEqualTo(cursor)
            assertThat(operations.waiting.get()).isEqualTo(0)
            val empty = client.changes(carol).cursor
            coroutineScope {
                val bobs = (1..4).map { async { client.changes(bob, cursor, wait = 25) } }
                val carols = (1..4).map { async { client.changes(carol, empty, wait = 25) } }
                // Registration happens before the first read; count only requests that have released their slots.
                withTimeout(10000) { while (operations.waiting.get() < 8) delay(10) }
                val refused = client.send(HttpMethod.Get, "/api/lists/changes?cursor=$empty&wait=25", carol)
                assertThat(refused.status.value).isEqualTo(429); assertThat(code(refused.bodyAsText())).isEqualTo("rate_limited")
                // Eight waiting requests would occupy every request slot if waiting held one.
                assertThat(withTimeout(5000) { client.get("/readyz") }.status).isEqualTo(HttpStatusCode.OK)
                assertThat(client.apply(alice, list, capture(list)).status).isEqualTo(HttpStatusCode.OK)
                withTimeout(10000) { bobs.awaitAll() }.forEach { assertThat(it.lists.single().id).isEqualTo(list) }
                val own = client.create(carol).id
                withTimeout(10000) { carols.awaitAll() }.forEach { assertThat(it.lists.single().id).isEqualTo(own) }
            }
            withTimeout(5000) { while (operations.waiting.get() > 0) delay(10) }
        }
    }

    @Test fun nonMembersGetNotFoundFromEveryListRoute() = http {
        val list = client.create(alice).id
        for (target in listOf(list, uuid())) {
            assertThat(client.apply(carol, target, capture(target)).status).isEqualTo(HttpStatusCode.NotFound)
            assertThat(client.send(HttpMethod.Delete, "/api/lists/$target", carol).status).isEqualTo(HttpStatusCode.NotFound)
            assertThat(client.share(carol, target, bob).status).isEqualTo(HttpStatusCode.NotFound)
            assertThat(client.send(HttpMethod.Delete, "/api/lists/$target/members/${alice.id}", carol).status).isEqualTo(HttpStatusCode.NotFound)
            assertThat(client.send(HttpMethod.Delete, "/api/lists/$target/members/${carol.id}", carol).status).isEqualTo(HttpStatusCode.NotFound)
            val archive = client.send(HttpMethod.Put, "/api/lists/$target/archived", carol, """{"archived":true}""")
            assertThat(archive.status).isEqualTo(HttpStatusCode.NotFound); assertThat(code(archive.bodyAsText())).isEqualTo("not_found")
        }
        assertThat(client.changes(carol).lists).isEmpty()
    }

    @Test fun onlyTheOwnerDeletesOrRemovesOthersAndTheOwnerCannotLeave() = http {
        val list = client.create(alice).id
        client.share(alice, list, bob)
        for ((who, path) in listOf(bob to "/api/lists/$list", alice to "/api/lists/$list/members/${alice.id}", bob to "/api/lists/$list/members/${alice.id}")) {
            val response = client.send(HttpMethod.Delete, path, who)
            assertThat(response.status).isEqualTo(HttpStatusCode.Forbidden); assertThat(code(response.bodyAsText())).isEqualTo("forbidden")
        }
        assertThat(client.share(bob, list, carol).status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.send(HttpMethod.Delete, "/api/lists/$list/members/${carol.id}", bob).status).isEqualTo(HttpStatusCode.Forbidden)
        val unchanged = seq(list)
        assertThat(client.share(bob, list, carol).status).isEqualTo(HttpStatusCode.OK)
        assertThat(seq(list)).isEqualTo(unchanged)
        assertThat(client.send(HttpMethod.Delete, "/api/lists/$list/members/${carol.id}", alice).status).isEqualTo(HttpStatusCode.NoContent)
        val disabled = f.account("Disabled")
        db.transaction { it.execute("UPDATE web_user SET disabled=true WHERE id=?", id(disabled.id)) }
        assertThat(client.share(alice, list, disabled).status).isEqualTo(HttpStatusCode.NotFound)
        assertThat(client.send(HttpMethod.Post, "/api/lists/$list/members", alice, """{"userId":"${uuid()}"}""").status).isEqualTo(HttpStatusCode.NotFound)
        db.transaction { c ->
            c.execute("INSERT INTO web_user(id,issuer,subject,display_name,created_at,last_login_at) SELECT gen_random_uuid(),?,gen_random_uuid()::text,'Filler',0,0 FROM generate_series(1,48)", WebIdentityConfig.LOCAL_ISSUER)
            c.execute("INSERT INTO list_member(list_id,user_id,role) SELECT ?,id,'editor' FROM web_user WHERE display_name='Filler'", id(list))
        }
        val full = client.share(alice, list, carol)
        assertThat(full.status).isEqualTo(HttpStatusCode.Conflict); assertThat(code(full.bodyAsText())).isEqualTo("limit_reached")
    }

    @Test fun theOwnerArchivesAndRestoresAListForEveryMember() {
        val operations = RelayMetrics(db)
        http(operations = operations) {
            val list = client.create(alice).id
            client.share(alice, list, bob)
            val cursor = client.changes(bob).cursor
            coroutineScope {
                val waiting = async { client.changes(bob, cursor, wait = 25) }
                withTimeout(10000) { while (operations.waiting.get() < 1) delay(10) }
                val archived = client.archive(alice, list, true)
                assertThat(flags(archived)).isEqualTo(true to true); assertThat(archived.seq).isEqualTo(seq(list))
                val woken = withTimeout(10000) { waiting.await() }.lists.single()
                assertThat(flags(woken)).isEqualTo(true to true); assertThat(woken.seq).isEqualTo(archived.seq)
            }
            // An archived list stays editable, and every response that carries it says it is archived.
            val edited = client.apply(bob, list, capture(list))
            assertThat(edited.status).isEqualTo(HttpStatusCode.OK)
            assertThat(flags(Wire.json.decodeFromString(IntentBatchResult.serializer(), edited.bodyAsText()).list)).isEqualTo(true to true)
            assertThat(flags(Wire.json.decodeFromString(SharedList.serializer(), client.share(bob, list, carol).bodyAsText()))).isEqualTo(true to true)
            assertThat(flags(Wire.json.decodeFromString(SharedList.serializer(), client.send(HttpMethod.Put, "/api/lists/$list", alice, created("Retry")).bodyAsText()))).isEqualTo(true to true)
            for (who in listOf(alice, bob, carol)) assertThat(flags(client.changes(who).lists.single())).isEqualTo(true to true)
            val restored = client.archive(alice, list, false)
            assertThat(flags(restored)).isEqualTo(false to false); assertThat(restored.seq).isEqualTo(seq(list))
            for (who in listOf(alice, bob, carol)) assertThat(flags(client.changes(who).lists.single())).isEqualTo(false to false)
        }
    }

    @Test fun anEditorArchivesOnlyForThemselfAndCannotUndoTheOwnersArchive() = http {
        val list = client.create(alice).id
        client.share(alice, list, bob); client.share(alice, list, carol)
        val before = seq(list)
        val mine = client.archive(bob, list, true)
        assertThat(flags(mine)).isEqualTo(true to false); assertThat(mine.seq).isGreaterThan(before); assertThat(mine.seq).isEqualTo(seq(list))
        assertThat(flags(client.changes(bob).lists.single())).isEqualTo(true to false)
        // Every member's feed carries the new seq, but the list is archived for nobody else.
        for (who in listOf(alice, carol)) client.changes(who).lists.single().let { assertThat(flags(it)).isEqualTo(false to false); assertThat(it.seq).isEqualTo(mine.seq) }
        client.archive(alice, list, true)
        val undone = client.archive(bob, list, false)
        assertThat(flags(undone)).isEqualTo(true to true); assertThat(undone.seq).isEqualTo(seq(list))
        client.archive(carol, list, true)
        assertThat(flags(client.archive(alice, list, false))).isEqualTo(false to false)
        assertThat(flags(client.changes(bob).lists.single())).isEqualTo(false to false)
        assertThat(flags(client.changes(carol).lists.single())).isEqualTo(true to false)
    }

    @Test fun anArchiveRequestThatChangesNothingKeepsTheSeq() = http {
        val list = client.create(alice).id
        client.share(alice, list, bob)
        val start = seq(list)
        for (who in listOf(alice, bob)) assertThat(client.archive(who, list, false).seq).isEqualTo(start)
        val archived = client.archive(alice, list, true).seq
        assertThat(archived).isGreaterThan(start)
        assertThat(client.archive(alice, list, true).seq).isEqualTo(archived)
        // The editor's own flag is already clear, so restoring the owner's archive changes nothing.
        client.archive(bob, list, false).let { assertThat(flags(it)).isEqualTo(true to true); assertThat(it.seq).isEqualTo(archived) }
        val own = client.archive(bob, list, true).seq
        assertThat(own).isGreaterThan(archived)
        assertThat(client.archive(bob, list, true).seq).isEqualTo(own)
        assertThat(seq(list)).isEqualTo(own)
        for (bad in listOf("{}", """{"archived":"yes"}""")) assertThat(client.send(HttpMethod.Put, "/api/lists/$list/archived", alice, bad).status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test fun beingAddedAgainClearsAnEditorsOwnArchive() = http {
        val list = client.create(alice).id
        client.share(alice, list, bob)
        client.archive(bob, list, true)
        // Adding a current member changes nothing, so the flag stays.
        client.share(alice, list, bob)
        assertThat(flags(client.changes(bob).lists.single())).isEqualTo(true to false)
        assertThat(client.send(HttpMethod.Delete, "/api/lists/$list/members/${bob.id}", bob).status).isEqualTo(HttpStatusCode.NoContent)
        client.share(alice, list, bob)
        assertThat(flags(client.changes(bob).lists.single())).isEqualTo(false to false)
    }

    @Test fun peopleSearchMatchesNameOrEmailAndSkipsSelfAndDisabledAccounts() = http {
        val dave = f.account("dave", "DAVE@Example.org")
        val disabled = f.account("Bobby Disabled", "bobby@example.org")
        db.transaction { it.execute("UPDATE web_user SET disabled=true WHERE id=?", id(disabled.id)) }
        db.transaction { it.execute("INSERT INTO web_user(id,issuer,subject,display_name,email,created_at,last_login_at) VALUES (?,?,?,?,?,0,0)", id(uuid()), "https://other.example", "x", "Bobcat", "bobcat@example.org") }
        suspend fun people(q: String) = Wire.json.decodeFromString(People.serializer(), client.send(HttpMethod.Get, "/api/people?q=$q", alice).bodyAsText()).people
        assertThat(people("BO")).containsExactly(Person(bob.id, "Bob Builder", "bob@example.org"))
        assertThat(people("example.ORG")).containsExactly(Person(bob.id, "Bob Builder", "bob@example.org"), Person(dave.id, "dave", "DAVE@Example.org")).inOrder()
        assertThat(people("").map { it.id }).containsExactly(bob.id, carol.id, dave.id).inOrder()
        assertThat(people("").single { it.id == carol.id }.detail).isNull()
        assertThat(client.send(HttpMethod.Get, "/api/people?q=${"a".repeat(201)}", alice).status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.send(HttpMethod.Get, "/api/people", null).status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test fun phonesUseBearersAndBrowsersUseCookiesWithCsrfOnMutations() = http {
        val list = uuid()
        val forged = client.send(HttpMethod.Put, "/api/lists/$list", alice, created("Cookie"), cookie = true, csrf = false)
        assertThat(forged.status).isEqualTo(HttpStatusCode.Forbidden)
        assertThat(client.send(HttpMethod.Put, "/api/lists/$list", alice, created("Cookie"), cookie = true).status).isEqualTo(HttpStatusCode.Created)
        val read = client.send(HttpMethod.Get, "/api/lists/changes", alice, cookie = true, csrf = false)
        assertThat(read.status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.apply(alice, list, capture(list)).status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.send(HttpMethod.Put, "/api/lists/$list/archived", alice, """{"archived":true}""", cookie = true, csrf = false).status).isEqualTo(HttpStatusCode.Forbidden)
        assertThat(client.send(HttpMethod.Put, "/api/lists/$list/archived", alice, """{"archived":true}""", cookie = true).status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.send(HttpMethod.Delete, "/api/lists/$list", alice, cookie = true, csrf = false).status).isEqualTo(HttpStatusCode.Forbidden)
        assertThat(client.send(HttpMethod.Get, "/api/lists/changes", null).status).isEqualTo(HttpStatusCode.Unauthorized)
        db.transaction { it.execute("UPDATE native_session SET revoked=true WHERE hash=?", digest(alice.bearer)) }
        assertThat(client.send(HttpMethod.Get, "/api/lists/changes", alice).status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test fun largeListResponsesAreCompressed() = http {
        val list = uuid()
        val body = Wire.json.encodeToString(CreateList.serializer(), CreateList(Projection(name = "Big", preferences = mapOf("p" to buildJsonObject { put("text", "milk ".repeat(1000)) }))))
        assertThat(client.send(HttpMethod.Put, "/api/lists/$list", alice, body).status).isEqualTo(HttpStatusCode.Created)
        val response = client.get("/api/lists/changes") { bearerAuth(alice.bearer); header(HttpHeaders.AcceptEncoding, "gzip") }
        assertThat(response.headers[HttpHeaders.ContentEncoding]).isEqualTo("gzip")
        val plain = Wire.json.decodeFromString(ListChanges.serializer(), java.util.zip.GZIPInputStream(response.readRawBytes().inputStream()).readBytes().decodeToString())
        assertThat(plain.lists.single().projection.preferences.getValue("p")).isNotNull()
    }

    @Test fun removedHouseholdRoutesAnswerUpgradeRequired() = http {
        val paths = listOf("/v1/households", "/v1/households/${uuid()}/lists", "/v1/invites/${uuid()}/open", "/v1/join-requests", "/v1/lists/${uuid()}/operations",
            "/v1/devices/challenge", "/v1/devices/session", "/v1/changes", "/v1/account/households", "/api/households", "/api/devices/link")
        for (path in paths) for (method in listOf(HttpMethod.Get, HttpMethod.Post)) {
            val response = client.send(method, path, alice)
            assertThat(response.status).isEqualTo(HttpStatusCode.UpgradeRequired)
            assertThat(code(response.bodyAsText())).isEqualTo("upgrade_required")
        }
        assertThat(client.get("/v1/server-info").status).isEqualTo(HttpStatusCode.OK)
    }

    @Test fun bothCredentialKindsSlideAtMostOncePerDay() = http {
        fun expiry(table: String, column: String, value: String) = db.transaction { c -> c.query("SELECT expires FROM $table WHERE $column=?", digest(value)) { it.getLong(1) }.single() }
        db.transaction { it.execute("UPDATE web_session SET expires=? WHERE token_hash=?", now + 900000, digest(alice.cookie)) }
        now += 2 * RelayFixture.DAY
        assertThat(client.send(HttpMethod.Get, "/api/lists/changes", alice).status).isEqualTo(HttpStatusCode.OK)
        assertThat(expiry("native_session", "hash", alice.bearer)).isEqualTo(now + 180 * RelayFixture.DAY)
        assertThat(client.send(HttpMethod.Get, "/api/me", alice, cookie = true).status).isEqualTo(HttpStatusCode.Unauthorized)
        db.transaction { it.execute("UPDATE web_session SET expires=? WHERE token_hash=?", now + 60000, digest(alice.cookie)) }
        val renewed = client.send(HttpMethod.Get, "/api/me", alice, cookie = true)
        assertThat(renewed.status).isEqualTo(HttpStatusCode.OK)
        assertThat(expiry("web_session", "token_hash", alice.cookie)).isEqualTo(now + 30 * RelayFixture.DAY)
        assertThat(renewed.headers.getAll(HttpHeaders.SetCookie)!!.single()).contains("Max-Age=2592000")
        val slid = now
        now += 3600000
        assertThat(client.send(HttpMethod.Get, "/api/me", alice, cookie = true).headers[HttpHeaders.SetCookie]).isNull()
        assertThat(client.send(HttpMethod.Get, "/api/lists/changes", alice).status).isEqualTo(HttpStatusCode.OK)
        assertThat(expiry("web_session", "token_hash", alice.cookie)).isEqualTo(slid + 30 * RelayFixture.DAY)
        assertThat(expiry("native_session", "hash", alice.bearer)).isEqualTo(slid + 180 * RelayFixture.DAY)
        now = slid + 30 * RelayFixture.DAY
        assertThat(client.send(HttpMethod.Get, "/api/me", alice, cookie = true).status).isEqualTo(HttpStatusCode.Unauthorized)
        now = slid + 180 * RelayFixture.DAY
        assertThat(client.send(HttpMethod.Get, "/api/lists/changes", alice).status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test fun restoreFenceRotationMakesOldCursorsReset() = http {
        val list = client.create(alice).id
        val cursor = client.changes(alice).cursor
        db.fence(identity.serverId, "restored")
        assertThat(client.send(HttpMethod.Get, "/api/lists/changes?cursor=$cursor", alice).status).isEqualTo(HttpStatusCode.Unauthorized)
        val fresh = f.signIn(alice.id)
        val again = client.changes(fresh, cursor)
        assertThat(again.reset).isTrue(); assertThat(again.lists.single().id).isEqualTo(list)
        assertThat(again.cursor).startsWith("restored.")
    }

    @Test fun storedProjectionsHoldNoPlaintextAndAreBoundToTheirList() = http {
        val first = client.create(alice, name = "synthetic-secret-milk").id
        val second = client.create(alice, name = "Other").id
        client.apply(alice, first, capture(first, "synthetic-secret-bread"))
        val stored = db.transaction { c -> c.query("SELECT projection FROM list") { it.getBytes(1) } }
        assertThat(stored).hasSize(2)
        stored.forEach { assertThat(it.decodeToString()).doesNotContain("synthetic-secret") }
        db.transaction { it.execute("UPDATE list SET projection=(SELECT projection FROM list WHERE id=?) WHERE id=?", id(first), id(second)) }
        val moved = client.apply(alice, second, capture(second))
        assertThat(moved.status).isEqualTo(HttpStatusCode.ServiceUnavailable); assertThat(code(moved.bodyAsText())).isEqualTo("unavailable")
        assertThat(client.send(HttpMethod.Get, "/api/lists/changes", alice).status).isEqualTo(HttpStatusCode.ServiceUnavailable)
    }

    @Test fun aWrongKeyFailsClosedAndRewrapRoundTrips() {
        http { client.create(alice, name = "Kept") }
        val other = ListCipher(com.google.crypto.tink.subtle.Random.randBytes(32))
        assertThat(assertThrows(IllegalStateException::class.java) { other.check(db) }.message).isEqualTo("list_key_mismatch")
        http(Relay(db, identity, f.auth, other)) {
            val response = client.send(HttpMethod.Get, "/api/lists/changes", alice)
            assertThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable); assertThat(code(response.bodyAsText())).isEqualTo("unavailable")
        }
        f.relay.cipher.rewrap(db, other)
        other.check(db)
        assertThrows(IllegalStateException::class.java) { f.relay.cipher.check(db) }
        http(Relay(db, identity, f.auth, other)) { assertThat(client.changes(alice).lists.single().projection.name).isEqualTo("Kept") }
        // Rewrapping from a key that does not open the lists changes nothing.
        assertThrows(RelayError::class.java) { f.relay.cipher.rewrap(db, ListCipher(com.google.crypto.tink.subtle.Random.randBytes(32))) }
        other.check(db)
    }

    @Test fun startupRequiresAPrivateListKeyFile() {
        assertThat(assertThrows(IllegalArgumentException::class.java) { ListCipher.fromEnvironment(emptyMap()) }.message).isEqualTo("missing_config:USUALS_LIST_KEY_FILE")
        val directory = Files.createTempDirectory("usuals-list-key")
        try {
            val key = directory.resolve("key")
            Files.write(key, ByteArray(32) { 3 }); Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-r-----"))
            assertThrows(IllegalArgumentException::class.java) { ListCipher.fromEnvironment(mapOf("USUALS_LIST_KEY_FILE" to key.toString())) }
            Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"))
            ListCipher.fromEnvironment(mapOf("USUALS_LIST_KEY_FILE" to key.toString()))
            Files.write(key, ByteArray(31))
            assertThrows(IllegalArgumentException::class.java) { ListCipher.fromEnvironment(mapOf("USUALS_LIST_KEY_FILE" to key.toString())) }
        } finally { directory.toFile().deleteRecursively() }
    }
}
