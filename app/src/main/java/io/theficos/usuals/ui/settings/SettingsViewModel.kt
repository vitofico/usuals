package io.theficos.usuals.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.theficos.usuals.llm.ModelBudget
import io.theficos.usuals.llm.ModelCapabilities
import io.theficos.usuals.auth.ProviderKind
import io.theficos.usuals.auth.ProviderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val info: AppInfo = AppInfo(),
    /** What the keystore last said about the model key, including "it would not say". */
    val apiKey: KeyState = KeyState.ABSENT,
    /**
     * What is currently typed in the key field.
     *
     * It lives here only until it is saved and is cleared the instant it is.
     * Nothing else in the app ever sees it, and it is never written anywhere
     * except the keystore.
     */
    val apiKeyDraft: String = "",
    /** Calls the live budget has counted today. Read from the budget, not stored here. */
    val callsToday: Int = 0,
    val modelProbe: ProbeState<ModelCapabilities> = ProbeState.Idle,
    val loaded: Boolean = false,
    val serverOrigin: String? = null,
)

/**
 * Settings, and the screen where invariant 8 is discharged.
 *
 * The privacy block is not a link to a document, it is rendered content: the
 * claims this app makes and the edge of each one live on the screen that makes
 * them. Nothing in this class computes that block, but the screen it drives
 * cannot quietly lose it.
 *
 * Every constructor parameter has a default, and the defaults are the offline
 * ones, so the screen is navigable and readable before anything is wired and a
 * missing factory shows a settings page rather than crashing a route. Real
 * wiring is one factory: see [factory].
 */
