package io.theficos.usuals.enrich

import io.theficos.usuals.model.Gtin
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** On-device barcode reads, with shared waiting permits, single flight and no search. */
class OpenFoodFactsEnricher(
    userAgent: String,
    private val rateLimiter: OffRateLimiter,
    private val singleFlight: SingleFlight<Gtin, QualityLookup> = SingleFlight(),
    private val isRestricted: (Gtin) -> Boolean = RestrictedCirculation::isRestricted,
    baseUrl: String = DEFAULT_BASE_URL,
    sisterBaseUrls: Map<ProductType, String> = SISTER_BASE_URLS,
    connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS,
    callTimeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MILLIS,
    httpClient: OkHttpClient = OkHttpClient(),
    private val allowRequest: () -> Boolean = { true },
    private val preferredLanguage: () -> String = { "en" },
) : ProductEnricher {

    init {
        require(userAgent.isNotBlank()) {
            "Open Food Facts blocks clients with no meaningful User-Agent, so an empty one is a bug"
        }
    }

    private val endpoint: HttpUrl = baseUrl.toHttpUrl()

    /**
     * Where a non-food barcode is asked about next. Our own table, never a host
     * taken from a response, so a tampered or misdirected answer cannot choose
     * where the barcode goes.
     */
    private val sisterEndpoints: Map<ProductType, HttpUrl> = sisterBaseUrls.mapValues { it.value.toHttpUrl() }

    /**
     * The caller's client, rebuilt rather than used as given.
     *
     * Taking a client and then overriding its timeouts and adding the
     * interceptor here means there is no way to construct this class with a
     * client that could send an anonymous request, including from a test.
     * `newBuilder` shares the connection pool and dispatcher, so this is a
     * cheap derivation and not a second HTTP stack.
     */
    private val client: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        // The bound that actually holds. connect and read time out individual
        // socket operations, so a server trickling a byte at a time resets the
        // read clock for ever and a "hard short timeout" becomes unbounded.
        // This one covers the whole call, redirects and retries included, which
        // is the number a caller can reason about.
        .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        // One permit, one request, made true rather than assumed. Left at
        // OkHttp's default this client follows up to twenty redirects under the
        // single permit the limiter granted, so a captive portal or an
        // intercepting network, which is the ordinary case on a phone, could
        // turn fifteen permitted requests a minute into three hundred actual
        // ones leaving the device. Open Food Facts see the requests, not the
        // permits. The `read` branch below already expects this: it lists "a
        // redirect OkHttp did not follow" among the responses it labels
        // malformed.
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(UserAgentInterceptor(userAgent))
        .build()

    override suspend fun lookup(gtin: Gtin): QualityLookup {
        if (!allowRequest()) return QualityLookup.Skipped(gtin, SkipReason.OPTED_OUT)
        // First, and outside both the limiter and the single flight, on purpose.
        // An in-store barcode identifies a thing in one retailer's own numbering
        // and OFF cannot resolve it, so asking would spend a permit from a
        // budget shared with every real lookup in order to be told nothing.
        if (isRestricted(gtin)) {
            return QualityLookup.Skipped(gtin, SkipReason.RESTRICTED_CIRCULATION)
        }

        // Single flight outside the limiter, so that ten simultaneous lookups of
        // the same barcode cost one permit rather than ten.
        return singleFlight.run(gtin) { rateLimiter.withPermit {
            if (!allowRequest()) QualityLookup.Skipped(gtin, SkipReason.OPTED_OUT) else {
                val result = fetch(gtin)
                if (allowRequest()) result else QualityLookup.Skipped(gtin, SkipReason.OPTED_OUT)
            }
        } }
    }

    override fun toString(): String = "OpenFoodFactsEnricher(endpoint=$endpoint)"

    // ---- the wire ----------------------------------------------------------

    /**
     * One read, or two when Open Food Facts says the barcode is not food.
     *
     * The second read goes to the sister database for that product type, under
     * the permit the first one took. That bends "one permit, one request" on
     * purpose: both hosts belong to one organisation, the second read happens
     * only after an explicit answer naming its database, and spending a second
     * permit would make a non-food scan wait behind a full window for a name.
     * Whatever the sister read returns, the type answer stands: it is what Open
     * Food Facts said, and caching it is what stops every rescan of a shampoo
     * spending a permit to be told the same thing.
     */
    private suspend fun fetch(gtin: Gtin): QualityLookup = withContext(Dispatchers.IO) {
        val answer = ask(endpoint, gtin)
        val type = (answer as? QualityLookup.Found)?.quality?.productType?.takeIf { it != ProductType.FOOD }
            ?: return@withContext answer
        val sister = sisterEndpoints[type]
        // Consent is checked again because this read sends the barcode to a host
        // the person has not been asked about since the first request went out.
        if (sister == null || !allowRequest()) return@withContext answer
        val record = (ask(sister, gtin) as? QualityLookup.Found)?.quality
        // Only the naming facts are kept. Grades in a sister record are not food
        // grades, and its category tags do not map onto shopping aisles.
        QualityLookup.Found(gtin, notFood(type).copy(
            productName = record?.productName,
            brands = record?.brands,
            packageSize = record?.packageSize,
            sourceModifiedAtEpochSeconds = record?.sourceModifiedAtEpochSeconds,
        ))
    }

    private fun ask(base: HttpUrl, gtin: Gtin): QualityLookup {
        val request = Request.Builder().url(productUrl(base, gtin)).get().build()
        return try {
            client.newCall(request).execute().use { response -> read(gtin, response) }
        } catch (timeout: SocketTimeoutException) {
            // A connect or read timeout. Worth trying again later, which is why
            // it is not the same answer as unreachable.
            QualityLookup.Failed(gtin, FailureReason.TIMEOUT)
        } catch (interrupted: InterruptedIOException) {
            // What a call timeout looks like. Same fact, different exception.
            QualityLookup.Failed(gtin, FailureReason.TIMEOUT)
        } catch (io: IOException) {
            // No DNS, no route, connection reset: there was nothing to ask.
            QualityLookup.Failed(gtin, FailureReason.UNREACHABLE)
        }
    }

    /** Locale fields are requested only for the current language and English fallback. */
    private fun productUrl(base: HttpUrl, gtin: Gtin): HttpUrl {
        val language = preferredLanguage().lowercase(java.util.Locale.ROOT).takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "en"
        val localized = listOf(language, "en").distinct().flatMap { lang ->
            listOf("product_name_$lang", "generic_name_$lang", "ingredients_text_$lang")
        }
        return base.newBuilder()
            .addPathSegments("api/v3/product/${gtin.value}.json")
            .addQueryParameter("fields", (listOf(FIELDS) + localized).joinToString(","))
            .addQueryParameter("lc", language)
            .build()
    }

    private fun read(gtin: Gtin, response: Response): QualityLookup {
        // A body that dies halfway through reading is a malformed answer, not an
        // unreachable server, so the read is caught here rather than left to the
        // IOException handler around the call.
        val body = if (response.code == HTTP_OK || response.code == HTTP_NOT_FOUND) runCatching {
            response.body?.let { body ->
                if (body.contentLength() > MAX_BODY_BYTES) return@let null
                val source = body.source()
                source.request(MAX_BODY_BYTES + 1)
                if (source.buffer.size > MAX_BODY_BYTES) null else source.readUtf8()
            }
        }.getOrNull() else null
        return when {
            response.code == HTTP_OK -> readRecord(gtin, body)

            response.code == HTTP_NOT_FOUND -> readMiss(gtin, body)

            response.code == HTTP_TOO_MANY_REQUESTS -> {
                // The one header worth obeying, and a floor for when it is not
                // sent. Telling the limiter is how a single 429 slows the whole
                // app down instead of the next lookup walking into the same
                // wall.
                //
                // The fallback is the part that matters in practice. This
                // handler used to do nothing at all without a parseable
                // Retry-After, and Open Food Facts has never been observed to
                // send one, so the only 429 the app can actually receive was the
                // one it ignored: it would carry on at fifteen a minute into a
                // service that had just said stop. Being told to slow down is
                // itself the instruction; the header only says for how long.
                val pause = offRetryAfterMillis(response.header("Retry-After"))
                rateLimiter.noteRetryAfter(pause ?: DEFAULT_RATE_LIMIT_BACKOFF_MILLIS)
                QualityLookup.Failed(gtin, FailureReason.RATE_LIMITED)
            }

            response.code >= HTTP_SERVER_ERROR ->
                QualityLookup.Failed(gtin, FailureReason.SERVER_ERROR)

            // Any other status is an exchange that completed and produced
            // nothing we can read as an answer about this product: a 403 at a
            // proxy, a 400 from an API version that moved, a redirect OkHttp
            // did not follow. The label matters less than what it is not, and
            // what it is not is Missing.
            else -> QualityLookup.Failed(gtin, FailureReason.MALFORMED)
        }
    }

    /**
     * A 200 is a record when it carries a product object, and the object itself
     * is the evidence. The `status` and `result.id` strings are deliberately not
     * required to match: they are labels OFF could reword, and a wording change
     * should not turn every lookup in the app into a failure when the data we
     * came for is sitting right there.
     */
    private fun readRecord(gtin: Gtin, body: String?): QualityLookup {
        val root = parseObject(body) ?: return QualityLookup.Failed(gtin, FailureReason.MALFORMED)
        val product = root["product"] as? JsonObject ?: return QualityLookup.Failed(gtin, FailureReason.MALFORMED)
        if (!codeMatches(root, gtin) || !codeMatches(product, gtin)) return QualityLookup.Failed(gtin, FailureReason.MALFORMED)
        return QualityLookup.Found(gtin, qualityOf(product))
    }

    /**
     * A 404 is the opposite case, so it is read strictly.
     *
     * There is no product object to inspect, only OFF's word for it, which means
     * the word has to be the right one. A 404 that does not say
     * `product_not_found` is more likely to be us asking a URL that no longer
     * exists than OFF confirming an absence, and the cost of getting that wrong
     * is a negative cache entry that outlives the mistake.
     *
     * The one other word accepted is OFF saying the barcode belongs to a sister
     * database, and only with the type it names in `errors[].field` (observed as
     * `{"id": "product_type", "value": "beauty"}`). That is an answer about the
     * product, not about our URL: it is cached as a record of that type so a
     * rescan does not ask again, and [fetch] may then read the sister database
     * for a name.
     */
    private fun readMiss(gtin: Gtin, body: String?): QualityLookup {
        val root = parseObject(body) ?: return QualityLookup.Failed(gtin, FailureReason.MALFORMED)
        if (!codeMatches(root, gtin)) return QualityLookup.Failed(gtin, FailureReason.MALFORMED)
        return when ((root["result"] as? JsonObject)?.stringOrNull("id")) {
            PRODUCT_NOT_FOUND -> QualityLookup.Missing(gtin)
            PRODUCT_IN_SISTER_DATABASE -> productTypeOf(root)?.let { QualityLookup.Found(gtin, notFood(it)) }
                ?: QualityLookup.Failed(gtin, FailureReason.MALFORMED)
            else -> QualityLookup.Failed(gtin, FailureReason.MALFORMED)
        }
    }

    /** The non-food type named in a sister-database answer, or null when it names none we know. */
    private fun productTypeOf(root: JsonObject): ProductType? =
        (root["errors"] as? JsonArray).orEmpty().asSequence()
            .mapNotNull { (it as? JsonObject)?.get("field") as? JsonObject }
            .filter { it.stringOrNull("id") == "product_type" }
            .mapNotNull { NON_FOOD_TYPES[it.stringOrNull("value")] }
            .firstOrNull()

    /** A non-food answer before any sister record is read: no food grade applies to it. */
    private fun notFood(type: ProductType) = ProductQuality(productType = type, nutriScoreNotApplicable = true)

    private fun codeMatches(record: JsonObject, gtin: Gtin): Boolean {
        val code = record["code"] ?: return true
        val value = (code as? JsonPrimitive)?.takeIf { it.isString }?.content
        return Gtin.parse(value.orEmpty()) == gtin
    }

    // ---- reading the record ------------------------------------------------

    private fun qualityOf(product: JsonObject): ProductQuality = ProductQuality(
        productName = localizedText(product, "product_name", 160) ?: localizedText(product, "generic_name", 160),
        categories = tagsOrNull(product["categories_tags"]),
        nutriScore = gradeOrNull<NutriScore>(product.stringOrNull("nutriscore_grade")),
        nutriScoreNotApplicable = product.stringOrNull("nutriscore_grade")?.trim()?.lowercase(java.util.Locale.ROOT) == NOT_APPLICABLE,
        novaGroup = novaGroupOrNull(product["nova_group"]),
        // The v3.1 name first: API versions after v3 renamed the field, and the
        // old name may stop being sent before this client moves off v3.
        greenScore = greenScoreOrNull(
            product.stringOrNull("environmental_score_grade") ?: product.stringOrNull("ecoscore_grade"),
        ),
        allergens = tagsOrNull(product["allergens_tags"]),
        additives = tagsOrNull(product["additives_tags"]),
        brands = cleanText(product.stringOrNull("brands"), 240),
        packageSize = cleanText(product.stringOrNull("quantity"), 120),
        ingredients = localizedText(product, "ingredients_text", 8_000),
        traces = tagsOrNull(product["traces_tags"]),
        sourceModifiedAtEpochSeconds = (product["last_modified_t"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?.takeIf { it in 1..253402300799L },
    )

    /**
     * A single letter or nothing.
     *
     * This is where the sentinels die. `"unknown"` and `"not-applicable"` are
     * words OFF writes where a grade would go, and neither is a grade, so
     * neither matches an enum entry and both come back null. Anything else OFF
     * invents later fails the same way, which is the point of matching against
     * the enum instead of trusting the string. "Not applicable" is still worth
     * knowing, so [qualityOf] reads it separately rather than through here.
     */
    private inline fun <reified T : Enum<T>> gradeOrNull(raw: String?): T? {
        val letter = raw?.trim()?.uppercase() ?: return null
        if (letter.length != 1) return null
        return enumValues<T>().firstOrNull { it.name == letter }
    }

    /**
     * The Green-Score, whose top grade OFF writes as `a-plus`. `a+` is accepted
     * too because it is how the grade is printed, and costs one entry.
     */
    private fun greenScoreOrNull(raw: String?): GreenScore? = when (raw?.trim()?.lowercase(java.util.Locale.ROOT)) {
        "a-plus", "a+" -> GreenScore.A_PLUS
        else -> gradeOrNull<GreenScore>(raw)
    }

    /**
     * NOVA 1 to 4, mapped onto the four [NovaGroup] entries in order.
     *
     * Only the numeric form is in the observed contract. The string form is
     * accepted too because it costs one branch, OFF has been known to stringify
     * numbers, and the 1 to 4 range check means leniency here cannot produce a
     * group that does not exist.
     */
    private fun novaGroupOrNull(element: JsonElement?): NovaGroup? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        val group = primitive.content.toIntOrNull() ?: return null
        return NovaGroup.entries.getOrNull(group - 1)
    }

    private fun localizedText(product: JsonObject, field: String, limit: Int): String? {
        val language = preferredLanguage().lowercase(java.util.Locale.ROOT).takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "en"
        return listOf("${field}_$language", field, "${field}_en").distinct().firstNotNullOfOrNull {
            cleanText(product.stringOrNull(it), limit)
        }
    }

    private fun cleanText(raw: String?, limit: Int): String? = raw?.replace(WHITESPACE, " ")
        ?.filterNot(Char::isISOControl)?.trim()?.take(limit)?.ifBlank { null }

    /** Missing and empty source arrays remain distinct; neither claims safety. */
    private fun tagsOrNull(element: JsonElement?): List<String>? {
        val array = element as? JsonArray ?: return null
        val kept = array.asSequence()
            .filterIsInstance<JsonPrimitive>()
            .filter { it !is JsonNull && it.isString }
            .map { sanitizeTag(it.content) }
            .filter { it.isNotEmpty() }
            .take(MAX_TAGS)
            .toList()
        return if (kept.isEmpty() && array.isNotEmpty()) null else kept
    }

    /** Bound source tags before they enter local storage or UI. */
    private fun sanitizeTag(raw: String): String = raw
        .filterNot { it.isISOControl() }
        .replace(WHITESPACE, " ")
        .trim()
        .take(MAX_TAG_LENGTH)

    private fun parseObject(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
    }

    companion object {

        /**
         * The `world` subdomain rather than a country one. A barcode scanned in
         * one country routinely belongs to a product listed under another, and
         * `world` is the union of the country views rather than a slice of it.
         */
        const val DEFAULT_BASE_URL: String = "https://world.openfoodfacts.org"

        /** The sister databases, verified to serve the same v3 product read on 2026-10-22. */
        val SISTER_BASE_URLS: Map<ProductType, String> = mapOf(
            ProductType.BEAUTY to "https://world.openbeautyfacts.org",
            ProductType.PRODUCT to "https://world.openproductsfacts.org",
            ProductType.PET_FOOD to "https://world.openpetfoodfacts.org",
        )

        /** Only mapped fields are requested; no catalogue search is performed. */
        const val FIELDS: String = "code,product_name,categories_tags,nutriscore_grade," +
            "nova_group,ecoscore_grade,environmental_score_grade,allergens_tags,additives_tags,generic_name,brands,quantity," +
            "ingredients_text,traces_tags,last_modified_t"

        /**
         * Three seconds to connect, five to answer.
         *
         * Short because of where this runs: somebody is standing in a kitchen
         * watching a list fill in, and a lookup that has not answered in five
         * seconds has already failed as far as they are concerned. Quality is an
         * enrichment, so giving up early costs a grade and never a shopping list.
         */
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Long = 3_000
        const val DEFAULT_READ_TIMEOUT_MILLIS: Long = 5_000

        /**
         * The whole call, end to end.
         *
         * Larger than connect plus read because it covers a call that got
         * partway and stalled, and small enough that nothing in this module can
         * hold a coroutine for a noticeable fraction of a minute. Enrichment is
         * optional: a lookup that is taking this long has already failed at
         * being the thing that never makes anybody wait.
         */
        const val DEFAULT_CALL_TIMEOUT_MILLIS: Long = 10_000

        /**
         * How long to stand down after a 429 that did not say.
         *
         * One window, because the limit being enforced is a per-minute one and
         * a full window is the smallest pause that can be sure of clearing it.
         * Erring long costs a minute of an optional feature being colder than it
         * could be. Erring short costs a service that has already asked us to
         * stop being asked again immediately.
         */
        const val DEFAULT_RATE_LIMIT_BACKOFF_MILLIS: Long = OffRateLimiter.ONE_MINUTE_MILLIS
    }
}

/**
 * Stamps the User-Agent on every request leaving this client.
 *
 * No [Interceptor] has been used anywhere in this repo before, so this is a new
 * mechanism, and it is worth the novelty for one reason: OFF asks clients to
 * identify themselves and blocks the ones that do not, and a per-call header is
 * something a future edit can forget. A header set here cannot be forgotten by
 * a new call site, cannot be dropped by a refactor that rebuilds a request, and
 * applies to a redirect the same way it applies to the original. The rule is
 * "no request escapes anonymous", and an interceptor is the only place that rule
 * can actually live.
 *
 * `header` rather than `addHeader`, so this wins over anything a caller set.
 */
private class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
}

