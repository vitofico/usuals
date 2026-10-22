package io.theficos.usuals.ui.product

import io.theficos.usuals.enrich.ProductQuality
import io.theficos.usuals.model.Gtin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * This phone's own choices about product facts on list rows. They describe one
 * person, and [avoid] is health data, so they never sync to a shared list, go
 * into an export or leave the phone in a backup.
 */
@Serializable
data class RowFactsChoices(
    val showGrades: Boolean = true,
    /** Open Food Facts allergen tags (`en:milk`) this person wants flagged. */
    val avoid: Set<String> = emptySet(),
) {
    /** True when rows need anything from the product cache at all. */
    val active: Boolean get() = showGrades || avoid.isNotEmpty()
}

/** [RowFactsChoices] kept as one JSON value, the way `ShoppingToolsStore` keeps its own. */
class RowFactsStore(read: () -> String? = { null }, private val write: (String) -> Unit = {}) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // An unreadable file falls back to the defaults rather than taking the list down.
    private val mutable = MutableStateFlow(runCatching { read()?.let { json.decodeFromString<RowFactsChoices>(it) } }.getOrNull() ?: RowFactsChoices())
    val state = mutable.asStateFlow()

    @Synchronized private fun change(update: (RowFactsChoices) -> RowFactsChoices) {
        val next = update(mutable.value)
        write(json.encodeToString(next))
        mutable.value = next
    }

    fun showGrades(enabled: Boolean) = change { it.copy(showGrades = enabled) }
    fun avoid(tag: String, avoided: Boolean) = change { it.copy(avoid = if (avoided) it.avoid + tag else it.avoid - tag) }
}

/**
 * What list rows may know about products: the local cache and nothing else. A
 * row never sends a barcode anywhere; an uncached barcode stays bare until
 * someone opens its product sheet.
 */
interface RowFactsSource {
    /** Row facts follow the Open Food Facts opt-in: with lookup off, rows show none. */
    val lookupEnabled: Flow<Boolean>
    val choices: Flow<RowFactsChoices>
    /** The stated records for [gtins], again whenever the cache changes. */
    fun cached(gtins: Set<Gtin>): Flow<Map<Gtin, ProductQuality>>
}

object NoRowFacts : RowFactsSource {
    override val lookupEnabled: Flow<Boolean> = flowOf(false)
    override val choices: Flow<RowFactsChoices> = flowOf(RowFactsChoices())
    override fun cached(gtins: Set<Gtin>): Flow<Map<Gtin, ProductQuality>> = flowOf(emptyMap())
}
