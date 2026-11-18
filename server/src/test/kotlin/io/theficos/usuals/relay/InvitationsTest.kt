package io.theficos.usuals.relay

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.theficos.usuals.sync.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InvitationsTest {
    private val database = TestDatabase()
    private val db = database.db
    private var now = 1_800_000_000_000L
    private lateinit var f: RelayFixture
    private lateinit var web: WebIdentity
    private lateinit var lists: Lists
    private lateinit var invitations: Invitations
    private lateinit var configuration: WebIdentityConfig
    private val provider = OidcFixture { now }
    private val sent = mutableListOf<InvitationMail>()
    private var mailUnavailable = false
    private val mail = object : InvitationMailer { override fun send(message: InvitationMail) { if (mailUnavailable) error("synthetic_failure"); sent += message } }
    private val limits = MailConfiguration("mailpit", 1025, "usuals@example.test", startTls = false, local = true)
    @Before fun before() {
        db.migrate(); val identity = Identity.generate(); db.fence(identity.serverId, "original")
        f = RelayFixture(db, identity) { now }; configure()
    }
    private fun configure(invitationOnly: Boolean = false) {
        val oidc = OidcConfig(provider.issuer, "usuals-web", "synthetic-client-secret", f.origin, setOf("admin-subject"), registrationEnabled = true)
        configuration = WebIdentityConfig(f.origin, oidc, false, invitationOnly, "collaborators")
        invitations = Invitations(f.relay, configuration, mail, limits) { now }
        web = WebIdentity(configuration, db, f.auth, OidcClient(oidc, provider.transport) { now }, { now }, invitations)
        lists = Lists(f.relay, configuration)
    }
    @After fun after() { invitations.close(); database.close() }
    private fun login(email: String, subject: String = email, context: String? = null, register: Boolean = false): WebCaller {
        provider.subject = subject; provider.email = email
        val start = web.begin("login-$subject", register = register, invitationCookie = context)
        val request = provider.authorize(start)
        return web.reader(web.complete("synthetic-code", request.getValue("state"), start.browserToken, "login-$subject").token)
    }
    private fun access(caller: WebCaller) = AccountAccess(caller.user.id) { web.current(it, caller) }
    private fun create(caller: WebCaller, name: String = "Groceries") = lists.create(access(caller), uuid(), CreateList(Projection(name = name))).first
    private fun invite(caller: WebCaller, list: String?, email: String) = invitations.send(caller, list, SendInvitation(email, "it")) { web.current(it, caller) }
    private fun context(token: String = sent.last().link.substringAfter('#')): String {
        val start = invitations.challenge("context-${uuid()}")
        invitations.attach(start.token, f.origin, start.result.csrfToken, InvitationSecret(token))
        return start.token
    }
    private fun preview(cookie: String?, who: WebCaller?) = invitations.preview(cookie, who) { c -> who?.let { web.current(c, it) } }
    private fun accept(cookie: String?, who: WebCaller) = invitations.accept(cookie, who, lists) { web.current(it, who) }
    private fun denied(code: String, action: () -> Any?) { assertThat(assertThrows(RelayError::class.java) { action() }.code).isEqualTo(code) }

    @Test fun newRecipientJoinsOnlyInvitedListAndReplayCannotRestoreRemovedMembership() {
        val alice = login("alice@example.test"); val shared = create(alice); create(alice, "Private")
        val invitation = invite(alice, shared.id, "Bob@Example.test")
        assertThat(invitation.delivery).isEqualTo("queued")
        assertThat(invitations.deliverOne()).isTrue()
        assertThat(sent.single().to).isEqualTo("bob@example.test")
        assertThat(sent.single().locale).isEqualTo("it")
        val cookie = context()
        assertThat(preview(cookie, null)).isEqualTo(InvitationPreview("signin"))
        val bob = login("bob@example.test", context = cookie, register = true)
        db.transaction { it.execute("UPDATE web_user SET display_name='' WHERE id IN (?,?)", id(alice.user.id), id(bob.user.id)) }
        assertThat(preview(cookie, bob).state).isEqualTo("ready")
        assertThat(lists.changes(access(bob), null, 100).lists).isEmpty()
        val accepted = accept(cookie, bob).list!!
        assertThat(accepted.id).isEqualTo(shared.id); assertThat(accepted.role).isEqualTo("editor")
        assertThat(accepted.members.map { it.name }).containsExactly("alice@example.test", "bob@example.test")
        assertThat(lists.changes(access(bob), null, 100).lists.map { it.id }).containsExactly(shared.id)
        assertThat(accept(cookie, bob).list!!.seq).isEqualTo(accepted.seq)
        lists.remove(access(alice), shared.id, bob.user.id)
        denied("invitation_unavailable") { accept(cookie, bob) }
        assertThat(lists.changes(access(bob), null, 100).lists).isEmpty()
    }
    @Test fun recipientProofBelongsToTheSessionAndRequiresRecentAuthentication() {
        val alice = login("alice@example.test"); val shared = create(alice)
        invite(alice, shared.id, "bob@example.test"); invitations.deliverOne(); val cookie = context()
        val eve = login("eve@example.test")
        db.transaction { it.execute("UPDATE web_user SET email='bob@example.test' WHERE id=?", id(eve.user.id)) }
        val readAgain = web.reader(db.transaction { c ->
            // A separate signed-in session must not borrow another session's verified email or auth_time.
            val token = randomToken()
            c.execute("INSERT INTO web_session VALUES (?,?,?,?,?,?,false,?,?)", id(uuid()), digest(token), id(eve.user.id), randomToken(), now, now + 1000000, now, "eve@example.test")
            token
        })
        assertThat(preview(cookie, readAgain).state).isEqualTo("wrong_account")
        denied("invitation_wrong_account") { accept(cookie, readAgain) }
        provider.authenticationTime = now / 1000 - 600
        val stale = login("bob@example.test")
        assertThat(preview(cookie, stale).state).isEqualTo("reauthenticate")
        denied("reauthentication_required") { accept(cookie, stale) }
        denied("reauthentication_required") { login("bob@example.test", context = cookie) }
        provider.authenticationTime = null
        val fresh = login("bob@example.test", context = cookie)
        assertThat(accept(cookie, fresh).list!!.id).isEqualTo(shared.id)
    }
    @Test fun resendRotatesTheSecretAndCancelExpiryAndSenderRemovalInvalidateAccess() {
        val alice = login("alice@example.test"); val shared = create(alice)
        val invitation = invite(alice, shared.id, "bob@example.test"); invitations.deliverOne()
        val oldToken = sent.last().link.substringAfter('#'); val oldCookie = context()
        denied("invitation_resend_later") { invitations.change(alice, shared.id, invitation.id, true) { web.current(it, alice) } }
        now += 60001
        invitations.change(alice, shared.id, invitation.id, true) { web.current(it, alice) }; invitations.deliverOne()
        assertThat(sent.last().locale).isEqualTo("it")
        assertThat(preview(oldCookie, null).state).isEqualTo("unavailable")
        denied("invitation_unavailable") { context(oldToken) }
        val cookie = context(); val bob = login("bob@example.test")
        invitations.change(alice, shared.id, invitation.id, false) { web.current(it, alice) }
        denied("invitation_unavailable") { accept(cookie, bob) }
        now += 60001
        invite(alice, shared.id, "bob@example.test"); invitations.deliverOne(); val expiring = context()
        now += 7L * 86400000
        assertThat(preview(expiring, bob).state).isEqualTo("unavailable")
        now += 60001
        val currentAlice = login("alice@example.test"); val editor = login("editor@example.test")
        // Establish the editor through an invitation, never through directory UUID guessing.
        invite(currentAlice, shared.id, "editor@example.test"); invitations.deliverOne(); accept(context(), editor)
        invite(editor, shared.id, "new@example.test"); invitations.deliverOne(); val removed = context()
        lists.remove(access(currentAlice), shared.id, editor.user.id)
        assertThat(preview(removed, null).state).isEqualTo("unavailable")
    }
    @Test fun outboxSurvivesRetryAndKeyRotationWithoutKeepingDeliveredSecrets() {
        val alice = login("alice@example.test"); val shared = create(alice)
        val invitation = invite(alice, shared.id, "bob@example.test")
        mailUnavailable = true; assertThat(invitations.deliverOne()).isTrue(); assertThat(sent).isEmpty()
        val next = ListCipher(ByteArray(32) { 9 })
        f.relay.cipher.rewrap(db, next); next.check(db)
        assertThrows(IllegalStateException::class.java) { f.relay.cipher.check(db) }
        val restarted = Invitations(Relay(db, f.identity, f.auth, next), configuration, mail, limits) { now }
        mailUnavailable = false; now += 30000
        assertThat(restarted.deliverOne()).isTrue(); assertThat(sent).hasSize(1)
        assertThat(restarted.deliverOne()).isFalse()
        assertThat(db.transaction { c -> c.query("SELECT mail_cipher IS NULL,delivery FROM email_invitation WHERE id=?", id(invitation.id)) { it.getBoolean(1) to it.getString(2) }.single() }).isEqualTo(true to "sent")
        restarted.close()
    }
    @Test fun unrelatedAccountsCannotEnumerateOrAddPeopleByUuid() {
        val alice = login("alice@example.test"); val bob = login("bob@example.test"); val eve = login("eve@example.test")
        val shared = create(alice)
        assertThat(lists.people(access(alice), "").people).isEmpty()
        denied("not_found") { lists.add(access(alice), shared.id, AddMember(bob.user.id)) }
        invite(alice, shared.id, "bob@example.test"); invitations.deliverOne(); accept(context(), bob)
        assertThat(lists.people(access(alice), "").people.map { it.id }).containsExactly(bob.user.id)
        assertThat(lists.people(access(eve), "bob").people).isEmpty()
    }
    @Test fun invitationOnlyAdmissionChecksCallbackIncludingSocialOrDirectProviderRegistration() {
        configure(invitationOnly = true)
        denied("invitation_required") { login("unknown@example.test") }
        denied("invitation_required") { login("unknown@example.test", register = true) }
        assertThat(db.transaction { it.query("SELECT count(*) FROM web_user") { row -> row.getLong(1) }.single() }).isEqualTo(0)
        val admin = login("admin@example.test", "admin-subject")
        invite(admin, null, "alice@example.test"); invitations.deliverOne(); val cookie = context()
        provider.verifiedEmail = false
        denied("invitation_required") { login("alice@example.test", context = cookie, register = true) }
        provider.verifiedEmail = true
        val alice = login("alice@example.test", context = cookie, register = true)
        assertThat(alice.user.admin).isFalse(); assertThat(accept(cookie, alice).list).isNull()
        assertThat(login("alice@example.test").user.id).isEqualTo(alice.user.id)
        val shared = create(alice)
        invite(alice, shared.id, "bob@example.test"); invitations.deliverOne()
        val bobContext = context(); val bob = login("bob@example.test", context = bobContext, register = true)
        assertThat(accept(bobContext, bob).list!!.id).isEqualTo(shared.id)
    }
    @Test fun parallelAcceptanceAddsOneMembershipAndOneFeedTransition() {
        val alice = login("alice@example.test"); val bob = login("bob@example.test"); val shared = create(alice)
        invite(alice, shared.id, "bob@example.test"); invitations.deliverOne(); val cookie = context()
        val executor = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val calls = (1..2).map { executor.submit<Long> { start.await(); accept(cookie, bob).list!!.seq } }
            start.countDown()
            val sequences = calls.map { it.get(5, TimeUnit.SECONDS) }
            assertThat(sequences.distinct()).hasSize(1)
            assertThat(db.transaction { c -> c.query("SELECT count(*) FROM list_member WHERE list_id=? AND user_id=? AND removed IS NULL", id(shared.id), id(bob.user.id)) { it.getLong(1) }.single() }).isEqualTo(1)
        } finally { executor.shutdownNow() }
    }
    @Test fun restoreInvalidatesPendingLinksMailAndContinuation() {
        val alice = login("alice@example.test"); val shared = create(alice)
        invite(alice, shared.id, "bob@example.test"); invitations.deliverOne(); val cookie = context()
        now += 60001; invite(alice, shared.id, "carol@example.test")
        db.fence(f.identity.serverId, "restored")
        assertThat(invitations.deliverOne()).isFalse()
        assertThat(preview(cookie, null).state).isEqualTo("unavailable")
        denied("invitation_unavailable") { context(sent.first().link.substringAfter('#')) }
    }
    @Test fun httpKeepsGetHarmlessAndProtectsMutationsWithBrowserCsrf() = testApplication {
        val alice = login("alice@example.test"); val shared = create(alice)
        application { relayModule(f.relay, webIdentity = web) }
        val configuration = client.get("/auth/config").bodyAsText()
        assertThat(configuration).contains("\"registrationPath\":\"/auth/register\"")
        val createPath = "/api/lists/${shared.id}/invitations"
        val rejected = client.post(createPath) { cookie("__Host-usuals-session", sessionToken(alice)); contentType(ContentType.Application.Json); setBody("{\"email\":\"bob@example.test\"}") }
        assertThat(rejected.status).isEqualTo(HttpStatusCode.Forbidden)
        val challenge = client.get("/api/invitations/challenge")
        assertThat(challenge.headers[HttpHeaders.CacheControl]).isEqualTo("no-store")
        assertThat(client.get("/api/invitations/accept").status).isEqualTo(HttpStatusCode.MethodNotAllowed)
        val response = client.post(createPath) {
            cookie("__Host-usuals-session", sessionToken(alice)); header(HttpHeaders.Origin, f.origin); header("X-CSRF-Token", alice.csrf)
            contentType(ContentType.Application.Json); setBody("{\"email\":\"bob@example.test\"}")
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.Created)
        assertThat(response.bodyAsText()).doesNotContain("token")
        assertThat(invitations.deliverOne()).isTrue()
    }
    private fun sessionToken(caller: WebCaller): String {
        // Test-only credential with the same session proof, avoiding any production token introspection API.
        val token = randomToken()
        db.transaction { it.execute("UPDATE web_session SET token_hash=? WHERE id=?", digest(token), id(caller.session)) }
        return token
    }
}
