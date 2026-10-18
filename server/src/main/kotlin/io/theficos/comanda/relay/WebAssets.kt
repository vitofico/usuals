package io.theficos.comanda.relay

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Only packaged browser assets are public. Never fall back to HTML for API paths. */
internal fun Route.webAssets() {
    get("/") { call.browserAsset("index.html") }
    get("/locales.json") { call.browserAsset("locales.json") }
    for (prefix in listOf("assets", "src", "locales")) {
        get("/$prefix/{path...}") {
            val parts = call.parameters.getAll("path").orEmpty()
            if (parts.isEmpty() || parts.any { !Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}").matches(it) }) {
                call.respond(HttpStatusCode.NotFound)
            } else call.browserAsset("$prefix/" + parts.joinToString("/"))
        }
    }
}

private suspend fun ApplicationCall.browserAsset(path: String) {
    val type = when (path.substringAfterLast('.')) {
        "html" -> ContentType.Text.Html
        "mjs" -> ContentType.parse("text/javascript")
        "css" -> ContentType.Text.CSS
        "json" -> ContentType.Application.Json
        "svg" -> ContentType.parse("image/svg+xml")
        "ttf" -> ContentType.parse("font/ttf")
        "txt" -> ContentType.Text.Plain
        else -> null
    }
    val bytes = if (type == null) null else BrowserAssetCache.read(path)
    if (bytes == null) { respond(HttpStatusCode.NotFound); return }
    response.headers.append("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'; object-src 'none'")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("X-Frame-Options", "DENY")
    response.headers.append("Cross-Origin-Resource-Policy", "same-origin")
    response.headers.append("Cross-Origin-Opener-Policy", "same-origin")
    response.headers.append("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()")
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondBytes(bytes, requireNotNull(type))
}


// Concurrent requests share the finite packaged bytes instead of allocating a font per request.
private object BrowserAssetCache {
    private val files = mutableMapOf<String, ByteArray>()
    @Synchronized fun read(path: String): ByteArray? {
        files[path]?.let { return it }
        val stream = javaClass.getResourceAsStream("/web/$path") ?: return null
        val bytes = stream.use { it.readNBytes(2 * 1024 * 1024 + 1) }
        if (bytes.size > 2 * 1024 * 1024) return null
        files[path] = bytes
        return bytes
    }
}
