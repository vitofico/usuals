package io.theficos.usuals.relay

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.theficos.usuals.sync.CreateList
import io.theficos.usuals.sync.Projection
import io.theficos.usuals.sync.Wire
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AccountLimitsTest {
    private val database = TestDatabase()
    private val db = database.db
    private lateinit var f: RelayFixture
    private lateinit var alice: Account
    private lateinit var bob: Account
    @Before fun before() {
        db.migrate(); f = RelayFixture(db)
        alice = f.account("Alice"); bob = f.account("Bob")
    }
    @After fun after() = database.close()
    private fun access(account: Account): AccountAccess {
        val caller = f.web.reader(account.cookie)
        return AccountAccess(account.id) { c -> f.web.current(c, caller) }
    }
    private fun config(values: Map<String, AccountLimit>) = WebIdentityConfig(f.origin, null, true, accountLimits = AccountLimits(values))
    private fun create(lists: Lists, account: Account, list: String = uuid()) = lists.create(access(account), list, CreateList(Projection(name = "Synthetic")))

    @Test fun optionalPolicyIsStrictAndHasNoCommercialDefault() {
        val absent = AccountLimits.fromEnvironment(emptyMap())
        assertThat(absent.ownedLists(alice.id)).isEqualTo(500)
        assertThat(absent.members(alice.id)).isEqualTo(50)
        assertThat(db.transaction { absent.view(it, alice.id) }).isNull()
        for (text in listOf("[]", "null", "{\"bad\":{\"maxOwnedLists\":1}}", "{\"${alice.id}\":{}}",
            "{\"${alice.id}\":{\"maxOwnedLists\":-1}}", "{\"${alice.id}\":{\"maxListMembers\":0}}",
            "{\"${alice.id}\":{\"maxOwnedLists\":501}}", "{\"${alice.id}\":{\"maxOwnedLists\":1,\"plan\":\"paid\"}}"))
            assertThrows(IllegalArgumentException::class.java) { AccountLimits.fromEnvironment(mapOf("USUALS_ACCOUNT_LIMITS" to text)) }
        val configured = AccountLimits.fromEnvironment(mapOf("USUALS_ACCOUNT_LIMITS" to "{\"${alice.id}\":{\"maxOwnedLists\":0,\"maxListMembers\":2}}"))
        assertThat(configured.ownedLists(alice.id)).isEqualTo(0)
        assertThat(configured.ownedLists(bob.id)).isEqualTo(500)
        val defaults = AccountLimits.fromEnvironment(mapOf("USUALS_DEFAULT_ACCOUNT_LIMITS" to """{"maxOwnedLists":1,"maxListMembers":2,"monthlyAiRequests":2}"""))
        assertThat(defaults.ownedLists(bob.id)).isEqualTo(1)
        assertThat(db.transaction { defaults.view(it, bob.id) }?.monthlyAiRequests).isEqualTo(2)
        val limited = Lists(f.relay, WebIdentityConfig(f.origin, null, true, accountLimits = defaults))
        create(limited, bob)
        assertThat(assertThrows(RelayError::class.java) { create(limited, bob) }.code).isEqualTo("limit_reached")
    }

    @Test fun creationCountsArchivedListsAllowsReplayAndFreesCapacityOnlyOnDeletion() {
        val configured = config(mapOf(alice.id to AccountLimit(maxOwnedLists = 1)))
        val lists = Lists(f.relay, configured)
        val list = uuid()
        assertThat(create(lists, alice, list).second).isTrue()
        assertThat(create(lists, alice, list).second).isFalse()
        lists.archive(access(alice), list, io.theficos.usuals.sync.ArchiveList(true))
        assertThat(assertThrows(RelayError::class.java) { create(lists, alice) }.code).isEqualTo("limit_reached")
        val web = WebIdentity(configured, db, f.auth)
        assertThat(web.me(alice.cookie).limits).isEqualTo(AccountLimitView(maxOwnedLists = 1, ownedLists = 1))
        assertThat(web.me(bob.cookie).limits).isNull()
        create(lists, bob)
        lists.delete(access(alice), list)
        create(lists, alice)
        assertThat(web.me(alice.cookie).limits?.ownedLists).isEqualTo(1)
    }

    @Test fun concurrentCreatesCannotOversubscribeTheAccount() {
        val lists = Lists(f.relay, config(mapOf(alice.id to AccountLimit(maxOwnedLists = 1))))
        val account = access(alice)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = (1..2).map { pool.submit(Callable {
                start.await()
                try { lists.create(account, uuid(), CreateList(Projection(name = "Synthetic"))); "created" }
                catch (error: RelayError) { error.code }
            }) }
            start.countDown()
            assertThat(jobs.map { it.get(10, TimeUnit.SECONDS) }).containsExactly("created", "limit_reached")
            assertThat(db.transaction { c -> c.query("SELECT count(*) FROM list WHERE owner=?", id(alice.id)) { it.getInt(1) }.single() }).isEqualTo(1)
        } finally { pool.shutdownNow() }
    }

    @Test fun everyMembershipPathUsesTheListOwnersLimitIncludingInvitedMembersAndEditors() {
        val carol = f.account("Carol")
        val lists = Lists(f.relay, config(mapOf(alice.id to AccountLimit(maxListMembers = 2), bob.id to AccountLimit(maxListMembers = 1))))
        val first = create(lists, alice).first.id
        lists.add(access(alice), first, io.theficos.usuals.sync.AddMember(bob.id))
        lists.add(access(bob), first, io.theficos.usuals.sync.AddMember(bob.id))
        assertThat(assertThrows(RelayError::class.java) { lists.add(access(bob), first, io.theficos.usuals.sync.AddMember(carol.id)) }.code).isEqualTo("limit_reached")
        assertThat(assertThrows(RelayError::class.java) { db.transaction { lists.addInvited(it, alice.id, first, carol.id) } }.code).isEqualTo("limit_reached")
        lists.remove(access(bob), first, bob.id)
        db.transaction { lists.addInvited(it, alice.id, first, carol.id) }
        val second = create(lists, bob).first.id
        assertThat(assertThrows(RelayError::class.java) { lists.add(access(bob), second, io.theficos.usuals.sync.AddMember(alice.id)) }.code).isEqualTo("limit_reached")
    }

    @Test fun authenticatedPolicyHttpUpdatesAreDurableAndAffectTheActualListWriter() = testApplication {
        val configured = config(emptyMap())
        val key = ByteArray(32) { 9 }
        val expiry = System.currentTimeMillis() + 240000
        application { relayModule(f.relay, webIdentity = f.web, accountPolicy = AccountPolicy(db, configured.accountLimits, key)) }
        val path = "/api/service/accounts/${alice.id}/limits"
        suspend fun policy(token: String?, cookie: Boolean = false) = client.put(path) {
            token?.let { bearerAuth(it) }
            if (cookie) header(HttpHeaders.Cookie, "__Host-usuals-session=${alice.cookie}")
            contentType(ContentType.Application.Json); setBody("""{"limits":{"maxOwnedLists":1,"maxListMembers":2},"fallback":{"maxOwnedLists":0,"maxListMembers":1},"expiresAt":$expiry}""")
        }
        assertThat(policy(null).status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(policy("wrong").status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(policy(Wire.encode(key), cookie = true).status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(policy(Wire.encode(key)).status).isEqualTo(HttpStatusCode.OK)
        assertThat(policy(Wire.encode(key)).status).isEqualTo(HttpStatusCode.OK)
        val lists = Lists(f.relay, configured)
        val list = create(lists, alice).first.id
        assertThat(assertThrows(RelayError::class.java) { create(lists, alice) }.code).isEqualTo("limit_reached")
        assertThat(client.put(path) {
            bearerAuth(Wire.encode(key)); contentType(ContentType.Application.Json)
            setBody("""{"limits":{"maxOwnedLists":2,"maxListMembers":1},"fallback":{"maxOwnedLists":0,"maxListMembers":1},"expiresAt":$expiry}""")
        }.status).isEqualTo(HttpStatusCode.OK)
        create(Lists(f.relay, config(emptyMap())), alice)
        assertThat(assertThrows(RelayError::class.java) { lists.add(access(alice), list, io.theficos.usuals.sync.AddMember(bob.id)) }.code).isEqualTo("limit_reached")
        assertThat(f.web.me(alice.cookie).limits).isEqualTo(AccountLimitView(2, 1, 2, expiry))
        create(lists, bob)
    }

    @Test fun expiredDynamicPolicyUsesItsExplicitFallbackWithoutAWorkerOrProvider() {
        var now = 1_800_000_000_000L
        val limits = AccountLimits(mapOf(bob.id to AccountLimit(maxOwnedLists = 2))) { now }
        val configured = WebIdentityConfig(f.origin, null, true, accountLimits = limits)
        val lists = Lists(f.relay, configured)
        val policy = AccountPolicy(db, limits, ByteArray(32)) { now }
        val expiry = now + 300000
        val update = AccountPolicyUpdate(AccountLimit(3, 3), AccountLimit(1, 1), expiry)
        policy.put(alice.id, update)
        val first = create(lists, alice).first.id
        lists.add(access(alice), first, io.theficos.usuals.sync.AddMember(bob.id))
        create(lists, alice)
        now = expiry - 1
        assertThat(db.transaction { limits.view(it, alice.id) }).isEqualTo(AccountLimitView(3, 3, 2, expiry))
        now = expiry
        assertThat(db.transaction { limits.view(it, alice.id) }).isEqualTo(AccountLimitView(1, 1, 2, expiry))
        assertThat(assertThrows(RelayError::class.java) { create(lists, alice) }.code).isEqualTo("limit_reached")
        now++
        assertThat(assertThrows(RelayError::class.java) { lists.add(access(alice), first, io.theficos.usuals.sync.AddMember(f.account("Carol").id)) }.code).isEqualTo("limit_reached")
        // Existing members, list retries and their data survive the fallback; static operator policy is independent.
        assertThat(create(lists, alice, first).second).isFalse()
        assertThat(db.transaction { limits.view(it, bob.id) }).isEqualTo(AccountLimitView(maxOwnedLists = 2, ownedLists = 0))
        assertThat(assertThrows(RelayError::class.java) { policy.put(alice.id, update) }.code).isEqualTo("invalid_policy_expiry")
        assertThat(assertThrows(RelayError::class.java) { policy.put(alice.id, update.copy(expiresAt = now + 300001)) }.code).isEqualTo("invalid_policy_expiry")
        policy.put(alice.id, update.copy(expiresAt = now + 300000))
        create(lists, alice)
    }
}
