package io.theficos.comanda.enrich

import io.theficos.comanda.model.Gtin

/**
 * The outcome of asking about one barcode, which is not the same thing as the
 * data that came back.
 *
 * The brief is emphatic about this and the API makes it cheap: a confirmed
 * missing record, a timeout, a 429, a malformed response and a lookup we chose
 * not to make are five materially different facts, and collapsing them into
 * "no data" is how a brief outage becomes a permanent negative cache entry. The
 * Open Food Facts v3 endpoint draws the important line for us. A genuine miss is
 * HTTP 404 with `result.id = product_not_found`; everything else that goes wrong
 * is us failing to ask, and must never be written down as an answer.
 *
 * Hence [cacheable]: only [Found] and [Missing] are answers. [Skipped] and
 * [Failed] are states of the asking, and the cache must refuse them.
 */
sealed interface QualityLookup {

    val gtin: Gtin

    /** Whether this outcome may be written to the cache as knowledge. */
    val cacheable: Boolean get() = this is Found || this is Missing

    /** OFF has a record. It may still be [ProductQuality.isBlank]. */
    data class Found(override val gtin: Gtin, val quality: ProductQuality) : QualityLookup

    /** OFF answered and does not have this product. Negative-cacheable. */
    data class Missing(override val gtin: Gtin) : QualityLookup

    /** We chose not to ask. Never cached, because nothing was learned. */
    data class Skipped(override val gtin: Gtin, val reason: SkipReason) : QualityLookup

    /** We asked and could not find out. Never cached. Retry state only. */
    data class Failed(override val gtin: Gtin, val reason: FailureReason) : QualityLookup
}

/** Why a lookup was not attempted. */
enum class SkipReason {
    /**
     * The barcode is in a GS1 range reserved for in-store use, so it identifies
     * a thing in one retailer's own numbering rather than a product anyone else
     * can resolve. See [RestrictedCirculation].
     */
    RESTRICTED_CIRCULATION,
    OPTED_OUT,


}

/**
 * Why a lookup failed.
 *
 * Kept separate from [SkipReason] rather than merged into one "did not happen"
 * enum, because the two have opposite retry semantics: a skip is a decision and
 * repeating it changes nothing, whereas a failure is worth trying again later.
 */
enum class FailureReason {
    /** The request did not complete in time. */
    TIMEOUT,

    /** Network unreachable, DNS failure, connection reset. */
    UNREACHABLE,

    /** HTTP 429. The limiter should back off; see any Retry-After header. */
    RATE_LIMITED,

    /** HTTP 5xx. Theirs, not ours. */
    SERVER_ERROR,

    /** A 2xx whose body was not the shape we expect. */
    MALFORMED,
}
