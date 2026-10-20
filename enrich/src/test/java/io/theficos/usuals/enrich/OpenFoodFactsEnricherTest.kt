package io.theficos.usuals.enrich

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.Gtin
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Open Food Facts seam, against a scripted server that is not OFF.
 *
 * Two failures this suite exists to prevent, and they are the two that would be
 * invisible in production.
 *
 * The first is a bad outcome being written down as a fact. A 500, a 429 or an
 * unreadable body means we failed to ask, and the cache is allowed to remember
 * only what OFF actually said. Cache a five minute outage as "Open Food Facts
 * has nothing on this product" and the entry looks exactly like the truth for as
 * long as it lives.
 *
 * The second is the sentinel trap. OFF writes the words `unknown` and
 * `not-applicable` where a grade would go, so a parser that keeps the raw string
 * ends up rendering the literal text "unknown" as a nutrition grade. The fixture is built
 * around that pairing: a real-looking Nutri-Score of `e` sitting next to an eco
 * grade of `unknown`, in one record, the way OFF genuinely returns them.
 *
 * Every response here is scripted locally. Nothing in this file may contact
 * world.openfoodfacts.org, both because a suite that needs the internet is not a
 * suite and because pointing CI at a volunteer-funded service is the sort of
 * thing that gets an app blocked.
 */
class OpenFoodFactsEnricherTest {

    private val server = MockWebServer()

    /** Whatever the limiter was made to wait for. Empty is the interesting value. */
    private val waits = mutableListOf<Long>()

    private val spread = Gtin.parse(SPREAD)!!
    private val absent = Gtin.parse(ABSENT)!!
    private val inStore = Gtin.parse(IN_STORE)!!

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun enricher(
        isRestricted: (Gtin) -> Boolean = { false },
        permitsPerWindow: Int = 100,
        sleep: suspend (Long) -> Unit = { millis -> waits += millis },
    ) = OpenFoodFactsEnricher(
        userAgent = USER_AGENT,
        rateLimiter = OffRateLimiter(
            permitsPerWindow = permitsPerWindow,
            windowMillis = 60_000,
            clock = { 0L },
            sleep = sleep,
        ),
        isRestricted = isRestricted,
        baseUrl = server.url("/").toString(),
    )

