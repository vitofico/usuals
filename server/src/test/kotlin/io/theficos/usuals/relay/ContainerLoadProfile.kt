package io.theficos.usuals.relay

import io.theficos.usuals.sync.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Explicit local-only load client, never run by the normal test task. No keys leave memory. */
object ContainerLoadProfile {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 1)
        val input = Path.of(args.single())
        val config = Wire.json.parseToJsonElement(Files.readString(input)).jsonObject
        val address = URI(config.getValue("address").jsonPrimitive.content)
        require(address.scheme == "http" && address.host == "host.docker.internal" && address.port in 1..65535 && address.path.isEmpty())
        val origin = "https://usuals.invalid"
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val transport = object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse = withContext(Dispatchers.IO) {
                require(origin == "https://usuals.invalid" && path.startsWith("/v1/"))
                val request = HttpRequest.newBuilder(URI(address.toString() + path)).timeout(Duration.ofSeconds(60))
                    .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
                if (body != null) request.header("Content-Type", "application/json")
                if (token != null) request.header("Authorization", "Bearer $token")
                val response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
                val bytes = response.body().use { it.readNBytes(maxResponse + 1) }
                require(bytes.size <= maxResponse)
                WireResponse(response.statusCode(), bytes)
            }
        }
        val state = MemorySyncStore()
        val source = CheckpointSource { _, _ -> ListCheckpoint(0, Wire.zero, SnapshotCodec.encode(Projection(name = "Synthetic capacity fixture"))) }
        val owner = HouseholdClient(state, transport, source)
        val setup = owner.prepare(origin)
        val household = owner.bootstrap(setup.verifySaved(setup.file, setup.secret), config.getValue("bootstrapToken").jsonPrimitive.content)
        val list = UUID.randomUUID().toString()
        owner.createList(household, list)
        val saved = state.read().households.getValue(household)
        val token = requireNotNull(saved.session).token
        val header = Header(saved.server.serverId, household, list, saved.device.publicKeys.id, UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), saved.epoch, saved.hash, 0, Wire.zero)
        val envelope = SyncCrypto.seal(header, ByteArray(7_860_000), Wire.decode(saved.keys.getValue("$list:${saved.epoch}"), 32), saved.device, maxDecoded = 10 * 1024 * 1024) { state.reserve("profile") }
        val body = Wire.json.encodeToString(Snapshot(envelope, 0, Wire.zero)).toByteArray()
        require(body.size in 10_000_000 until 10 * 1024 * 1024)
        val first = transport.request(origin, "PUT", "/v1/lists/$list/snapshot", body, token, 10 * 1024 * 1024)
        check(first.status == 200)
        val start = CompletableDeferred<Unit>()
        val bulk = (0 until 8).map { index -> async {
            start.await()
            transport.request(origin, if (index % 2 == 0) "PUT" else "GET", "/v1/lists/$list/snapshot",
                if (index % 2 == 0) body else null, token, 10 * 1024 * 1024).status
        } }
        val reads = async {
            start.await()
            (0 until 24).map {
                val before = System.nanoTime()
                val response = transport.request(origin, "GET", "/v1/lists/$list/operations?after=0", null, token)
                check(response.status in setOf(200, 503))
                val elapsed = (System.nanoTime() - before) / 1_000_000.0
                delay(20)
                response.status to elapsed
            }
        }
        start.complete(Unit)
        val statuses = bulk.awaitAll()
        check(statuses.all { it in setOf(200, 503) })
        val catchup = reads.await()
        check(transport.request(origin, "GET", "/v1/lists/$list/operations?after=0", null, token).status == 200)
        val downloaded = transport.request(origin, "GET", "/v1/lists/$list/snapshot", null, token, 10 * 1024 * 1024)
        check(downloaded.status == 200 && downloaded.body.contentEquals(body))
        val times = catchup.map { it.second }.sorted()
        val result = buildJsonObject {
            put("passed", true); put("firstPublishAccepted", true); put("snapshotRequestBytes", body.size); put("authenticatedBulkRequests", statuses.size)
            put("bulkAccepted", statuses.count { it == 200 }); put("bulkShed", statuses.count { it == 503 })
            put("catchupRequests", catchup.size); put("catchupAccepted", catchup.count { it.first == 200 })
            put("catchupShed", catchup.count { it.first == 503 }); put("catchupP95IncludingShedMs", times[(times.size * .95).toInt().coerceAtMost(times.lastIndex)])
            put("scope", "local single-household near-limit authenticated snapshot stress; not long-duration or cluster capacity")
        }
        Files.writeString(input.resolveSibling("authenticated-results.json"), result.toString())
        println("PASS: authenticated near-limit snapshot stress and subsequent catch-up")
    }
}
