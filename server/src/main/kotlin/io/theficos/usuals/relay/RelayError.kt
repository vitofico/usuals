package io.theficos.usuals.relay

import kotlinx.serialization.Serializable

class RelayError(val code: String, val status: Int) : RuntimeException(code) {
    fun response() = ErrorResponse(code, if (status == 429) 60 else null)
}
internal fun reject(code: String = "invalid", status: Int = 400): Nothing = throw RelayError(code, status)
internal fun expect(condition: Boolean, code: String = "invalid", status: Int = 400) { if (!condition) reject(code, status) }
@Serializable data class ErrorResponse(val code: String, val retryAfterSeconds: Int? = null)