class SettingsViewModel(
    private val store: SettingsStore = InMemorySettingsStore(),
    private val secrets: SecretStorePort = NoSecretStore,
    private val modelProbe: ModelProbe = UnreachableModelProbe,
    private val budget: ModelBudget = ModelBudget(),
    private val info: AppInfo = AppInfo(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val currentServerOrigin: () -> String? = { null },
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(
            info = info,
        ),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private val saveLock = Mutex()
    private var settingsRevision = 0L

    fun loadIfNeeded() {
        scope.launch {
            val origin = withContext(worker) { runCatching { currentServerOrigin() }.getOrNull() }
            _state.update { it.copy(serverOrigin = origin) }
        }
        if (_state.value.loaded) return
        scope.launch {
            val loaded = withContext(worker) { store.load() }
            val key = withContext(worker) {
                keyStateOf { if (secrets.hasModelKey()) KeyState.PRESENT else KeyState.ABSENT }
            }
            val used = withContext(worker) { budget.usedToday() }
            _state.update {
                it.copy(settings = loaded, apiKey = key, callsToday = used, loaded = true)
            }
        }
    }

    // Modelo ----------------------------------------------------------------

    fun setProviderKind(kind: ProviderKind) {
        if (kind == _state.value.settings.provider.kind) return
        updateSettings { it.copy(provider = ProviderDefaults.forKind(kind)) }
        clearModelProbe()
    }

    fun setBaseUrl(baseUrl: String) {
        updateSettings { it.copy(provider = it.provider.copy(baseUrl = baseUrl)) }
        clearModelProbe()
    }

    fun setModelName(model: String) {
        updateSettings { it.copy(provider = it.provider.copy(model = model)) }
        clearModelProbe()
    }

    /**
     * Whether traffic stays on the person's own network.
     *
     * Stored rather than derived, because an OpenAI-compatible endpoint on a
     * machine in the hallway is local and an Ollama behind a tunnel is not, and
     * only the person knows which. The privacy line on this screen reads from
     * it, so getting it wrong would make the app claim something untrue.
     */
    fun setIsLocal(isLocal: Boolean) {
        updateSettings { it.copy(provider = it.provider.copy(isLocal = isLocal)) }
        clearModelProbe()
    }

    fun setApiKeyDraft(draft: String) {
        _state.update { it.copy(apiKeyDraft = draft) }
    }

    fun saveApiKey() {
        val draft = _state.value.apiKeyDraft
        if (draft.isBlank()) return
        scope.launch {
            val outcome = withContext(worker) {
                keyStateOf {
                    secrets.putModelKey(draft)
                    KeyState.PRESENT
                }
            }
            // Drop the draft either way, and that is deliberate on the failing
            // branch too. A key that was not stored is still a key sitting in a
            // StateFlow that outlives the screen, and invariant 5 cares about
            // where the plaintext is, not about whether the write succeeded.
            // The cost is that a failed save loses what was typed, which the
            // notice on screen says plainly, and re-pasting is cheap next to
            // holding a secret nobody asked us to hold.
            _state.update { it.copy(apiKeyDraft = "", apiKey = outcome) }
        }
    }

    fun clearApiKey() {
        scope.launch {
            val outcome = withContext(worker) {
                keyStateOf {
                    secrets.clearModelKey()
                    KeyState.ABSENT
                }
            }
            // Note what this does not do on failure: report the key as gone. A
            // clear that threw may have removed nothing, and telling someone
            // their key is deleted when it might still be there is the one
            // wrong answer available here.
            _state.update { it.copy(apiKeyDraft = "", apiKey = outcome) }
        }
    }

    /**
     * Runs a keystore call and turns a refusal into [KeyState.UNAVAILABLE].
     *
     * Every path into the keystore goes through here, because the failure mode
     * being defended against is not an unlikely exception, it is an exception
     * thrown inside a coroutine this class launched. There is nothing above it
     * to catch it, so it reaches the thread's handler and takes the process
     * down, from a settings screen, with no message a person could act on.
     *
     * Cancellation is rethrown rather than swallowed: a cancelled load is not a
     * broken keystore, and reporting it as one would leave a stale notice on a
     * screen that is already going away.
     */
    private inline fun keyStateOf(read: () -> KeyState): KeyState = try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unavailable: Exception) {
        // Deliberately not carried into the state. The message here says things
        // like "AndroidKeyStore not found", which means nothing to the person
        // holding the phone, and keeping keystore internals out of
        // SettingsUiState keeps them out of every toString that state reaches.
        KeyState.UNAVAILABLE
    }

    fun probeModel() {
        val settings = _state.value.settings.provider
        scope.launch {
            _state.update { it.copy(modelProbe = ProbeState.Running) }
            val next = try {
                ProbeState.Done(withContext(worker) { modelProbe.probe(settings) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ProbeState.Broken
            }
            _state.update { it.copy(modelProbe = next, callsToday = budget.usedToday()) }
        }
    }

    // Presupuesto -----------------------------------------------------------

    fun setScanLookup(enabled: Boolean) {
        store.setScanLookupEnabled(enabled)
        updateSettings { it.copy(scanLookupEnabled = enabled) }
    }

    fun setDailyCallCeiling(ceiling: Int) {
        val next = ceiling.coerceAtLeast(0)
        budget.changeCeiling(next)
        updateSettings { it.copy(dailyCallCeiling = next) }
    }

    override fun onCleared() {
        scope.cancel()
    }

    private fun clearModelProbe() {
        _state.update { it.copy(modelProbe = ProbeState.Idle) }
    }

    /**
     * Every edit writes through. There is no Save button, because a settings
     * page that can be left in an unsaved state is a settings page that lies
     * about what the app is configured to do.
     */
    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value.settings)
        val revision = ++settingsRevision
        _state.update { it.copy(settings = next) }
        scope.launch {
            saveLock.withLock {
                if (revision == settingsRevision) withContext(worker) { store.save(next) }
            }
        }
    }

    companion object {
        /**
         * The wiring, in one place. Pass this as
         * `UsualsApp(settingsViewModelFactory = ...)`.
         */
        fun factory(
            store: SettingsStore,
            secrets: SecretStorePort,
            modelProbe: ModelProbe,
            budget: ModelBudget,
            info: AppInfo,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer<SettingsViewModel> {
                SettingsViewModel(
                    store = store,
                    secrets = secrets,
                    modelProbe = modelProbe,
                    budget = budget,
                    info = info,
                )
            }
        }
    }
}

/** Settings with nowhere to go. Keeps the screen readable before the wiring exists. */
private class InMemorySettingsStore : SettingsStore {
    private var held = AppSettings()

    override suspend fun load(): AppSettings = held

    override suspend fun save(settings: AppSettings) {
        held = settings
    }
}

/** No keystore, so no key. "No key configured" is a normal state to render, not an error. */
private object NoSecretStore : SecretStorePort {
    override suspend fun putModelKey(key: String) = Unit

    override suspend fun clearModelKey() = Unit

    override suspend fun hasModelKey(): Boolean = false
}

/**
 * A probe with nothing to probe.
 *
 * It reports an endpoint that did not answer, which is what the screen would
 * show anyway, rather than inventing a capability nobody measured.
 */
private object UnreachableModelProbe : ModelProbe {
    override suspend fun probe(settings: ProviderSettings): ModelCapabilities = ModelCapabilities(
        reachable = false,
        supportsStructuredOutput = false,
        model = null,
    )
}
