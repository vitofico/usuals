package io.theficos.usuals.di

import android.content.Context
import io.theficos.usuals.data.local.RoomQualityCache
import io.theficos.usuals.ui.product.RowFactsSource
import io.theficos.usuals.ui.product.RowFactsStore
import kotlinx.coroutines.flow.Flow

/**
 * Its own preference file, so what it holds is easy to reason about: shared
 * preferences are excluded from cloud backup and device transfer by the backup
 * rules, and no export reads this file.
 */
fun preferenceRowFacts(context: Context): RowFactsStore {
    val prefs = context.getSharedPreferences("row_facts", Context.MODE_PRIVATE)
    return RowFactsStore(read = { prefs.getString("state", null) }, write = {
        check(prefs.edit().putString("state", it).commit())
    })
}

class RepositoryRowFacts(
    private val cache: RoomQualityCache,
    override val lookupEnabled: Flow<Boolean>,
    store: RowFactsStore,
) : RowFactsSource {
    override val choices = store.state
    override fun cached(gtins: Set<io.theficos.usuals.model.Gtin>) = cache.observe(gtins)
}
