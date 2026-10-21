package io.theficos.usuals.di

import io.theficos.usuals.sync.*
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
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse {
        validateOrigin(origin, allowLocal)
        require((path.startsWith("/v1/") || path.startsWith("/api/")) && '#' !in path && maxResponse in 1..(10 * 1024 * 1024))
        val builder = Request.Builder().url(origin + path)
        if (token != null) builder.header("Authorization", "Bearer $token")
        val payload = body ?: if (method in setOf("POST", "PUT", "PATCH")) byteArrayOf() else null
        builder.method(method, payload?.toRequestBody("application/json".toMediaType()))
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(builder.build())
            // A model call takes its time and a feed read may wait 25 s for a change.
            if (path.startsWith("/v1/ai/") || path.startsWith("/api/lists/changes")) call.timeout().timeout(45, TimeUnit.SECONDS)
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
    override fun toString() = "OkHttpRelayTransport([redacted])"
}
internal fun validateOrigin(value: String, local: Boolean) {
    val u = URI(value)
    require((u.scheme == "https" || local && u.scheme == "http" && u.host in setOf("10.0.2.2", "127.0.0.1", "localhost")) &&
        u.host != null && u.host == u.host.lowercase() && u.host.all { it.code < 128 } && u.userInfo == null &&
        u.rawPath.isNullOrEmpty() && u.rawQuery == null && u.rawFragment == null && (u.port == -1 || u.port in 1..65535) &&
        !(u.scheme == "https" && u.port == 443) && value == u.toASCIIString())
}
