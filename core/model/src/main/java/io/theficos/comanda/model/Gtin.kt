package io.theficos.comanda.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A canonical EAN-13 barcode, the grocery equivalent of an ISBN.
 *
 * This is the identity everything downstream keys on: the household alias
 * cache, the shared corpus, and any future portability between shops. So it
 * follows the same discipline quire applies to ISBN-13: one canonical stored
 * form, checksum validated at the door, invalid input rejected rather than
 * carried around as a suspect string. A [Gtin] that exists is a [Gtin] that
 * passed the check digit.
 *
 * [parse] accepts the three encodings a consumer product actually carries and
 * normalizes all of them to 13 digits:
 *
 * - **EAN-13**, stored as is.
 * - **UPC-A** (12 digits, North American packaging), left-padded with one zero.
 * - **EAN-8** (short codes on small packaging), left-padded with five zeros.
 *
 * Left-padding is safe because the GS1 mod-10 weights are assigned from the
 * right, so the added zeros neither shift the weight of an existing digit nor
 * contribute to the sum. A valid UPC-A or EAN-8 is therefore still valid once
 * widened, which is why one check routine covers all three.
 *
 * GTIN-14 is deliberately not accepted. It identifies a case or a pallet, not
 * the thing a person puts in a basket, and silently accepting one would put a
 * trade unit into a cart line.
 */
@Serializable(with = GtinSerializer::class)
class Gtin private constructor(val value: String) {

    override fun equals(other: Any?): Boolean = this === other || (other is Gtin && other.value == value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        private const val EAN_13_LENGTH = 13

        /** The lengths a consumer barcode arrives in, before widening. */
        private val ACCEPTED_LENGTHS = setOf(8, 12, EAN_13_LENGTH)

        /**
         * Returns the canonical EAN-13 form of [raw], or null when [raw] is not
         * a barcode we accept. Hyphens and whitespace are stripped first, since
         * printed and hand-typed codes carry both.
         */
        fun parse(raw: String): Gtin? {
            val digits = raw.filterNot { it == '-' || it.isWhitespace() }
            if (digits.length !in ACCEPTED_LENGTHS) return null
            if (!digits.all { it in '0'..'9' }) return null

            val ean13 = digits.padStart(EAN_13_LENGTH, '0')

            // An all-zero code has a valid check digit and is never a product.
            // It is what a misread or a half-initialised scanner buffer looks
            // like, so reject it here rather than letting it reach the cache.
            if (ean13.all { it == '0' }) return null
            if (!hasValidCheckDigit(ean13)) return null

            return Gtin(ean13)
        }

        fun isValid(raw: String): Boolean = parse(raw) != null

        /**
         * GS1 mod-10: weight the digits 1, 3, 1, 3, ... from the left across a
         * 13-digit code (which puts weight 1 on the check digit at the right)
         * and require the total to be a multiple of ten.
         */
        private fun hasValidCheckDigit(ean13: String): Boolean {
            var sum = 0
            for (i in ean13.indices) {
                val digit = ean13[i] - '0'
                sum += if (i % 2 == 0) digit else digit * 3
            }
            return sum % 10 == 0
        }
    }
}

/**
 * Serializes a [Gtin] as its canonical 13-digit string, and re-validates on the
 * way in. A blob that has been round-tripped through the relay or a Room column
 * gets the same check as a freshly scanned code: there is no path that produces
 * a [Gtin] instance without a passing check digit.
 */
object GtinSerializer : KSerializer<Gtin> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.theficos.comanda.model.Gtin", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Gtin) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): Gtin {
        val raw = decoder.decodeString()
        return Gtin.parse(raw) ?: throw SerializationException("not a valid GTIN: $raw")
    }
}
