package io.theficos.usuals.relay

import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.util.AttributeKey
import io.theficos.usuals.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.ByteArrayOutputStream

/** Time a request spent waiting for a list change, left out of its measured duration. */
private val idle = AttributeKey<Long>("idle")

@OptIn(ExperimentalCoroutinesApi::class)
fun Application.relayModule(relay: Relay, operations: RelayMetrics = RelayMetrics(relay.db), ingress: TrustedIngress = TrustedIngress(), webIdentity: WebIdentity? = null,
    browserModels: BrowserModels = BrowserModels(), ai: AiGateway = AiGateway(relay.db, AiConfiguration())) {
    val connections = webIdentity?.let { Connections(relay.db, relay, it) }
    val aiSlots = Semaphore(2)
    val aiIo = Dispatchers.IO.limitedParallelism(2)
    /** A phone's bearer or a browser's cookie; browser mutations also need the CSRF token. */
    fun ApplicationCall.accountAccess(mutation: Boolean = true): AccountAccess {
        if (request.headers[HttpHeaders.Authorization] != null) {
            val token = bearer(); val binding = requireNotNull(connections)
            val user = relay.db.transaction { binding.caller(it, token) }
            return AccountAccess(user) { c -> expect(binding.caller(c, token) == user, "unauthenticated", 401) }
        }
        val web = webIdentity ?: reject("unauthenticated", 401)
        val caller = if (mutation) webCaller(web) else reader(web)
        return AccountAccess(caller.user.id) { c -> web.current(c, caller) }
    }
    val io = Dispatchers.IO.limitedParallelism(4)
    val identityIo = Dispatchers.IO.limitedParallelism(1)
    val requests = Semaphore(8)
    val identityRequests = Semaphore(1)
    // A stored list is up to 7 MB encoded and several times that decoded, so at most two requests hold one.
    val listWork = Semaphore(2)
    install(StatusPages) {
        exception<RelayError> { call, e ->
            if (e.status == 429) call.response.headers.append(HttpHeaders.RetryAfter, "60")
            call.respondText(Wire.json.encodeToString(ErrorResponse.serializer(), e.response()), ContentType.Application.Json, HttpStatusCode.fromValue(e.status))
        }
        exception<WireTooLarge> { call, _ -> call.respondText("{\"code\":\"too_large\"}", ContentType.Application.Json, HttpStatusCode.PayloadTooLarge) }
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
    /** One of the request slots, the 15 s limit and the database dispatcher. */
    suspend fun <T> admit(identity: Boolean = false, block: suspend () -> T): T {
        val admission = if (identity) identityRequests else requests
        expect(admission.tryAcquire(), "unavailable", 503)
        try { return withTimeout(15000) { withContext(if (identity) identityIo else io) { block() } } } finally { admission.release() }
    }
    suspend fun RoutingContext.run(identity: Boolean = false, admitted: Boolean = true, block: suspend RoutingContext.() -> Unit) {
        val started = System.nanoTime(); var status = 200
        operations.active.incrementAndGet()
        try {
            if (admitted) admit(identity) { block() } else block()
            status = call.response.status()?.value ?: 200
        } catch (error: Throwable) {
            status = when (error) {
                is RelayError -> error.status
                is WireTooLarge -> 413
                is IllegalArgumentException, is java.nio.BufferUnderflowException -> 400
                is java.sql.SQLException -> if (error.sqlState?.startsWith("23") == true) 400 else 503
                else -> 503
            }
            throw error
        } finally {
            operations.active.decrementAndGet(); operations.finished(status, System.nanoTime() - started - (call.attributes.getOrNull(idle) ?: 0))
        }
    }
    /** Builds and encodes a list response under [listWork]; the bytes are written after it is released. */
    suspend fun <T> ApplicationCall.listed(serializer: KSerializer<T>, status: () -> HttpStatusCode = { HttpStatusCode.OK }, block: suspend () -> T) {
        val bytes = listWork.withPermit { Wire.json.encodeToString(serializer, block()).toByteArray() }
        respondBytes(bytes, ContentType.Application.Json, status())
    }
    routing {
        webAssets(browserModels)
        get("/healthz") { call.respondText("ok") }
        get("/readyz") { run { val ready = relay.db.ready(); call.respondText(if (ready) "ok" else "unavailable", status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable) } }
        get("/v1/server-info") { call.json(relay.identity.info()) }

        get("/v1/instance") { call.privateResponse(); call.json(InstanceInfo(name = "Usuals", server = relay.identity.info(), accounts = webIdentity != null, ai = webIdentity != null && ai.configuration.profiles.isNotEmpty())) }
        if (webIdentity != null && connections != null) {
            post("/v1/connect") { run { call.privateResponse(); call.json(connections.start(call.body(4096), ingress.client(call.request.local.remoteHost, call.request.headers.getAll("X-Forwarded-For").orEmpty()))) } }
            post("/api/connect/offers") { run { call.privateResponse(); call.json(connections.offer(call.webCaller(webIdentity), call.body(1024))) } }
            get("/api/connect/offers/{id}") { run { call.privateResponse(); call.json(connections.offerView(call.reader(webIdentity), call.routeId("id"))) } }
            get("/api/connect/{id}") { run { call.privateResponse(); call.json(connections.view(call.reader(webIdentity), call.routeId("id"))) } }
            post("/api/connect/{id}/approve") { run { call.privateResponse(); connections.approve(call.webCaller(webIdentity), call.routeId("id")); call.json(ConnectResult(true)) } }
            get("/api/connections") { run { call.privateResponse(); call.json(connections.sessions(call.reader(webIdentity))) } }
            post("/api/connections/{id}/revoke") { run { call.privateResponse(); connections.revoke(call.webCaller(webIdentity), call.routeId("id")); call.json(ConnectResult(false)) } }
            post("/v1/connect/{id}/complete") { run { call.privateResponse(); call.json(connections.complete(call.routeId("id"), call.body(2048), ingress.client(call.request.local.remoteHost, call.request.headers.getAll("X-Forwarded-For").orEmpty()))) } }
            post("/v1/connection/logout") { run { call.privateResponse(); call.json(connections.logout(call.bearer())) } }
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
            val lists = Lists(relay, webIdentity.config)
            route("/api/lists") {
                install(Compression) { mode = CompressionConfig.Mode.CompressResponse; gzip { minimumSize(1024) } }
                get("changes") { run(admitted = false) {
                    call.privateResponse()
                    val cursor = call.request.queryParameters["cursor"]
                    val limit = (call.request.queryParameters["limit"] ?: "100").toInt()
                    val wait = (call.request.queryParameters["wait"] ?: "0").toInt().also { expect(it in 0..25) }
                    val access = admit { call.accountAccess(mutation = false) }
                    // Registered before the first read, so a change committed in between still wakes this request.
                    val waiter = if (wait > 0) lists.waiters.register(access.user) else null
                    try {
                        suspend fun answer(always: Boolean) = admit {
                            val bytes = listWork.withPermit {
                                lists.changes(access, cursor, limit).takeIf { always || it.reset || it.lists.isNotEmpty() || it.removed.isNotEmpty() }
                                    ?.let { Wire.json.encodeToString(ListPage.serializer(), it).toByteArray() }
                            }
                            bytes?.let { call.respondBytes(it, ContentType.Application.Json) } != null
                        }
                        // Waiting holds no request slot and runs outside the 15 s limit.
                        if (!answer(waiter == null)) {
                            val started = System.nanoTime()
                            operations.waiting.incrementAndGet()
                            try { withTimeoutOrNull(wait * 1000L) { requireNotNull(waiter).await() } }
                            finally {
                                operations.waiting.decrementAndGet()
                                call.attributes.put(idle, System.nanoTime() - started)
                            }
                            answer(true)
                        }
                    } finally { if (waiter != null) lists.waiters.release(access.user, waiter) }
                } }
                // Uploads are read before taking a list-work permit, so a slow sender cannot hold one.
                put("{id}") { run {
                    call.privateResponse(); val access = call.accountAccess(); val list = call.routeId("id"); var created = false
                    val body = call.bytes(8 * 1024 * 1024)
                    call.listed(ListView.serializer(), { if (created) HttpStatusCode.Created else HttpStatusCode.OK }) {
                        lists.create(access, list, decode(body, 8 * 1024 * 1024, payload = true)).also { created = it.second }.first
                    }
                } }
                post("{id}/intents") { run {
                    call.privateResponse(); val access = call.accountAccess(); val list = call.routeId("id"); val body = call.bytes(2 * 1024 * 1024)
                    call.listed(BatchView.serializer()) { lists.apply(access, list, decode(body, 2 * 1024 * 1024, payload = true)) }
                } }
                delete("{id}") { run { call.privateResponse(); lists.delete(call.accountAccess(), call.routeId("id")); call.respond(HttpStatusCode.NoContent) } }
                post("{id}/members") { run {
                    call.privateResponse(); val access = call.accountAccess(); val list = call.routeId("id"); val request = call.body<AddMember>(1024)
                    call.listed(ListView.serializer()) { lists.add(access, list, request) }
                } }
                put("{id}/archived") { run {
                    call.privateResponse(); val access = call.accountAccess(); val list = call.routeId("id"); val request = call.body<ArchiveList>(1024)
                    call.listed(ListView.serializer()) { lists.archive(access, list, request) }
                } }
                delete("{id}/members/{userId}") { run {
                    call.privateResponse(); lists.remove(call.accountAccess(), call.routeId("id"), call.routeId("userId")); call.respond(HttpStatusCode.NoContent)
                } }
            }
            get("/api/people") { run { call.privateResponse(); call.json(lists.people(call.accountAccess(mutation = false), call.request.queryParameters["q"] ?: "")) } }
            webIdentity.invitations?.let { invitations ->
                get("/api/invitations/challenge") { run(identity = true) {
                    call.privateResponse()
                    val start = invitations.challenge(ingress.client(call.request.local.remoteHost, call.request.headers.getAll("X-Forwarded-For").orEmpty()))
                    call.webCookie(webIdentity, webIdentity.invitationCookieName(), start.token, 1800)
                    call.json(start.result)
                } }
                post("/api/invitations/context") { run(identity = true) {
                    call.privateResponse()
                    invitations.attach(call.request.cookies[webIdentity.invitationCookieName()] ?: "", call.request.headers[HttpHeaders.Origin], call.request.headers["X-CSRF-Token"], call.identityBody(1024))
                    call.respond(HttpStatusCode.NoContent)
                } }
                get("/api/invitations/current") { run {
                    call.privateResponse()
                    val caller = try { call.reader(webIdentity) } catch (e: RelayError) { if (e.status in setOf(401, 403)) null else throw e }
                    call.json(invitations.preview(call.request.cookies[webIdentity.invitationCookieName()], caller) { c -> caller?.let { webIdentity.current(c, it) } })
                } }
                post("/api/invitations/accept") { run {
                    call.privateResponse(); val caller = call.webCaller(webIdentity)
                    call.listed(InvitationAccepted.serializer()) { invitations.accept(call.request.cookies[webIdentity.invitationCookieName()], caller, lists) { webIdentity.current(it, caller) } }
                } }
                post("/api/invitations/dismiss") { run {
                    call.privateResponse()
                    expect(call.request.headers[HttpHeaders.Origin] == webIdentity.config.origin, "forbidden", 403)
                    call.webCookie(webIdentity, webIdentity.invitationCookieName(), "", 0)
                    call.respond(HttpStatusCode.NoContent)
                } }
                for (admin in listOf(false, true)) route(if (admin) "/api/admin/invitations" else "/api/lists/{id}/invitations") {
                    get { run {
                        call.privateResponse(); val caller = call.reader(webIdentity)
                        call.json(invitations.page(caller, if (admin) null else call.routeId("id")) { webIdentity.current(it, caller) })
                    } }
                    post { run {
                        call.privateResponse(); val caller = call.webCaller(webIdentity); val request = call.identityBody<SendInvitation>(1024)
                        call.json(invitations.send(caller, if (admin) null else call.routeId("id"), request) { webIdentity.current(it, caller) }, HttpStatusCode.Created)
                    } }
                    post("{invitationId}/resend") { run {
                        call.privateResponse(); val caller = call.webCaller(webIdentity)
                        call.json(invitations.change(caller, if (admin) null else call.routeId("id"), call.routeId("invitationId"), true) { webIdentity.current(it, caller) })
                    } }
                    delete("{invitationId}") { run {
                        call.privateResponse(); val caller = call.webCaller(webIdentity)
                        call.json(invitations.change(caller, if (admin) null else call.routeId("id"), call.routeId("invitationId"), false) { webIdentity.current(it, caller) })
                    } }
                }
            }
        }
        // The household relay is gone; older apps read this and ask their user to update.
        for (path in listOf("/v1/households", "/v1/invites", "/v1/join-requests", "/v1/lists", "/v1/devices", "/v1/changes", "/v1/account/households", "/api/households", "/api/devices/link")) {
            route("$path/{...}") { handle { call.respondText("{\"code\":\"upgrade_required\"}", ContentType.Application.Json, HttpStatusCode.UpgradeRequired) } }
        }

        get("/auth/config") { run { call.privateResponse(); call.json(WebAuthConfig(webIdentity != null, if (webIdentity?.config?.oidc != null) "/auth/login" else null, webIdentity?.config?.oidc != null, webIdentity?.config?.localEnabled == true,
            if (webIdentity?.config?.oidc?.registrationEnabled == true) "/auth/register" else null, webIdentity?.invitations != null, webIdentity?.config?.invitationOnly == true)) } }
        if (webIdentity != null) {
            for (register in listOf(false, true)) get(if (register) "/auth/register" else "/auth/login") { run(identity = true) {
                call.privateResponse()
                val start = webIdentity.begin(ingress.client(call.request.local.remoteHost, call.request.headers.getAll("X-Forwarded-For").orEmpty()), register,
                    call.request.queryParameters["fresh"] == "true", call.request.cookies[webIdentity.invitationCookieName()])
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
                call.webCookie(webIdentity, webIdentity.sessionCookieName(), complete.token, WebIdentity.SESSION_SECONDS)
                call.respondRedirect("/")
            } }
            get("/api/me") { run { call.privateResponse(); call.json(call.reader(webIdentity).me()) } }
            get("/api/sessions") { run { call.privateResponse(); call.json(webIdentity.sessions(call.webToken(webIdentity))) } }
            post("/auth/logout") { run {
                call.privateResponse(); webIdentity.logout(call.webCaller(webIdentity))
                call.webCookie(webIdentity, webIdentity.sessionCookieName(), "", 0); call.respond(HttpStatusCode.NoContent)
            } }
            post("/auth/switch") { run(identity = true) {
                call.privateResponse()
                val result = webIdentity.switchAccount(call.webCaller(webIdentity))
                call.webCookie(webIdentity, webIdentity.sessionCookieName(), "", 0)
                call.json(result)
            } }
            post("/api/sessions/{id}/revoke") { run {
                call.privateResponse(); webIdentity.revoke(call.webCaller(webIdentity), call.routeId("id")); call.respond(HttpStatusCode.NoContent)
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
                    call.webCookie(webIdentity, webIdentity.sessionCookieName(), result.token, WebIdentity.SESSION_SECONDS); call.respond(HttpStatusCode.NoContent)
                } }
                post("/auth/local/activate") { run(identity = true) {
                    call.privateResponse(); call.webCookie(webIdentity, webIdentity.localCookieName(), "", 0)
                    val result = webIdentity.localActivate(call.request.cookies[webIdentity.localCookieName()] ?: "", call.request.headers[HttpHeaders.Origin], call.request.headers["X-CSRF-Token"],
                        ingress.client(call.request.local.remoteHost, call.request.headers.getAll("X-Forwarded-For").orEmpty())) { call.identityBody(2048) }
                    call.webCookie(webIdentity, webIdentity.sessionCookieName(), result.token, WebIdentity.SESSION_SECONDS); call.respond(HttpStatusCode.NoContent)
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
    }
}
private fun WebIdentity.sessionCookieName() = if (config.secureCookies) "__Host-usuals-session" else "usuals-session"
private fun WebIdentity.loginCookieName() = if (config.secureCookies) "__Host-usuals-login" else "usuals-login"
private fun WebIdentity.localCookieName() = if (config.secureCookies) "__Host-usuals-local-login" else "usuals-local-login"
private fun WebIdentity.invitationCookieName() = if (config.secureCookies) "__Host-usuals-invitation" else "usuals-invitation"
private fun ApplicationCall.webToken(identity: WebIdentity) = request.cookies[identity.sessionCookieName()] ?: reject("unauthenticated", 401)
private fun ApplicationCall.webCaller(identity: WebIdentity) = session(identity, identity.csrf(webToken(identity), request.headers[HttpHeaders.Origin], request.headers["X-CSRF-Token"]))
private fun ApplicationCall.reader(identity: WebIdentity) = session(identity, identity.reader(webToken(identity)))
/** The cookie follows the session's sliding expiry, so an active browser stays signed in. */
private fun ApplicationCall.session(identity: WebIdentity, caller: WebCaller) =
    caller.also { if (it.renewed) webCookie(identity, identity.sessionCookieName(), webToken(identity), WebIdentity.SESSION_SECONDS) }
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
private suspend inline fun <reified T> ApplicationCall.body(max: Int = 1024 * 1024): T = decode(bytes(max), max)
/** [payload] admits the JSON nulls and larger objects that list projections and intents carry. */
private inline fun <reified T> decode(bytes: ByteArray, max: Int, payload: Boolean = false): T = Wire.json.decodeFromJsonElement(Wire.strictJson(bytes, max, payload))
private suspend fun ApplicationCall.bytes(max: Int): ByteArray {
    expect(request.headers[HttpHeaders.ContentEncoding].let { it == null || it == "identity" })
    request.headers[HttpHeaders.ContentLength]?.let { expect(it.toLong() in 0..max.toLong(), "too_large", 413) }
    expect(request.contentType().match(ContentType.Application.Json))
    val channel = receiveChannel(); val buffer = ByteArray(8192); val output = ByteArrayOutputStream()
    while (true) {
        val n = channel.readAvailable(buffer, 0, minOf(buffer.size, max - output.size() + 1))
        if (n < 0) break
        output.write(buffer, 0, n); expect(output.size() <= max, "too_large", 413)
    }
    return output.toByteArray()
}
private suspend inline fun <reified T> ApplicationCall.json(value: T, status: HttpStatusCode = HttpStatusCode.OK) = respondText(Wire.json.encodeToString(kotlinx.serialization.serializer<T>(), value), ContentType.Application.Json, status)
private fun ApplicationCall.bearer(): String {
    val header = request.headers[HttpHeaders.Authorization] ?: reject("unauthenticated", 401)
    expect(header.startsWith("Bearer ") && header.length == 50, "unauthenticated", 401)
    return header.substring(7)
}
private fun ApplicationCall.routeId(name: String) = parameters[name]?.also { id(it) } ?: reject()
