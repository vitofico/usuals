package io.theficos.usuals.di

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.theficos.usuals.ui.list.ListViewModel
import io.theficos.usuals.ui.history.HistoryViewModel
import io.theficos.usuals.ui.settings.SettingsViewModel

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
                member = ServiceLocator.currentMember,
                historyStore = ServiceLocator.historyStore,
                smartStore = ServiceLocator.smartStore,
                recipeStore = ServiceLocator.recipeStore,
                scanStore = ServiceLocator.scanStore,
                productInfoStore = ServiceLocator.productInfoStore,
                prompts = ServiceLocator.promptMemory,
                directoryStore = ServiceLocator.listDirectory,
                shoppingTools = ServiceLocator.shoppingTools,
                reminders = ServiceLocator.reminders,
            )
        }
    }

    fun sharing(): ViewModelProvider.Factory = viewModelFactory {
        initializer<io.theficos.usuals.ui.sharing.SharingViewModel> { io.theficos.usuals.ui.sharing.SharingViewModel(ServiceLocator.householdClient,
            connection = ServiceLocator.connectionClient,
            choices = {
                val enrolled = runCatching { ServiceLocator.householdClient.households().map { it.id }.toSet() }.getOrNull()
                ServiceLocator.listRepository.all().filterNot { it.archived }.map {
                    io.theficos.usuals.ui.sharing.SharingList(it.id, it.name, it.householdId, it.shared,
                        it.shared && (ServiceLocator.sharingCoordinator.status.value[it.id] == io.theficos.usuals.ui.list.SharingStatus.REMOVED || enrolled != null && it.householdId !in enrolled))
                }
            },
            shareList = { household, source, history -> ServiceLocator.sharingCoordinator.share(household, source, history); Unit },
            retryList = { household, list -> ServiceLocator.sharingCoordinator.retryShare(household, list) },
            synchronize = { ServiceLocator.sharingCoordinator.request() },
            statusChanges = ServiceLocator.sharingCoordinator.status,
            pendingWork = { ServiceLocator.replica.exportPending().toByteArray() },
            readFrozen = { uri, kit, secret ->
                val context = ServiceLocator.appContext
                context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { input -> ServiceLocator.householdClient.previewFrozen(input, kit, secret) } ?: throw java.io.IOException()
            },
            saveFrozen = { copy, name -> ServiceLocator.listRepository.importFrozen(copy, name, ServiceLocator.replica); Unit },
            retainList = { id, name -> ServiceLocator.listRepository.retainCopy(id, ServiceLocator.replica, name); Unit },
            removeList = { id ->
                ServiceLocator.sharingCoordinator.withTransportPaused {
                    val list = requireNotNull(ServiceLocator.listRepository.all().firstOrNull { it.id == id && it.shared })
                    require(ServiceLocator.sharingCoordinator.status.value[id] == io.theficos.usuals.ui.list.SharingStatus.REMOVED || ServiceLocator.householdClient.households().none { it.id == list.householdId })
                    if (ServiceLocator.listRepository.all().none { it.shared && it.householdId == list.householdId && it.id != id })
                        ServiceLocator.householdClient.forgetLocalHousehold(list.householdId)
                    ServiceLocator.listRepository.removeLocalCopy(id)
                }
            },
            resetKeys = {
                ServiceLocator.sharingCoordinator.withTransportPaused {
                    if (ServiceLocator.householdClient.resetUnavailable { ServiceLocator.syncVault.resetUnavailable() }) ServiceLocator.syncState.clearBlobs()
                }
            }) }
    }
    fun history(): ViewModelProvider.Factory = viewModelFactory {
        initializer<HistoryViewModel> { HistoryViewModel(ServiceLocator.historyStore) }
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
                sharingSummary = {
                    val client = ServiceLocator.householdClient
                    val households = client.households().filter { !it.removed }
                    (ServiceLocator.connectionClient.current()?.origin ?: households.firstOrNull()?.origin)?.let { origin ->
                        io.theficos.usuals.ui.settings.SharingSummary(java.net.URI(origin).host, households.sumOf { household -> client.members(household.id).sumOf { it.devices.size } })
                    }
                },
            )
        }
    }
}