/**
 * Reads a `Retry-After` header as milliseconds from now, or null.
 *
 * Only the delta-seconds form is read. The HTTP date form is legal and OFF does
 * not send it, and a date we cannot parse is simply not noted, which leaves the
 * limiter's own window in charge rather than guessing at a pause.
 *
 * The cap is the security part. This number arrives from the network and is
 * about to become a delay every later lookup waits out, so a header of
 * `999999999` would otherwise switch enrichment off for the rest of the process.
 * Ten minutes is already far longer than anything worth waiting for on a phone.
 */
internal fun offRetryAfterMillis(raw: String?): Long? {
    val seconds = raw?.trim()?.toLongOrNull() ?: return null
    if (seconds <= 0) return null
    return TimeUnit.SECONDS.toMillis(seconds.coerceAtMost(MAX_RETRY_AFTER_SECONDS))
}

private val json = Json

private val WHITESPACE = Regex("\\s+")

private const val HTTP_OK = 200
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500

private const val PRODUCT_NOT_FOUND = "product_not_found"
private const val PRODUCT_IN_SISTER_DATABASE = "product_found_with_a_different_product_type"
private const val NOT_APPLICABLE = "not-applicable"

/** The `product_type` values OFF names in a sister-database answer. */
private val NON_FOOD_TYPES: Map<String?, ProductType> = mapOf(
    "beauty" to ProductType.BEAUTY,
    "product" to ProductType.PRODUCT,
    "petfood" to ProductType.PET_FOOD,
)
private const val MAX_BODY_BYTES = 1_048_576L

/** Generous next to a real record: the worst offenders carry a couple of dozen additives. */
private const val MAX_TAGS = 32

/** `en:milk` is seven characters. Sixty-four is room for a long tag and nothing else. */
private const val MAX_TAG_LENGTH = 64

private const val MAX_RETRY_AFTER_SECONDS = 600L

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
