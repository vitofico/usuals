package io.theficos.usuals.relay

import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.theficos.usuals.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

private data class AccountAccess(val user: String, val verify: (java.sql.Connection) -> Unit)

@OptIn(ExperimentalCoroutinesApi::class)
fun Application.relayModule(relay: Relay, operations: RelayMetrics = RelayMetrics(relay.db), ingress: TrustedIngress = TrustedIngress(), webIdentity: WebIdentity? = null,
    browserModels: BrowserModels = BrowserModels(), ai: AiGateway = AiGateway(relay.db, AiConfiguration())) {
    val service = MembershipService(relay)
    val connections = webIdentity?.let { Connections(relay.db, relay, it) }
    val aiSlots = Semaphore(2)
    val aiIo = Dispatchers.IO.limitedParallelism(2)
    fun ApplicationCall.accountAccess(mutation: Boolean = true): AccountAccess {
        val native = request.headers[HttpHeaders.Authorization]
        return if (native != null) {
            val token = bearer(); val binding = requireNotNull(connections)
            val user = relay.db.transaction { binding.caller(it, token) }
            AccountAccess(user) { c -> expect(binding.caller(c, token) == user, "unauthenticated", 401) }
        }
        else {
            val web = webIdentity ?: reject("unauthenticated", 401)
            if (mutation) {
                val caller = webCaller(web)
                AccountAccess(caller.user.id) { c -> web.current(c, caller); Unit }
            } else AccountAccess(web.me(webToken(web)).user.id) { }
        }
    }
    val io = Dispatchers.IO.limitedParallelism(4)
    val identityIo = Dispatchers.IO.limitedParallelism(1)
    val requests = Semaphore(8)
    val identityRequests = Semaphore(1)
    val bulkRequests = Semaphore(1)
    val sockets = SocketHub(relay, operations)
    relay.changed = sockets::changed
    install(StatusPages) {
        exception<RelayError> { call, e ->
            if (e.status == 429) call.response.headers.append(HttpHeaders.RetryAfter, "60")
            call.respondText(Wire.json.encodeToString(ErrorResponse.serializer(), e.response()), ContentType.Application.Json, HttpStatusCode.fromValue(e.status))
        }
        exception<WireTooLarge> { call, _ -> call.respondText("{\"code\":\"too_large\"}", ContentType.Application.Json, HttpStatusCode.PayloadTooLarge) }
        exception<UnsupportedWireVersion> { call, _ -> call.respondText("{\"code\":\"upgrade_required\"}", ContentType.Application.Json, HttpStatusCode.UpgradeRequired) }
        exception<IllegalArgumentException> { call, _ -> call.respondText("{\"code\":\"invalid\"}", ContentType.Application.Json, HttpStatusCode.BadRequest) }
        exception<java.nio.BufferUnderflowException> { call, _ -> call.respondText("{\"code\":\"invalid\"}", ContentType.Application.Json, HttpStatusCode.BadRequest) }
        exception<Throwable> { call, error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            // Never log exceptions carrying JDBC parameters, bearer headers or bodies.
            val invalid = error is java.sql.SQLException && error.sqlState?.startsWith("23") == true
            call.respondText(if (invalid) "{\"code\":\"invalid\"}" else "{\"code\":\"unavailable\"}", ContentType.Application.Json,
                if (invalid) HttpStatusCode.BadRequest else HttpStatusCode.ServiceUnavailable)
        }
    }
    install(WebSockets) { pingPeriod = 15.seconds; timeout = 15.seconds; maxFrameSize = 16384; masking = false }
    suspend fun RoutingContext.run(bulk: Boolean = false, identity: Boolean = false, block: suspend RoutingContext.() -> Unit) {
        val started = System.nanoTime(); var status = 200; var admitted = false; var bulkAdmitted = false
        operations.active.incrementAndGet()
        try {
            val admission = if (identity) identityRequests else requests
            expect(admission.tryAcquire().also { admitted = it }, "unavailable", 503)
            withTimeout(15000) { withContext((if (identity) identityIo else io) + relay.auth.browserContext(webIdentity?.let { call.request.cookies[it.sessionCookieName()] })) {
                if (bulk) {
                    // Authenticate routing headers before admitting or reading a potentially slow large body.
                    relay.db.transaction { c ->
                        val token = call.bearer()
                        when {
                            call.parameters["h"] != null -> relay.auth.authorize(c, token, call.routeId("h"), owner = call.request.httpMethod != HttpMethod.Get, recovery = true)
                            call.parameters["l"] != null -> relay.listAccess(c, token, call.routeId("l"), owner = call.request.httpMethod == HttpMethod.Put, recovery = call.request.httpMethod == HttpMethod.Get)
                            else -> relay.auth.checkBootstrap(c, token)
                        }
                    }
                    expect(bulkRequests.tryAcquire().also { bulkAdmitted = it }, "unavailable", 503)
                }
                block()
            } }
            status = call.response.status()?.value ?: 200
        } catch (error: Throwable) {
            status = when (error) {
                is RelayError -> error.status
                is WireTooLarge -> 413
                is UnsupportedWireVersion -> 426
                is IllegalArgumentException, is java.nio.BufferUnderflowException -> 400
                is java.sql.SQLException -> if (error.sqlState?.startsWith("23") == true) 400 else 503
                else -> 503
            }
            throw error
        } finally {
            if (bulkAdmitted) bulkRequests.release()
            if (admitted) (if (identity) identityRequests else requests).release()
            operations.active.decrementAndGet(); operations.finished(status, System.nanoTime() - started)
        }
    }
    routing {
        webAssets(browserModels)
        get("/healthz") { call.respondText("ok") }
        get("/readyz") { run { val ready = relay.db.ready(); call.respondText(if (ready) "ok" else "unavailable", status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable) } }
        get("/v1/server-info") { call.json(relay.identity.info()) }

        get("/v1/instance") { call.privateResponse(); call.json(InstanceInfo(name = "Usuals", server = relay.identity.info(), accounts = webIdentity != null, ai = webIdentity != null && ai.configuration.profiles.isNotEmpty())) }
        if (webIdentity != null && connections != null) {
            post("/v1/connect") { run { call.privateResponse(); call.json(connections.start(call.body(4096), ingress.client(call.request.local.remoteHost, call.request.headers.getAll("X-Forwarded-For").orEmpty()))) } }
            get("/api/connect/{id}") { run { call.privateResponse(); val me = webIdentity.me(call.webToken(webIdentity)); call.json(connections.view(WebCaller("", me.user, "", me.expiresAt), call.routeId("id"))) } }
            post("/api/connect/{id}/approve") { run { call.privateResponse(); connections.approve(call.webCaller(webIdentity), call.routeId("id")); call.json(ConnectResult(true)) } }
            get("/api/connections") { run { call.privateResponse(); val me = webIdentity.me(call.webToken(webIdentity)); call.json(connections.sessions(WebCaller("", me.user, "", me.expiresAt))) } }
            post("/api/connections/{id}/revoke") { run { call.privateResponse(); connections.revoke(call.webCaller(webIdentity), call.routeId("id")); call.json(ConnectResult(false)) } }
            post("/v1/connect/{id}/complete") { run { call.privateResponse(); call.json(connections.complete(call.routeId("id"), call.body(2048), ingress.client(call.request.local.remoteHost, call.request.headers.getAll("X-Forwarded-For").orEmpty()))) } }
            post("/v1/connection/logout") { run { call.privateResponse(); call.json(connections.logout(call.bearer())) } }
            post("/v1/account/households") { run {
                call.privateResponse(); val token = call.bearer()
                val user = relay.db.transaction { connections.caller(it, token) }
                val proof = webIdentity.createHouseholdFor(user, call.body(65536), relay, false) { c -> expect(connections.caller(c, token) == user, "unauthenticated", 401) }
                call.json(Membership(listOf(proof), 2), HttpStatusCode.Created)
            } }
            get("/v1/ai/settings") { run { call.privateResponse(); call.json(ai.settings(call.accountAccess(mutation = false).user)) } }
            post("/v1/ai/settings") { run { call.privateResponse(); val access = call.accountAccess(); call.json(ai.select(access.user, call.body(8192), access.verify)) } }
            for (action in listOf("probe", "probe-image", "text", "image")) post("/v1/ai/$action") {
                call.privateResponse()
                expect(aiSlots.tryAcquire(), "unavailable", 503)
                try {
                    withTimeout(40000) { withContext(aiIo) {
                        val access = call.accountAccess()
                        val request = withTimeout(5000) { call.body<AiRequest>(if (action == "image") 2900000 else 130000) }
                        val reply = try { ai.execute(access.user, action, request, access.verify) }
                        catch (failure: io.theficos.usuals.llm.ModelCallException) { AiReply(reachable = false, structured = false, problem = failure.problem.kind.name) }
                        call.json(reply)
                    } }
                } finally { aiSlots.release() }
            }
        }

        post("/v1/devices/challenge") { run { call.json(relay.auth.challenge(call.body(), ingress.client(call.request.local.remoteHost, call.request.headers.getAll("X-Forwarded-For").orEmpty()))) } }
        post("/v1/devices/session") { run {
            val webSession = if (webIdentity != null && call.request.cookies[webIdentity.sessionCookieName()] != null)
                call.webCaller(webIdentity).session else null
            call.json(relay.auth.session(call.body(), webSession))
        } }
        get("/auth/config") { run { call.privateResponse(); call.json(WebAuthConfig(webIdentity != null, if (webIdentity?.config?.oidc != null) "/auth/login" else null, webIdentity?.config?.oidc != null, webIdentity?.config?.localEnabled == true)) } }
        if (webIdentity != null) {
            get("/auth/login") { run(identity = true) {
                call.privateResponse()
                val start = webIdentity.begin(ingress.client(call.request.local.remoteHost, call.request.headers.getAll("X-Forwarded-For").orEmpty()))
                call.webCookie(webIdentity, webIdentity.loginCookieName(), start.browserToken, 300)
                call.respondRedirect(start.location)
            } }
            get("/auth/callback") { run(identity = true) {
                call.privateResponse()
                call.webCookie(webIdentity, webIdentity.loginCookieName(), "", 0)
                expect(call.request.queryParameters.names().all { it in setOf("state", "code", "iss", "error", "error_description", "error_uri", "session_state") })
                fun single(name: String) = call.request.queryParameters.getAll(name)?.singleOrNull() ?: reject("unauthenticated", 401)
                call.request.queryParameters["iss"]?.let { expect(it == webIdentity.config.oidc?.issuer, "unauthenticated", 401) }
                expect(call.request.queryParameters["error"] == null, "unauthenticated", 401)
                val complete = webIdentity.complete(single("code"), single("state"), call.request.cookies[webIdentity.loginCookieName()] ?: "",
                    ingress.client(call.request.local.remoteHost, call.request.headers.getAll("X-Forwarded-For").orEmpty()))
                call.webCookie(webIdentity, webIdentity.sessionCookieName(), complete.token, 900)
                call.respondRedirect("/")
            } }
            get("/api/me") { run { call.privateResponse(); call.json(webIdentity.me(call.webToken(webIdentity))) } }
            get("/api/sessions") { run { call.privateResponse(); call.json(webIdentity.sessions(call.webToken(webIdentity))) } }
            post("/auth/logout") { run {
                call.privateResponse(); webIdentity.logout(call.webCaller(webIdentity))
                call.webCookie(webIdentity, webIdentity.sessionCookieName(), "", 0); call.respond(HttpStatusCode.NoContent)
            } }
            post("/api/sessions/{id}/revoke") { run {
                call.privateResponse(); webIdentity.revoke(call.webCaller(webIdentity), call.routeId("id")); call.respond(HttpStatusCode.NoContent)
            } }
            post("/api/devices/link") { run {
                call.privateResponse(); webIdentity.link(call.webCaller(webIdentity), call.body(16384)); call.respond(HttpStatusCode.NoContent)
            } }
            post("/api/households") { run {
                call.privateResponse(); call.json(webIdentity.createHousehold(call.webCaller(webIdentity), call.body(65536), relay), HttpStatusCode.Created)
            } }
            get("/api/admin/users") { run { call.privateResponse(); call.json(webIdentity.users(call.webToken(webIdentity), call.request.queryParameters["after"])) } }
            post("/api/admin/users/{id}") { run {
                call.privateResponse(); call.json(webIdentity.update(call.webCaller(webIdentity), call.routeId("id"), call.body(1024)))
            } }
            post("/api/admin/users/{id}/revoke-sessions") { run {
                call.privateResponse(); webIdentity.revokeUser(call.webCaller(webIdentity), call.routeId("id")); call.respond(HttpStatusCode.NoContent)
            } }
            if (webIdentity.config.localEnabled) {
                get("/auth/local/challenge") { run(identity = true) {
                    call.privateResponse()
                    val challenge = webIdentity.localChallenge(ingress.client(call.request.local.remoteHost, call.request.headers.getAll("X-Forwarded-For").orEmpty()))
                    call.webCookie(webIdentity, webIdentity.localCookieName(), challenge.browserToken, 300)
                    call.json(challenge.result)
                } }
                post("/auth/local/login") { run(identity = true) {
                    call.privateResponse(); call.webCookie(webIdentity, webIdentity.localCookieName(), "", 0)
                    val result = webIdentity.localLogin(call.request.cookies[webIdentity.localCookieName()] ?: "", call.request.headers[HttpHeaders.Origin], call.request.headers["X-CSRF-Token"],
                        ingress.client(call.request.local.remoteHost, call.request.headers.getAll("X-Forwarded-For").orEmpty())) { call.identityBody(2048) }
                    call.webCookie(webIdentity, webIdentity.sessionCookieName(), result.token, 900); call.respond(HttpStatusCode.NoContent)
                } }
                post("/auth/local/activate") { run(identity = true) {
                    call.privateResponse(); call.webCookie(webIdentity, webIdentity.localCookieName(), "", 0)
                    val result = webIdentity.localActivate(call.request.cookies[webIdentity.localCookieName()] ?: "", call.request.headers[HttpHeaders.Origin], call.request.headers["X-CSRF-Token"],
                        ingress.client(call.request.local.remoteHost, call.request.headers.getAll("X-Forwarded-For").orEmpty())) { call.identityBody(2048) }
                    call.webCookie(webIdentity, webIdentity.sessionCookieName(), result.token, 900); call.respond(HttpStatusCode.NoContent)
                } }
                post("/auth/local/password") { run(identity = true) {
                    call.privateResponse()
                    webIdentity.localPassword(call.webCaller(webIdentity), call.body(2048), ingress.client(call.request.local.remoteHost, call.request.headers.getAll("X-Forwarded-For").orEmpty()))
                    call.webCookie(webIdentity, webIdentity.sessionCookieName(), "", 0); call.respond(HttpStatusCode.NoContent)
                } }
                post("/api/admin/local-users") { run {
                    call.privateResponse(); call.json(webIdentity.localCreate(call.webCaller(webIdentity), call.body(1024)), HttpStatusCode.Created)
                } }
                post("/api/admin/users/{id}/reset-local-password") { run {
                    call.privateResponse(); call.json(webIdentity.localReset(call.webCaller(webIdentity), call.routeId("id")))
                } }
            }
        }
        post("/v1/households") { run(bulk = true) { call.json(relay.create(call.bearer(), call.body(8 * 1024 * 1024)).response(), HttpStatusCode.Created) } }
        post("/v1/households/{h}/invites") { run { call.json(service.invite(call.bearer(), call.routeId("h"), call.body())) } }
        post("/v1/invites/{i}/open") { run { call.privateResponse(); call.json(service.open(call.routeId("i"), call.body(1024), ingress.client(call.request.local.remoteHost, call.request.headers.getAll("X-Forwarded-For").orEmpty()))) } }
        post("/v1/join-requests") { run { call.json(service.join(call.body(), ingress.client(call.request.local.remoteHost, call.request.headers.getAll("X-Forwarded-For").orEmpty())), HttpStatusCode.Accepted) } }
        get("/v1/households/{h}/join-requests") { run { call.json(service.pending(call.bearer(), call.routeId("h"))) } }
        post("/v1/households/{h}/join-requests/{r}/deny") { run { service.deny(call.bearer(), call.routeId("h"), call.routeId("r")); call.respond(HttpStatusCode.OK) } }
        post("/v1/households/{h}/join-requests/{r}/expired") { run { call.json(service.expired(call.bearer(), call.routeId("h"), call.routeId("r"), call.body(16384))) } }
        get("/v1/households/{h}/membership") { run(bulk = true) { call.json(relay.membership(call.bearer(), call.routeId("h"), call.cursor("afterEpoch"))) } }
        post("/v1/households/{h}/membership") { run(bulk = true) { call.json(service.change(call.bearer(), call.routeId("h"), call.body(8 * 1024 * 1024)).response()) } }
        get("/v1/households/{h}/lists") { run { call.json(relay.lists(call.bearer(), call.routeId("h"))) } }
        post("/v1/households/{h}/lists") { run(bulk = true) { call.json(service.change(call.bearer(), call.routeId("h"), call.body(8 * 1024 * 1024), creatingList = true).response(), HttpStatusCode.Created) } }
        get("/v1/households/{h}/key-packages") { run { call.json(relay.packages(call.bearer(), call.routeId("h"), call.queryId("listId"), call.positive("epoch"))) } }
        put("/v1/households/{h}/snapshot-candidates/{c}") { run(bulk = true) { call.json(service.stage(call.bearer(), call.routeId("h"), call.routeId("c"), call.body(10 * 1024 * 1024))) } }
        get("/v1/lists/{l}/operations") { run(bulk = true) { call.json(relay.operations(call.bearer(), call.routeId("l"), call.cursor("after"), call.request.queryParameters["limit"]?.toInt() ?: 100)) } }
        post("/v1/lists/{l}/operations") { run { call.json(relay.append(call.bearer(), call.routeId("l"), call.body())) } }
        get("/v1/lists/{l}/intents/{i}") { run { call.json(relay.intent(call.bearer(), call.routeId("l"), call.routeId("i"))) } }
        get("/v1/lists/{l}/snapshot") { run(bulk = true) { call.json(relay.snapshot(call.bearer(), call.routeId("l"))) } }
        put("/v1/lists/{l}/snapshot") { run(bulk = true) { val token = call.bearer(); val list = call.routeId("l"); val snapshot: Snapshot = call.body(10 * 1024 * 1024); relay.putSnapshot(token, list, snapshot); call.json(snapshot) } }
        webSocket("/v1/changes") {
            try { withContext(relay.auth.browserContext(webIdentity?.let { call.request.cookies[it.sessionCookieName()] })) { sockets.connect(this@webSocket, call.bearer(), io) } }
            catch (_: RelayError) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "forbidden")) }
        }
    }
}
private fun WebIdentity.sessionCookieName() = if (config.secureCookies) "__Host-usuals-session" else "usuals-session"
private fun WebIdentity.loginCookieName() = if (config.secureCookies) "__Host-usuals-login" else "usuals-login"
private fun WebIdentity.localCookieName() = if (config.secureCookies) "__Host-usuals-local-login" else "usuals-local-login"
private fun ApplicationCall.webToken(identity: WebIdentity) = request.cookies[identity.sessionCookieName()] ?: reject("unauthenticated", 401)
private fun ApplicationCall.webCaller(identity: WebIdentity) = identity.csrf(webToken(identity), request.headers[HttpHeaders.Origin], request.headers["X-CSRF-Token"])
private fun ApplicationCall.webCookie(identity: WebIdentity, name: String, value: String, maxAge: Int) {
    response.cookies.append(Cookie(name, value, path = "/", maxAge = maxAge, secure = identity.config.secureCookies, httpOnly = true, extensions = mapOf("SameSite" to "Lax")))
}
private fun ApplicationCall.privateResponse() {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append("Pragma", "no-cache")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("X-Content-Type-Options", "nosniff")
}
private suspend inline fun <reified T> ApplicationCall.identityBody(max: Int): T =
    try { withTimeout(3000) { body<T>(max) } } catch (_: TimeoutCancellationException) { reject("unavailable", 503) }
