package io.theficos.usuals.di

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.theficos.usuals.ui.list.ListViewModel
import io.theficos.usuals.ui.history.HistoryViewModel
import io.theficos.usuals.ui.settings.SettingsViewModel
import io.theficos.usuals.ui.settings.AccountSummary
import io.theficos.usuals.ui.account.AccountSession
import io.theficos.usuals.ui.account.AccountViewModel
import io.theficos.usuals.ui.share.ShareViewModel

/**
 * How a view model gets its dependencies.
 *
 * One entry per screen, and every one of them is a real factory rather than an
 * optional extra. That is the point of this file: a screen whose factory can be
 * left out is a screen that ships as a route which pops itself, and the app
 * spent a while in exactly that state with two working screens nobody could
 * reach. `UsualsApp` still takes each factory as a parameter so a test can
 * substitute one, but the default is the wiring, not nothing.
 *
 * Every [ServiceLocator] read happens inside an initializer, so nothing here
 * touches the database or the keystore until a screen that needs them is
 * actually opened.
 */
object UsualsViewModelFactories {

    val list: ViewModelProvider.Factory = viewModelFactory {
        initializer<ListViewModel> {
            ListViewModel(
                store = ServiceLocator.itemStore,
                parser = ServiceLocator.parser,
                speech = ServiceLocator.speechSource,
                historyStore = ServiceLocator.historyStore,
                smartStore = ServiceLocator.smartStore,
                recipeStore = ServiceLocator.recipeStore,
                scanStore = ServiceLocator.scanStore,
                productInfoStore = ServiceLocator.productInfoStore,
                rowFacts = ServiceLocator.rowFacts,
                prompts = ServiceLocator.promptMemory,
                directoryStore = ServiceLocator.listDirectory,
                shoppingTools = ServiceLocator.shoppingTools,
                reminders = ServiceLocator.reminders,
                basket = ServiceLocator.basketSource,
            )
        }
    }

    fun account(): ViewModelProvider.Factory = viewModelFactory {
        initializer<AccountViewModel> { AccountViewModel(ServiceLocator.connectionClient, AccountWiring) }
    }
    fun share(list: String): ViewModelProvider.Factory = viewModelFactory {
        initializer<ShareViewModel> { ShareViewModel(list, ServiceLocator.shareStore) }
    }
    fun history(): ViewModelProvider.Factory = viewModelFactory {
        initializer<HistoryViewModel> { HistoryViewModel(ServiceLocator.historyStore, basket = ServiceLocator.basketSource) }
    }

    /**
     * Settings, all of it real.
     *
     * The two arguments that used to be missing are the ones that make bringing
     * your own model true rather than decorative. `secrets` is a write-only
     * door onto Keystore, so the key field stores something and this layer
     * still cannot read it back. `modelProbe` builds a client from the settings
     * being edited, so *Probar conexión* tests the endpoint in front of the
     * person rather than the one that was saved an hour ago, and an endpoint
     * that answers but cannot produce JSON says so here instead of in the
     * kitchen.
     */
    fun settings(): ViewModelProvider.Factory = viewModelFactory {
        initializer<SettingsViewModel> {
            SettingsViewModel(
                store = ServiceLocator.settingsStore,
                secrets = ServiceLocator.secretStorePort,
                modelProbe = ServiceLocator.modelProbe,
                budget = ServiceLocator.modelBudget,
                info = ServiceLocator.appInfo,
                accountSummary = { ServiceLocator.connectionClient.current()?.let { AccountSummary(java.net.URI(it.origin).host) } },
            )
        }
    }
}

/** The account screen's session: the sync engine, and a sync as soon as a connection completes. */
private object AccountWiring : AccountSession {
    override suspend fun expired() = ServiceLocator.listSync.expired()
    override suspend fun name() = ServiceLocator.listSync.name()
    override suspend fun connected() { ServiceLocator.listSync.connected(); ServiceLocator.listSyncScheduler.request() }
    override suspend fun signOut(force: Boolean) = ServiceLocator.listSync.signOut(force)
}
