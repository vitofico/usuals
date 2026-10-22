package io.theficos.usuals.di

import android.content.Context
import io.theficos.usuals.R
import io.theficos.usuals.auth.KeystoreSecretStore
import io.theficos.usuals.auth.ProviderSettingsStore
import io.theficos.usuals.data.local.UsualsDatabase
import io.theficos.usuals.data.local.ItemRepository
import io.theficos.usuals.data.local.HistoryRepository
import io.theficos.usuals.data.local.RoomQualityCache
import io.theficos.usuals.enrich.*
import io.theficos.usuals.smart.BarcodeCategories
import io.theficos.usuals.ui.scan.ScanStore
import io.theficos.usuals.ui.history.HistoryStore
import io.theficos.usuals.ui.history.PromptMemory
import io.theficos.usuals.llm.LlmClient
import io.theficos.usuals.llm.ModelBudget
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.parse.InputLanguages
import io.theficos.usuals.smart.CategoryClassifier
import io.theficos.usuals.smart.HistoryRanker
import io.theficos.usuals.smart.RepeatPlanner
import io.theficos.usuals.smart.IntervalModel
import io.theficos.usuals.smart.SuggestionEngine
import io.theficos.usuals.smart.QuantityHints
import io.theficos.usuals.smart.RepeatOffers
import io.theficos.usuals.smart.SimilarItems
import io.theficos.usuals.ui.list.SmartStore
import io.theficos.usuals.parse.UtteranceParser
import io.theficos.usuals.parse.IngredientLineParser
import io.theficos.usuals.recipe.RecipeJsonLd
import io.theficos.usuals.recipe.RecipeFetcher
import io.theficos.usuals.recipe.IngredientReviewPlanner
import io.theficos.usuals.ui.recipe.RecipeStore
import io.theficos.usuals.ui.list.ItemStore
import io.theficos.usuals.ui.list.SpeechSource
import io.theficos.usuals.ui.settings.AppInfo
import io.theficos.usuals.ui.settings.ModelProbe
import io.theficos.usuals.ui.settings.SecretStorePort
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * The object graph, by hand.
 *
 * No Hilt, and not because Hilt is bad. An app with one graph, no build
 * variants that swap implementations, and a core that is already testable
 * without Android does not have the problem Hilt solves, and it would pay for
 * it in an annotation processor on every build and in generated code that is
 * hard to read when the wiring is wrong. Everything here is a `by resettable`
 * field on an object, which is a graph you can read top to bottom in a minute.
 *
 * [init] is called once from `MainActivity.onCreate`. Every field is built on
 * first read, so nothing exists until something asks for it: never opening
 * settings never touches Keystore. [reset] empties them again, which is for
 * tests and is explained where it is declared.
 *
 * This file is the one place `:app` names types from `:data:local`, `:llm` and
 * `:auth`. The ports ([ItemStore] and [SpeechSource]) exist so a rename in a
 * module lands here and never reaches a screen or its view model.
 */
object ServiceLocator {

    private var application: Context? = null

    fun init(context: Context) {
        if (application == null) application = context.applicationContext
    }

    val appContext: Context get() = context

    private val context: Context
        get() = requireNotNull(application) { "ServiceLocator.init(context) has not been called" }

    // The fields themselves ----------------------------------------------

    /**
     * Every field below is built on first read and then held, exactly as
     * `by lazy` would hold it, with one addition: [reset] drops the lot so the
     * next read builds a fresh graph.
     *
     * That addition is not generality for its own sake. Robolectric gives each
     * test method its own temporary file system, so a process singleton whose
     * database could never be rebuilt bound a Room connection to a directory
     * the next test no longer had, and the failure surfaced in whichever test
     * happened to be running when it landed.
     */
    private val resettables = mutableListOf<ResettableField<*>>()

    private fun <T> resettable(factory: () -> T): ResettableField<T> =
        ResettableField(factory).also { resettables += it }

    /**
     * Drops every field, closes the database if one was ever opened, and
     * forgets the context, so that the next [init] starts from nothing.
     *
     * For tests, and only for tests. Nothing in the app calls it: a process has
     * one graph for its whole life.
     */
    fun reset() {
        // Through the delegate rather than through [database], because reading
        // the property would open a connection in order to close it.
        listSyncSchedulerField.peek()?.close()
        widgetUpdaterField.peek()?.close()
        databaseField.peek()?.close()
        resettables.forEach { it.clear() }
        application = null
    }

    // Storage ------------------------------------------------------------

    private val databaseField = resettable { UsualsDatabase.build(context) }

    val database: UsualsDatabase by databaseField

    val listRepository by resettable { io.theficos.usuals.data.local.ListRepository(database) }
    val listDirectory by resettable { RepositoryListDirectory(listRepository, replica, promptMemory::migrateScope, listSync) }