    /** Wraps [productFields] in the v3 envelope OFF returns for a record that exists. */
    private fun enqueueRecord(productFields: String) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"code":"$SPREAD","errors":[],"status":"success",""" +
                    """"result":{"id":"product_found","name":"Product found"},""" +
                    """"product":{$productFields},"warnings":[]}""",
            ),
        )
    }

    private suspend fun quality(gtin: Gtin = spread): ProductQuality {
        val outcome = enricher().lookup(gtin)
        assertThat(outcome).isInstanceOf(QualityLookup.Found::class.java)
        return (outcome as QualityLookup.Found).quality
    }

    // ---- what a record means -----------------------------------------------

    @Test
    fun `a real record yields every facet the app ranks and displays by`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SPREAD_BODY))

        val spreadQuality = quality()

        assertThat(spreadQuality.productName).isEqualTo("Crema de ejemplo")
        assertThat(spreadQuality.categories).containsExactly("en:spreads")
        assertThat(spreadQuality.nutriScore).isEqualTo(NutriScore.E)
        assertThat(spreadQuality.novaGroup).isEqualTo(NovaGroup.ULTRA_PROCESSED)
        // The trap, in one assertion: this record has a real nutrition grade and
        // the word "unknown" where the eco grade goes. Only one of them is data.
        assertThat(spreadQuality.ecoScore).isNull()
        assertThat(spreadQuality.allergens)
            .containsExactly("en:milk", "en:nuts", "en:soybeans").inOrder()
        assertThat(spreadQuality.additives).containsExactly("en:e322", "en:e322i").inOrder()
    }

    @Test
    fun `the words OFF writes for no grade are absences, never grades`() = runTest {
        enqueueRecord(
            """"nutriscore_grade":"not-applicable","ecoscore_grade":"unknown","nova_group":1""",
        )
        enqueueRecord(
            """"nutriscore_grade":"unknown","ecoscore_grade":"not-applicable","nova_group":1""",
        )

        val notApplicable = quality()
        val unknown = quality()

        assertThat(notApplicable.nutriScore).isNull()
        assertThat(notApplicable.ecoScore).isNull()
        assertThat(unknown.nutriScore).isNull()
        assertThat(unknown.ecoScore).isNull()
        // The rest of the record is still good. A sentinel in one field is not a
        // reason to throw away the facts next to it.
        assertThat(unknown.novaGroup).isEqualTo(NovaGroup.UNPROCESSED)
    }

    @Test
    fun `a record that states nothing is still a record, not a missing product`() = runTest {
        enqueueRecord("")

        val outcome = enricher().lookup(spread)

        assertThat(outcome).isEqualTo(QualityLookup.Found(spread, ProductQuality.EMPTY))
        assertThat((outcome as QualityLookup.Found).quality.isBlank).isTrue()
    }

    @Test
    fun `an absent tag list stays unknown while an empty one is a checked none`() = runTest {
        enqueueRecord(""""nutriscore_grade":"b","nova_group":2""")
        enqueueRecord(""""allergens_tags":[],"additives_tags":[]""")

        val silent = quality()
        val checked = quality()

        // The distinction with somebody's allergy behind it. Null is "nobody
        // filled this in"; empty is "we looked and there are none".
        assertThat(silent.nutriScore).isEqualTo(NutriScore.B)
        assertThat(silent.allergens).isNull()
        assertThat(silent.additives).isNull()
        assertThat(checked.allergens).isEmpty()
        assertThat(checked.additives).isEmpty()
    }

    @Test
    fun `tag text is capped and stripped before it can reach a prompt`() = runTest {
        val many = (1..40).joinToString(",") { "\"en:e${300 + it}\"" }
        val overlong = "en:" + "a".repeat(200)
        // A bell character and two newlines, written the way JSON writes them,
        // so the parser hands the code under test a genuinely hostile tag.
        val hostile = "en:mi\\u0007lk\\n\\n"
        enqueueRecord(
            """"allergens_tags":["$hostile","$overlong"],""" +
                """"additives_tags":[$many]""",
        )

        val hostileQuality = quality()
        val allergens = hostileQuality.allergens!!

        assertThat(allergens[0]).isEqualTo("en:milk")
        assertThat(allergens[1]).hasLength(64)
        assertThat(allergens.none { tag -> tag.any { it.isISOControl() } }).isTrue()
        assertThat(hostileQuality.additives).hasSize(32)
    }

    // ---- what a non-record means -------------------------------------------

    @Test
    fun `a confirmed absence is Missing, and it is the only negative worth keeping`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody(NOT_FOUND_BODY))

        val outcome = enricher().lookup(absent)

        assertThat(outcome).isEqualTo(QualityLookup.Missing(absent))
        assertThat(outcome.cacheable).isTrue()
    }

    @Test
    fun `an outage is never written down as an absence`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("upstream is having a day"))

        val outcome = enricher().lookup(spread)

        assertThat(outcome).isEqualTo(QualityLookup.Failed(spread, FailureReason.SERVER_ERROR))
        // The load-bearing half. Missing would outlive the outage by weeks.
        assertThat(outcome.cacheable).isFalse()
    }

    @Test
    fun `being rate limited is a fact about us, not about the product`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "7").setBody("slow down"),
        )

        val outcome = enricher().lookup(spread)

        assertThat(outcome).isEqualTo(QualityLookup.Failed(spread, FailureReason.RATE_LIMITED))
        assertThat(outcome.cacheable).isFalse()
    }

    /**
     * The 429 Open Food Facts actually sends, which is one with no Retry-After
     * on it at all.
     *
     * This handler used to do nothing without a parseable header, and the
     * module's own documentation records that OFF has never been observed to
     * send one. So the only 429 the app could receive in practice was the one it
     * ignored: it would carry straight on at fifteen a minute into a service
     * that had just said stop. Being told to slow down is itself the
     * instruction; the header only says for how long.
     */
    @Test
    fun `a 429 with no Retry-After still stands the app down`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        // A clock the sleep moves, so that a limiter which really is standing
        // down finishes standing down. Frozen, the wait loop would never end,
        // which is a property of this fixture rather than of the limiter.
        var now = 0L
        val limiter = OffRateLimiter(
            permitsPerWindow = 100,
            windowMillis = 60_000,
            clock = { now },
            sleep = { millis ->
                waits += millis
                now += millis
            },
        )

        val outcome = OpenFoodFactsEnricher(
            userAgent = USER_AGENT,
            rateLimiter = limiter,
            isRestricted = { false },
            baseUrl = server.url("/").toString(),
        ).lookup(spread)

        assertThat(outcome).isEqualTo(QualityLookup.Failed(spread, FailureReason.RATE_LIMITED))
        // The next lookup is made to wait a full window, rather than walking
        // straight into the same wall.
        limiter.withPermit { }
        assertThat(waits).containsExactly(OffRateLimiter.ONE_MINUTE_MILLIS)
    }

    /**
     * One permit, one request, rather than one permit and up to twenty.
     *
     * Left at OkHttp's default this client follows redirects, so a captive
     * portal or an intercepting network, which is the ordinary case on a phone,
     * turns a single permitted product read into a chain of requests that Open
     * Food Facts sees and the rate limiter does not. A 3xx is now an exchange
     * that produced no answer, which is what the `read` branch already said it
     * treated a followed-nowhere redirect as.
     */
    @Test
    fun `a redirect is not followed, so one permit cannot become several requests`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere").toString()),
        )

        val outcome = enricher().lookup(spread)

        assertThat(outcome).isEqualTo(QualityLookup.Failed(spread, FailureReason.MALFORMED))
        assertThat(outcome.cacheable).isFalse()
        // The second response was never enqueued, so a followed redirect would
        // have hung or failed differently. One request left the client.
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a body that is not the contract is malformed, not an empty product`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("<html><body>mantenimiento</body></html>"),
        )

        val outcome = enricher().lookup(spread)

        assertThat(outcome).isEqualTo(QualityLookup.Failed(spread, FailureReason.MALFORMED))
        assertThat(outcome.cacheable).isFalse()
    }

    @Test
    fun `a 404 that does not confirm the absence is a failure, not an absence`() = runTest {
        // What a proxy or a moved API version returns. It says nothing about
        // whether OFF has this product, so it must not be kept as if it did.
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Not Found"}"""))

        val outcome = enricher().lookup(absent)

        assertThat(outcome).isEqualTo(QualityLookup.Failed(absent, FailureReason.MALFORMED))
    }

    @Test
    fun `a hostile retry after cannot switch enrichment off for the session`() {
        assertThat(offRetryAfterMillis("7")).isEqualTo(7_000)
        assertThat(offRetryAfterMillis(" 7 ")).isEqualTo(7_000)
        assertThat(offRetryAfterMillis("999999999")).isEqualTo(600_000)
        assertThat(offRetryAfterMillis("Wed, 21 Oct 2026 07:28:00 GMT")).isNull()
        assertThat(offRetryAfterMillis("-5")).isNull()
        assertThat(offRetryAfterMillis(null)).isNull()
    }

    @Test fun `localized names and rich details keep source values`() = runTest {
        enqueueRecord(""""product_name":"Default name","product_name_it":"Latte di prova",
            "brands":"Example","quantity":"6 x 200 ml","ingredients_text_it":"Latte, cacao",
            "traces_tags":["en:nuts"],"last_modified_t":1700000000""")
        val client = OpenFoodFactsEnricher(USER_AGENT, OffRateLimiter(100, 60000),
            baseUrl = server.url("/").toString(), preferredLanguage = { "it" })
        val found = client.lookup(spread) as QualityLookup.Found
        assertThat(found.quality.productName).isEqualTo("Latte di prova")
        assertThat(found.quality.brands).isEqualTo("Example")
        assertThat(found.quality.packageSize).isEqualTo("6 x 200 ml")
        assertThat(found.quality.ingredients).isEqualTo("Latte, cacao")
        assertThat(found.quality.traces).containsExactly("en:nuts")
        assertThat(found.quality.sourceModifiedAtEpochSeconds).isEqualTo(1700000000L)
        val request = server.takeRequest().requestUrl!!
        assertThat(request.queryParameter("lc")).isEqualTo("it")
        assertThat(request.queryParameter("fields")).contains("product_name_it")
    }

    @Test fun `blank product names fall back to generic names but never brands`() = runTest {
        enqueueRecord(""""product_name":"  ","generic_name_en":"Cocoa spread","brands":"Example"""")
        assertThat(quality().productName).isEqualTo("Cocoa spread")
        enqueueRecord(""""brands":"Example"""")
        assertThat(quality().productName).isNull()
    }

    @Test fun `mismatched product codes and unconfirmed failures are not cached`() = runTest {
        enqueueRecord(""""code":"$ABSENT","product_name":"Wrong product"""")
        assertThat(enricher().lookup(spread)).isEqualTo(QualityLookup.Failed(spread, FailureReason.MALFORMED))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"status":"failure"}"""))
        assertThat(enricher().lookup(spread)).isEqualTo(QualityLookup.Failed(spread, FailureReason.MALFORMED))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"$ABSENT","result":{"id":"product_not_found"}}"""))
        assertThat(enricher().lookup(spread)).isEqualTo(QualityLookup.Failed(spread, FailureReason.MALFORMED))
    }

    @Test fun `oversized known and chunked bodies are rejected`() = runTest {
        val body = """{"product":{"product_name":"""" + "x".repeat(1_048_576) + """"}}"""
        server.enqueue(MockResponse().setBody(body))
        assertThat(enricher().lookup(spread)).isEqualTo(QualityLookup.Failed(spread, FailureReason.MALFORMED))
        server.enqueue(MockResponse().setChunkedBody(body, 8192))
        assertThat(enricher().lookup(spread)).isEqualTo(QualityLookup.Failed(spread, FailureReason.MALFORMED))
    }

    @Test fun `missing empty and malformed traces remain distinct`() = runTest {
        enqueueRecord(""""traces_tags":[]""")
        assertThat(quality().traces).isEmpty()
        enqueueRecord(""""traces_tags":[null,123]""")
        assertThat(quality().traces).isNull()
        enqueueRecord(""""ingredients_text":42""")
        assertThat(quality().traces).isNull()
    }

    @Test fun `revoking consent while a response arrives discards the result`() = runTest {
        enqueueRecord(""""product_name":"Late name"""")
        var enabled = true
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request()).also { enabled = false }
        }.build()
        val client = OpenFoodFactsEnricher(USER_AGENT, OffRateLimiter(100, 60000),
            baseUrl = server.url("/").toString(), httpClient = http, allowRequest = { enabled })
        assertThat(client.lookup(spread)).isEqualTo(QualityLookup.Skipped(spread, SkipReason.OPTED_OUT))
        assertThat(server.requestCount).isEqualTo(1)
    }

    // ---- how we ask --------------------------------------------------------

    @Test
    fun `no request leaves without the contact user agent OFF asks clients for`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SPREAD_BODY))

        enricher().lookup(spread)

        assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo(USER_AGENT)
    }

    @Test
    fun `one lookup is one product read, naming the fields it was verified with`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SPREAD_BODY))

        enricher().lookup(spread)

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.requestUrl!!.encodedPath).isEqualTo("/api/v3/product/$SPREAD.json")
        // Spelled out rather than compared against the constant, because the
        // field list is a contract observed against the live service, and a
        // change to it should have to be made twice, deliberately.
        assertThat(request.requestUrl!!.queryParameter("fields")).isEqualTo(
            "code,product_name,categories_tags,nutriscore_grade," +
                "nova_group,ecoscore_grade,allergens_tags,additives_tags,generic_name,brands,quantity," +
                "ingredients_text,traces_tags,last_modified_t,product_name_en,generic_name_en,ingredients_text_en",
        )
        // No search, no second page, no crawl.
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a restricted circulation code costs neither a request nor a permit`() = runTest {
        // Proving "no permit was taken" needs a limiter with nothing left to
        // give, and a budget of zero cannot express that: OffRateLimiter refuses
        // one in its constructor, correctly, because a limiter that can never
        // grant is a deadlock rather than a configuration. So the budget is one
        // permit and the first lookup spends it. After that the limiter can only
        // answer by waiting, and the fake sleep turns any wait into a failure.
        // A restricted code that quietly asked for a permit would hit that.
        var restricted = false
        val enricher = enricher(
            isRestricted = { restricted },
            permitsPerWindow = 1,
            sleep = { millis -> throw AssertionError("waited ${millis}ms for an unneeded permit") },
        )
        enqueueRecord(""""code":"$SPREAD","nutriscore_grade":"e"""")
        enricher.lookup(spread)
        assertThat(server.requestCount).isEqualTo(1)

        restricted = true
        val outcome = enricher.lookup(inStore)

        assertThat(outcome)
            .isEqualTo(QualityLookup.Skipped(inStore, SkipReason.RESTRICTED_CIRCULATION))
        assertThat(outcome.cacheable).isFalse()
        // Still one: the restricted lookup added nothing.
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(waits).isEmpty()
    }

    private companion object {

        /** Obviously fake, and in the shape OFF requires: name, version, contact. */
        const val USER_AGENT = "UsualsTest/0.0 (pruebas@example.invalid)"

        const val SPREAD = "8471100000009"
        const val ABSENT = "9642138750214"

        /** A valid EAN-13 in the GS1 in-store range, which OFF cannot resolve. */
        const val IN_STORE = "2000000000008"

        /**
         * A record in the exact shape OFF returns, for a product that does not exist.
         *
         * Invented rather than captured, deliberately. A real OFF response is an
         * extract of their database, which is ODbL, and committing one would
         * attach share-alike obligations to this repository for the sake of a
         * test fixture. What actually needs to be faithful is the *shape*, and
         * the shape is reproduced exactly: the v3 envelope, the sparse product
         * object, and above all the sentinel trap of a real nutrition grade
         * sitting beside the literal word "unknown" where an eco grade goes.
         *
         * The tag vocabularies are not invented, because they are not OFF's data:
         * `en:milk` is their API's vocabulary and `en:e322` is an E number, in the
         * same way a field name is not data.
         *
         * The barcode is a structurally valid EAN-13 with a correct check digit
         * that belongs to no product. Verified against the live API before it was
         * chosen, and see the note on [ABSENT] for why that verification is worth
         * less than it looks.
         */
        val SPREAD_BODY = """
            {"code":"8471100000009","errors":[],"status":"success",
             "result":{"id":"product_found","name":"Product found"},
             "product":{"code":"8471100000009","product_name":"Crema de ejemplo",
              "categories_tags":["en:spreads"],
              "nutriscore_grade":"e","nova_group":4,"ecoscore_grade":"unknown",
              "allergens_tags":["en:milk","en:nuts","en:soybeans"],
              "additives_tags":["en:e322","en:e322i"]},"warnings":[]}
        """.trimIndent()

        /**
         * The miss envelope, which is a 404 and says so in the body as well.
         *
         * A warning for whoever maintains this: an improbable barcode is not an
         * absent one. Both `8412345678905` and `9999999999994` were probed and
         * both resolve to real records, because OFF is crowd-sourced and junk
         * codes get filled in. A test that assumes a made-up code returns a miss
         * passes today and fails the week somebody adds a product under it. This
         * fixture is scripted locally for exactly that reason.
         */
        val NOT_FOUND_BODY = """
            {"code":"9642138750214","errors":[],"product":null,
             "result":{"id":"product_not_found"},"status":"failure","warnings":[]}
        """.trimIndent()
    }
}
