package io.theficos.usuals.recipe

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class RecipeProblem { INVALID_URL, BLOCKED, HTTP, UNREACHABLE, TIMEOUT, TOO_LARGE, NOT_HTML }
sealed interface RecipeFetch {
    data class Read(val url: String, val document: RecipeDocument?, val prose: String) : RecipeFetch
    data class Failed(val problem: RecipeProblem, val httpCode: Int? = null) : RecipeFetch
}

class RecipeFetcher(
    private val extractor: RecipeJsonLd,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
) {
    suspend fun fetch(url: String): RecipeFetch {
        val address = url.toHttpUrlOrNull() ?: return RecipeFetch.Failed(RecipeProblem.INVALID_URL)
        if (address.username.isNotEmpty() || address.password.isNotEmpty()) return RecipeFetch.Failed(RecipeProblem.INVALID_URL)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(address).header("User-Agent", "Usuals/0.2 (on-device recipe import)")
                .header("Accept", "text/html,application/xhtml+xml").build())
            // Keep cancellation attached until the body is read and closed, not just until headers arrive.
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resume(failure(e)) }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use(::readResponse) }
                        .recoverCatching { error -> if (error is IOException) failure(error) else throw error }
                    continuation.resumeWith(result)
                }
            })
        }
    }

    private fun readResponse(response: Response): RecipeFetch {
        if (response.code in setOf(401, 403, 429)) return RecipeFetch.Failed(RecipeProblem.BLOCKED, response.code)
        if (!response.isSuccessful) return RecipeFetch.Failed(RecipeProblem.HTTP, response.code)
        val body = response.body ?: return RecipeFetch.Failed(RecipeProblem.NOT_HTML)
        if (body.contentType()?.subtype?.let { it != "html" && it != "xhtml+xml" } == true) return RecipeFetch.Failed(RecipeProblem.NOT_HTML)
        if (body.contentLength() > MAX_BYTES) return RecipeFetch.Failed(RecipeProblem.TOO_LARGE)
        val source = body.source()
        source.request(MAX_BYTES + 1)
        if (source.buffer.size > MAX_BYTES) return RecipeFetch.Failed(RecipeProblem.TOO_LARGE)
        val html = source.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
        val document = extractor.extract(html)
        if (document == null && Regex("cf-chl-|verify you are human|captcha", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            return RecipeFetch.Failed(RecipeProblem.BLOCKED, response.code)
        }
        return RecipeFetch.Read(response.request.url.toString(), document, if (document == null) HtmlText.prose(html) else "")
    }

    private fun failure(error: IOException) = RecipeFetch.Failed(
        if (error is SocketTimeoutException) RecipeProblem.TIMEOUT else RecipeProblem.UNREACHABLE,
    )

    companion object { private const val MAX_BYTES = 4L * 1024 * 1024 }
}