private suspend inline fun <reified T> ApplicationCall.body(max: Int = 1024 * 1024): T {
    expect(request.headers[HttpHeaders.ContentEncoding].let { it == null || it == "identity" })
    request.headers[HttpHeaders.ContentLength]?.let { expect(it.toLong() in 0..max.toLong(), "too_large", 413) }
    expect(request.contentType().match(ContentType.Application.Json))
    val channel = receiveChannel(); val buffer = ByteArray(8192); val output = ByteArrayOutputStream()
    while (true) {
        val n = channel.readAvailable(buffer, 0, minOf(buffer.size, max - output.size() + 1))
        if (n < 0) break
        output.write(buffer, 0, n); expect(output.size() <= max, "too_large", 413)
    }
    return Wire.json.decodeFromJsonElement(Wire.strictJson(output.toByteArray(), max))
}
private suspend inline fun <reified T> ApplicationCall.json(value: T, status: HttpStatusCode = HttpStatusCode.OK) = respondText(Wire.json.encodeToString(kotlinx.serialization.serializer<T>(), value), ContentType.Application.Json, status)
private fun ApplicationCall.bearer(): String {
    val header = request.headers[HttpHeaders.Authorization] ?: reject("unauthenticated", 401)
    expect(header.startsWith("Bearer ") && header.length == 50, "unauthenticated", 401)
    return header.substring(7)
}
private fun ApplicationCall.routeId(name: String) = parameters[name]?.also { id(it) } ?: reject()
private fun ApplicationCall.queryId(name: String) = request.queryParameters[name]?.also { id(it) } ?: reject()
private fun ApplicationCall.positive(name: String) = (request.queryParameters[name] ?: reject()).toLong().also { expect(it > 0) }
private fun ApplicationCall.cursor(name: String) = (request.queryParameters[name] ?: "0").toLong().also { expect(it >= 0) }

