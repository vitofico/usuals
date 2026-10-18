package io.theficos.usuals.enrich

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.Gtin
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class QueuedOptOutTest {
    @Test fun `revoking lookup blocks a request waiting for its shared permit`() = runTest {
        MockWebServer().use { server ->
            server.start()
            var enabled = true
            val limiter = OffRateLimiter(1, 60_000, { testScheduler.currentTime })
            val client = OpenFoodFactsEnricher("Usuals test", limiter, baseUrl = server.url("/").toString(),
                isRestricted = { false }, allowRequest = { enabled })
            server.enqueue(MockResponse().setBody("""{"product":{"product_name":"Milk","categories_tags":["en:milks"]}}"""))
            val first = Gtin.parse("4006381333931")!!
            val next = Gtin.parse("3017620422003")!!
            assertThat(client.lookup(first)).isInstanceOf(QualityLookup.Found::class.java)
            val queued = async { client.lookup(next) }
            runCurrent()
            assertThat(queued.isCompleted).isFalse()
            enabled = false
            advanceTimeBy(60_000); runCurrent()
            assertThat(queued.await()).isEqualTo(QualityLookup.Skipped(next, SkipReason.OPTED_OUT))
            assertThat(server.requestCount).isEqualTo(1)
        }
    }
}
