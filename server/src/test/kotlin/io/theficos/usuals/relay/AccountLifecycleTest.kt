package io.theficos.usuals.relay

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.theficos.usuals.sync.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class AccountLifecycleTest {
    private val database = TestDatabase()
    private val db = database.db
    private var now = 1_800_000_000_000L
    private lateinit var f: RelayFixture
    private lateinit var alice: Account
    private lateinit var bob: Account
    private lateinit var lifecycle: AccountLifecycle
    private val key = ByteArray(32) { 21 }
    private val password = "synthetic-long-password"
    @Before fun before() {
        db.migrate(); f = RelayFixture(db, clock = { now }); db.fence(f.identity.serverId, "synthetic")
        alice = f.account("Alice", "alice@example.test"); bob = f.account("Bob", "bob@example.test")
        for (account in listOf(alice, bob)) {
            val salt = LocalPasswords.salt()
            db.transaction { it.execute("INSERT INTO local_credential(user_id,password_hash,salt) VALUES (?,?,?)", id(account.id), LocalPasswords.hash(password, salt), salt) }
        }
        lifecycle = AccountLifecycle(f.relay, f.web, key) { now }
    }
    @After fun after() = database.close()
    private fun access(account: Account): AccountAccess { val caller = f.web.reader(account.cookie); return AccountAccess(account.id) { f.web.current(it, caller) } }
    private fun create(account: Account): String { val list = uuid(); Lists(f.relay, f.web.config).create(access(account), list, CreateList(Projection(name = "Synthetic"))); return list }
    private fun request(shared: Boolean = false, requestId: String = uuid()) = ClosureRequest(requestId, "DELETE", shared, if (shared) "DELETE SHARED LISTS" else "", password)

    @Test fun authorizationRequiresFreshCredentialsAndExplicitSharedListConfirmation() {
        val list = create(alice)
        Lists(f.relay, f.web.config).add(access(alice), list, AddMember(bob.id))
        val caller = f.web.reader(alice.cookie)
        assertThat(assertThrows(RelayError::class.java) { lifecycle.authorize(caller, request().copy(password = "wrong")) }.code).isEqualTo("reauthentication_required")
        assertThat(assertThrows(RelayError::class.java) { lifecycle.authorize(caller, request()) }.code).isEqualTo("shared_owned_lists_require_confirmation")
        assertThat(assertThrows(RelayError::class.java) { lifecycle.authorize(caller, request(true).copy(sharedConfirmation = "")) }.code).isEqualTo("shared_deletion_confirmation_required")
        assertThat(db.transaction { c -> c.query("SELECT count(*) FROM account_closure") { it.getInt(1) }.single() }).isEqualTo(0)
    }

    @Test fun closureIsIdempotentRevokesBothCredentialsAndPreservesOtherOwnersLists() = testApplication {
        application { relayModule(f.relay, webIdentity = f.web, accountLifecycle = lifecycle) }
        val own = create(alice); val other = create(bob)
        val lists = Lists(f.relay, f.web.config)
        lists.add(access(alice), own, AddMember(bob.id)); lists.add(access(bob), other, AddMember(alice.id))
        val request = request(true)
        val authorization = client.post("/api/account/closure-requests") {
            header(HttpHeaders.Cookie, "__Host-usuals-session=${alice.cookie}"); header(HttpHeaders.Origin, f.origin); header("X-CSRF-Token", alice.csrf)
            contentType(ContentType.Application.Json); setBody(Wire.json.encodeToString(ClosureRequest.serializer(), request))
        }
        assertThat(authorization.status).isEqualTo(HttpStatusCode.OK)
        val path = "/api/service/accounts/${alice.id}/closure/${request.id}"
        assertThat(client.post(path).status).isEqualTo(HttpStatusCode.Unauthorized)
        for (repeat in 1..2) assertThat(client.post(path) { bearerAuth(Wire.encode(key)) }.status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.get("/api/me") { header(HttpHeaders.Cookie, "__Host-usuals-session=${alice.cookie}") }.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(client.get("/api/lists/changes") { bearerAuth(alice.bearer) }.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(db.transaction { c -> c.query("SELECT projection IS NULL FROM list WHERE id=?", id(own)) { it.getBoolean(1) }.single() }).isTrue()
        assertThat(db.transaction { c -> c.query("SELECT projection IS NOT NULL FROM list WHERE id=?", id(other)) { it.getBoolean(1) }.single() }).isTrue()
        assertThat(lists.changes(access(bob), null, 200).lists.map { it.id }).containsExactly(other)
        assertThat(db.transaction { c -> c.query("SELECT count(*) FROM list_member WHERE user_id=?", id(alice.id)) { it.getInt(1) }.single() }).isEqualTo(0)
        for (table in listOf("local_credential", "web_session", "native_session", "connection_ticket", "ai_preference"))
            assertThat(db.transaction { c -> c.query("SELECT count(*) FROM $table WHERE user_id=?", id(alice.id)) { it.getInt(1) }.single() }).isEqualTo(0)
        assertThat(client.post("$path/purge") { bearerAuth(Wire.encode(key)) }.status).isEqualTo(HttpStatusCode.OK)
        val status = lifecycle.status(alice.id, request.id)
        assertThat(status.state).isEqualTo("purged"); assertThat(status.issuer).isNull(); assertThat(status.subject).isNull()
    }

    @Test fun exportUsesTheSameAccountAclAndNeverIncludesCredentials() = testApplication {
        application { relayModule(f.relay, webIdentity = f.web) }
        val own = create(alice); val shared = create(bob); create(f.account("Unrelated"))
        Lists(f.relay, f.web.config).add(access(bob), shared, AddMember(alice.id))
        val response = client.get("/api/account/export") { header(HttpHeaders.Cookie, "__Host-usuals-session=${alice.cookie}") }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val body = response.bodyAsText(); val json = Wire.json.parseToJsonElement(body).jsonObject
        assertThat(json.getValue("account").jsonObject.getValue("id").jsonPrimitive.content).isEqualTo(alice.id)
        assertThat(json.getValue("lists").jsonObject.getValue("lists").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }).containsExactly(own, shared)
        for (secret in listOf(password, alice.cookie, alice.bearer, "password_hash", "salt")) assertThat(body).doesNotContain(secret)
    }

    @Test fun sharingAddedAfterAuthorizationStillBlocksUntilFreshExplicitChoice() {
        val list = create(alice)
        val request = request()
        lifecycle.authorize(f.web.reader(alice.cookie), request)
        Lists(f.relay, f.web.config).add(access(alice), list, AddMember(bob.id))
        assertThat(assertThrows(RelayError::class.java) { lifecycle.close(alice.id, request.id) }.code).isEqualTo("shared_owned_lists_require_confirmation")
        assertThat(f.web.me(alice.cookie).user.disabled).isFalse()
        lifecycle.authorize(f.web.reader(alice.cookie), request.copy(deleteOwnedLists = true, sharedConfirmation = "DELETE SHARED LISTS"))
        assertThat(lifecycle.close(alice.id, request.id).state).isEqualTo("closed")
    }
}