    val reducer by resettable { io.theficos.usuals.sync.Reducer(io.theficos.usuals.model.NORMALIZER_VERSION) }
    val replica by resettable { io.theficos.usuals.data.local.LocalReplica(database, reducer) }
    val syncVault by resettable { io.theficos.usuals.auth.SyncVault(context) }
    val syncState by resettable { VaultSyncStateStore(context, syncVault).also { it.prune() } }
    val relayTransport by resettable { OkHttpRelayTransport(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) }
    val connectionClient by resettable { io.theficos.usuals.sync.ConnectionClient(syncState, relayTransport) }

    /** The account's lists on the server; see [ListSync]. */
    val listSync by resettable { ListSync(listRepository, replica, HttpListsService(relayTransport, connectionClient::current), connectionClient, syncState, context.getString(R.string.household_fallback_name)) }
    private val listSyncSchedulerField = resettable { ListSyncScheduler(context, listSync, database.syncDao().observeOutgoing()) }
    val listSyncScheduler by listSyncSchedulerField
    val shareStore by resettable { AccountShareStore(listRepository, listSync) }
    val itemRepository: ItemRepository by resettable { ItemRepository(database, replica = replica) }

    val historyRepository: HistoryRepository by resettable { HistoryRepository(database, itemRepository, replica = replica) }
    val reminders by resettable { preferenceReminders(context) }
    val shoppingTools by resettable { preferenceShoppingTools(context) }
    val historyStore: HistoryStore by resettable { RepositoryHistoryStore(historyRepository, parser, smartStore, shoppingTools) }
    val promptMemory: PreferencePromptMemory by resettable { PreferencePromptMemory(context) }

    /** What the list screen sees. See [RepositoryItemStore]. */
    val itemStore: ItemStore by resettable { RepositoryItemStore(itemRepository) }

    /** Where the endpoint, the model name and the "stays on my network" flag live. */
    val providerSettingsStore: ProviderSettingsStore by resettable {
        ProviderSettingsStore(context)
    }

    /** What the settings screen changes. */
    val settingsStore: PreferenceSettingsStore by resettable {
        PreferenceSettingsStore(context, providerSettingsStore)
    }

    /**
     * The version block on the settings screen.
     *
     * Read from the installed package rather than from `BuildConfig`, because
     * the build does not generate one and because the package manager is the
     * only source that cannot disagree with what the phone actually has.
     */
    val appInfo: AppInfo by resettable { readAppInfo() }

    @Suppress("DEPRECATION")
    private fun readAppInfo(): AppInfo {
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull() ?: return AppInfo()
        return AppInfo(
            versionName = installed.versionName.orEmpty(),
            // `longVersionCode` needs API 28 and this app runs from 26.
            versionCode = installed.versionCode,
        )
    }

    // Capture ------------------------------------------------------------

    val parser: UtteranceParser by resettable { LocalUtteranceParser() }
    val categoryClassifier: CategoryClassifier by resettable { CategoryClassifier(InputLanguages.available) }
    val historyRanker: HistoryRanker by resettable { HistoryRanker() }
    val repeatPlanner: RepeatPlanner by resettable { RepeatPlanner() }
    val intervalModel: IntervalModel by resettable { IntervalModel() }
    val suggestionEngine: SuggestionEngine by resettable { SuggestionEngine(repeatPlanner, intervalModel, historyRanker) }
    val quantityHints: QuantityHints by resettable { QuantityHints() }
    val repeatOffers: RepeatOffers by resettable { RepeatOffers() }
    val similarItems: SimilarItems by resettable { SimilarItems(historyRanker) }
    val smartStore: SmartStore by resettable {
        RepositorySmartStore(itemRepository, historyRepository, categoryClassifier, historyRanker, repeatPlanner, suggestionEngine, quantityHints, repeatOffers, similarItems)
    }

    val productRateLimiter: OffRateLimiter by resettable { OffRateLimiter(OffRateLimiter.PRODUCT_READS_PER_MINUTE, OffRateLimiter.ONE_MINUTE_MILLIS) }
    val qualityCache: RoomQualityCache by resettable { RoomQualityCache(database.qualityDao()) }
    val offClient: ProductEnricher by resettable {
        OpenFoodFactsEnricher("Usuals/${context.packageManager.getPackageInfo(context.packageName, 0).versionName} (+https://github.com/vitofico/usuals)", productRateLimiter,
            allowRequest = settingsStore::scanLookupEnabled,
            preferredLanguage = { context.resources.configuration.locales[0].language })
    }
    val offEnricher: ProductEnricher by resettable { CachingEnricher(offClient, qualityCache, System::currentTimeMillis) }
    val productInfoStore: io.theficos.usuals.ui.product.ProductInfoStore by resettable {
        RepositoryProductInfoStore(qualityCache, offClient, settingsStore::scanLookupEnabled, System::currentTimeMillis)
    }
    /** Reads the local quality cache only: the overview never asks Open Food Facts for anything. */
    val basketSource: io.theficos.usuals.ui.basket.BasketSource by resettable {
        io.theficos.usuals.ui.basket.BasketSource(categoryClassifier, qualityCache::getAll) {
            settingsStore.scanLookupEnabled() && settingsStore.basketOverviewEnabled()
        }
    }
    /** This phone's choices about product facts on list rows; see [preferenceRowFacts]. */
    val rowFactsStore: io.theficos.usuals.ui.product.RowFactsStore by resettable { preferenceRowFacts(context) }
    val rowFacts: io.theficos.usuals.ui.product.RowFactsSource by resettable { RepositoryRowFacts(qualityCache, settingsStore.scanLookup, rowFactsStore) }
    val barcodeCategories: BarcodeCategories by resettable { BarcodeCategories(categoryClassifier) }
    val scanStore: ScanStore by resettable {
        RepositoryScanStore(itemRepository, historyRepository, smartStore, settingsStore::scanLookupEnabled, { offEnricher }, barcodeCategories)
    }
    val themePreferences: ThemePreferences by resettable { ThemePreferences(context) }
    val tutorialPreferences: TutorialPreferences by resettable { TutorialPreferences(context) }
    private val widgetUpdaterField = resettable { WidgetUpdater(context, itemRepository, themePreferences) }
    val widgetUpdater: WidgetUpdater by widgetUpdaterField

