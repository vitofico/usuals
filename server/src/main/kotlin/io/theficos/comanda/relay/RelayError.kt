package io.theficos.comanda.relay

import io.theficos.comanda.sync.Receipt
import kotlinx.serialization.Serializable

class RelayError(val code: String, val status: Int, val inputIndex: Int? = null,
    val existingEnvelopeId: String? = null, val existingReceipt: Receipt? = null) : RuntimeException(code) {
    fun at(index: Int) = RelayError(code, status, inputIndex ?: index, existingEnvelopeId, existingReceipt)
    fun response() = ErrorResponse(code, if (status == 429) 60 else null, inputIndex, existingEnvelopeId, existingReceipt)
}
internal fun reject(code: String = "invalid", status: Int = 400): Nothing = throw RelayError(code, status)
internal fun expect(condition: Boolean, code: String = "invalid", status: Int = 400) { if (!condition) reject(code, status) }
internal inline fun <T> input(index: Int, block: () -> T): T = try { block() } catch (error: RelayError) { throw error.at(index) }
@Serializable data class ErrorResponse(val code: String, val retryAfterSeconds: Int? = null, val inputIndex: Int? = null,
    val existingEnvelopeId: String? = null, val existingReceipt: Receipt? = null)
