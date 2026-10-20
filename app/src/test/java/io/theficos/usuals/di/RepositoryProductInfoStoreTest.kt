package io.theficos.usuals.di

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.enrich.*
import io.theficos.usuals.model.Gtin
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RepositoryProductInfoStoreTest {
    private val gtin = Gtin.parse("4006381333931")!!
    @Test fun `cached details work with consent off and refresh never sends a barcode`() = runTest {
        val cache = InMemoryQualityCache(clock = { 0L })
        cache.put(QualityLookup.Found(gtin, ProductQuality(productName = "Saved")), 12)
        var calls = 0
        val reader = RepositoryProductInfoStore(cache, object : ProductEnricher {
            override suspend fun lookup(gtin: Gtin): QualityLookup { calls++; return QualityLookup.Missing(gtin) }
        }, { false }, { 99 })
        assertThat(reader.cached(gtin)?.quality?.productName).isEqualTo("Saved")
        assertThat(reader.refresh(gtin)).isEqualTo(QualityLookup.Skipped(gtin, SkipReason.OPTED_OUT))
        assertThat(calls).isEqualTo(0)
        assertThat(cache.get(gtin)?.fetchedAtEpochMillis).isEqualTo(12)
    }
    @Test fun `network failures and revoked consent retain saved details`() = runTest {
        val cache = InMemoryQualityCache(clock = { 0L })
        cache.put(QualityLookup.Found(gtin, ProductQuality(productName = "Saved")), 12)
        var enabled = true
        var revoke = false
        val reader = RepositoryProductInfoStore(cache, object : ProductEnricher {
            override suspend fun lookup(gtin: Gtin): QualityLookup {
                if (revoke) { enabled = false; return QualityLookup.Found(gtin, ProductQuality(productName = "Late")) }
                return QualityLookup.Failed(gtin, FailureReason.TIMEOUT)
            }
        }, { enabled }, { 99 })
        assertThat(reader.refresh(gtin)).isEqualTo(QualityLookup.Failed(gtin, FailureReason.TIMEOUT))
        assertThat(cache.get(gtin)?.fetchedAtEpochMillis).isEqualTo(12)
        revoke = true
        assertThat(reader.refresh(gtin)).isEqualTo(QualityLookup.Skipped(gtin, SkipReason.OPTED_OUT))
        assertThat(cache.get(gtin)?.quality?.productName).isEqualTo("Saved")
    }
}