    val ingredientParser: IngredientLineParser by resettable { IngredientLineParser() }
    val recipeExtractor: RecipeJsonLd by resettable { RecipeJsonLd() }
    val recipeFetcher: RecipeFetcher by resettable { RecipeFetcher(recipeExtractor) }
    val recipePlanner: IngredientReviewPlanner by resettable {
        IngredientReviewPlanner(ingredientParser, InputLanguages.available.flatMap { it.pantryStaples })
    }
    val recipeStore: RecipeStore by resettable {
        RepositoryRecipeStore(recipeFetcher, recipePlanner, itemRepository, historyRepository, { modelAccess()?.client }, modelBudget,
            markdown = markdownIngredients, files = recipeImportFiles, access = ::modelAccess)
    }
    val markdownIngredients by resettable { io.theficos.usuals.recipe.MarkdownIngredients() }
    val recipeImportFiles by resettable { AndroidRecipeImportFiles(context) }

    val speechSource: SpeechSource by resettable { AndroidSpeechSource(context) }

    // The model ----------------------------------------------------------

    val modelUsage: PreferenceModelUsage by resettable { PreferenceModelUsage(context) }
    val modelBudget: ModelBudget by resettable {
        ModelBudget(settingsStore.dailyCallCeiling(), loadUsage = modelUsage::read, saveUsage = modelUsage::write)
    }

    /**
     * Saved provider settings plus the stored key, as a client or as nothing.
     *
     * Both halves are functions so that neither is read until a client is
     * actually being built. A keystore that refuses reads as "no key" rather
     * than as a throw: null is already a supported answer, and every feature
     * that can use a model works without one.
     */
    val modelClients: ModelClients by resettable {
        ModelClients(
            settings = { providerSettingsStore.load() },
            apiKey = { runCatching { secretStore.modelKey() }.getOrNull() },
        )
    }

    /** The configured model, or null when no provider is configured. */
    private fun modelAccess(): RecipeModelAccess? = connectedModelAccess(connectionClient::current, modelClients::current) { saved ->
        io.theficos.usuals.llm.ServerLlmClient(connectionClient, saved)
    }

    /** *Test connection*, against the endpoint currently on the settings screen. */
    val modelProbe: ModelProbe by resettable {
        OpenAiModelProbe(apiKey = { secretStore.modelKey() }, budget = modelBudget)
    }

    // Secrets ------------------------------------------------------------

    /** The model key, in Keystore. It never leaves the device and is never logged. */
    val secretStore: KeystoreSecretStore by resettable { KeystoreSecretStore(context) }

    /**
     * What the settings screen writes the key through.
     *
     * Deliberately narrower than [secretStore]: it can store and forget a key
     * and it cannot read one back. The store arrives as a function so that
     * opening Keystore waits until the screen actually asks about a key.
     */
    val secretStorePort: SecretStorePort by resettable { KeystoreSecretStorePort { secretStore } }
}

/**
 * A field that behaves like `by lazy` and can be emptied again.
 *
 * A sentinel rather than a null check, so that a factory which legitimately
 * produces null is still run once rather than on every read. Reads are
 * synchronised per field, which is what `by lazy` does by default and what the
 * previous version of this graph relied on.
 */
private class ResettableField<T>(private val factory: () -> T) : ReadOnlyProperty<Any?, T> {

    private var held: Any? = Unset

    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = synchronized(this) {
        if (held === Unset) held = factory()
        held as T
    }

    /** What is held, or null if nothing has asked for it yet. Never builds. */
    @Suppress("UNCHECKED_CAST")
    fun peek(): T? = synchronized(this) { if (held === Unset) null else held as T }

    fun clear() = synchronized(this) { held = Unset }
}

private object Unset