private class SocketHub(private val relay: Relay, private val operations: RelayMetrics) {
    private data class Socket(val device: String, val households: Set<String>, val hint: Channel<String>)
    private val handshakes = Semaphore(32)
    private val sockets = ConcurrentHashMap<DefaultWebSocketServerSession, Socket>()
    fun changed(household: String) { sockets.values.filter { household in it.households }.forEach { it.hint.trySend(household) } }
    suspend fun connect(session: DefaultWebSocketServerSession, token: String, io: CoroutineDispatcher) {
        // Explicit subscriptions contain routing ids only. There is no URL token.
        expect(handshakes.tryAcquire(), "rate_limited", 429)
        val subscriptions = try {
            withContext(io) { relay.db.transaction { c -> relay.auth.caller(c, token) } }
            val frame = withTimeout(10000) { session.incoming.receive() } as? Frame.Text ?: reject()
            val element = Wire.strictJson(frame.readBytes(), 16384)
            val households = Wire.json.decodeFromJsonElement<List<String>>(element).toSortedSet()
            expect(households.size in 1..20)
            val device = withContext(io) { relay.db.transaction { c -> households.map { relay.auth.authorize(c, token, it).first.device }.distinct().single() } }
            households to device
        } finally { handshakes.release() }
        val (households, device) = subscriptions
        synchronized(sockets) {
            expect(sockets.size < 320 && sockets.values.count { it.device == device } < 2, "rate_limited", 429)
            sockets[session] = Socket(device, households, Channel(Channel.CONFLATED))
            operations.sockets.incrementAndGet()
        }
        try {
            households.forEach { session.send(Frame.Text("{\"householdId\":\"$it\"}")) }
            coroutineScope {
                val reader = launch { for (ignored in session.incoming) expect(ignored is Frame.Ping || ignored is Frame.Pong) }
                try {
                    while (reader.isActive) {
                        val changed = withTimeoutOrNull(1000) { sockets[session]?.hint?.receive() }
                        // Also recheck expiry and revocation without requiring any list edit.
                        withContext(io) { relay.db.transaction { c -> households.forEach { relay.auth.authorize(c, token, it) } } }
                        if (changed != null) session.send(Frame.Text("{\"householdId\":\"$changed\"}"))
                    }
                } finally { reader.cancel() }
            }
        } catch (_: RelayError) {
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "forbidden"))
        } finally { sockets.remove(session)?.let { it.hint.close(); operations.sockets.decrementAndGet() } }
    }
}

private fun ManifestProof.response() = Membership(listOf(this), signed().body().epoch)
