package io.theficos.usuals.relay

import java.net.URI
import kotlinx.serialization.json.*

/** An exact CSP allowlist, never a model proxy or a place to store provider credentials. */
class BrowserModels(origins: List<String> = emptyList()) {
    val origins = origins.distinct().sorted().toList()
    init {
        require(this.origins.size <= 16) { "invalid_web_model_origins" }
        for (value in this.origins) {
            val uri = runCatching { URI(value) }.getOrNull()
            require(uri != null && value.length <= 512 && value.all { it.code in 33..126 } &&
                !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null &&
                uri.rawPath.isNullOrEmpty() && uri.port in -1..65535 && uri.port != 0 &&
                (uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]")) &&
                value == "${uri.scheme}://${uri.host.lowercase()}" + (if (uri.port == -1 || uri.scheme == "https" && uri.port == 443 || uri.scheme == "http" && uri.port == 80) "" else ":${uri.port}") && uri.rawAuthority.none { it in "';\\\"" }) { "invalid_web_model_origins" }
        }
    }
    fun json(): String = buildJsonObject { put("origins", JsonArray(origins.map(::JsonPrimitive))) }.toString()
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): BrowserModels {
            val text = environment["USUALS_WEB_MODEL_ORIGINS"] ?: "[]"
            require(text.length <= 8192) { "invalid_web_model_origins" }
            val values = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull()
            require(values != null && values.all { it is JsonPrimitive && it.isString }) { "invalid_web_model_origins" }
            return BrowserModels(values.map { it.jsonPrimitive.content })
        }
    }
}
