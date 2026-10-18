package io.theficos.comanda.di

import io.theficos.comanda.sync.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OkHttpRelayTransport(private val allowLocal: Boolean) : RelayTransport {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse {
        validateOrigin(origin, allowLocal)
        require(path.startsWith("/v1/") && '#' !in path && maxResponse in 1..(10 * 1024 * 1024))
        val builder = Request.Builder().url(origin + path)
        if (token != null) builder.header("Authorization", "Bearer $token")
        builder.method(method, body?.toRequestBody("application/json".toMediaType()))
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(RelayProblem("offline")) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val source = response.body ?: throw RelayProblem("invalid")
                            if (source.contentLength() > maxResponse) throw RelayProblem("too_large", 413)
                            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            source.byteStream().use { stream -> while (true) {
                                val n = stream.read(buffer, 0, minOf(buffer.size, maxResponse - out.size() + 1))
                                if (n < 0) break
                                out.write(buffer, 0, n); if (out.size() > maxResponse) throw RelayProblem("too_large", 413)
                            } }
                            if (continuation.isActive) continuation.resume(WireResponse(response.code, out.toByteArray()))
                        } catch (_: IOException) { if (continuation.isActive) continuation.resumeWithException(RelayProblem("offline")) }
                        catch (error: RelayProblem) { if (continuation.isActive) continuation.resumeWithException(error) }
                    }
                }
            })
        }
    }
    suspend fun hints(access: RelayAccess, changed: () -> Unit): Unit = suspendCancellableCoroutine { continuation ->
        validateOrigin(access.origin, allowLocal)
        val request = Request.Builder().url(access.origin + "/v1/changes").header("Authorization", "Bearer " + access.token).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("[\"" + access.household + "\"]")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val value = Wire.strictJson(text.toByteArray(), 16384) as kotlinx.serialization.json.JsonObject
                    require(value.keys == setOf("householdId") && (value["householdId"] as? kotlinx.serialization.json.JsonPrimitive)?.content == access.household)
                    changed()
                } catch (_: Exception) { webSocket.cancel() }
            }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { webSocket.cancel() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (continuation.isActive) continuation.resume(Unit) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (continuation.isActive) continuation.resumeWithException(RelayProblem("offline"))
            }
        })
        continuation.invokeOnCancellation { socket.cancel() }
    }
    override fun toString() = "OkHttpRelayTransport([redacted])"
}
internal fun validateOrigin(value: String, local: Boolean) {
    val u = URI(value)
    require((u.scheme == "https" || local && u.scheme == "http" && u.host in setOf("10.0.2.2", "127.0.0.1", "localhost")) &&
        u.host != null && u.host == u.host.lowercase() && u.host.all { it.code < 128 } && u.userInfo == null &&
        u.rawPath.isNullOrEmpty() && u.rawQuery == null && u.rawFragment == null && (u.port == -1 || u.port in 1..65535) &&
        !(u.scheme == "https" && u.port == 443) && value == u.toASCIIString())
}
