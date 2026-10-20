package io.theficos.usuals

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.theficos.usuals.di.UsualsViewModelFactories
import io.theficos.usuals.ui.history.HistoryScreen
import io.theficos.usuals.ui.history.HistoryViewModel
import io.theficos.usuals.ui.list.ListScreen
import io.theficos.usuals.ui.list.ListViewModel
import io.theficos.usuals.ui.scan.BarcodeCamera
import io.theficos.usuals.ui.scan.LocalBarcodeCamera
import io.theficos.usuals.ui.scan.ScanScreen
import io.theficos.usuals.ui.scan.ScanNameSheet
import io.theficos.usuals.ui.scan.rememberBarcodeCamera
import io.theficos.usuals.ui.settings.SettingsScreen
import io.theficos.usuals.ui.settings.SettingsViewModel
import io.theficos.usuals.ui.theme.AppPalette

/**
 * Three screens, and the list is the one the app is for. Route names are plain
 * strings because they are also what a share target or a notification would
 * have to name, and a string is the thing that survives that.
 */
object Routes {
    const val LIST = "list"
    const val SETTINGS = "settings"
    const val ADVANCED = "advanced-settings"
    const val SCAN = "scan"
    const val HISTORY = "history"
    const val SHARING = "sharing"

    /**
     * Every destination the graph is supposed to contain.
     *
     * It exists so a test can compare this list against the graph the [NavHost]
     * actually built. That is a narrow assertion and it is worth having: the
     * app once shipped with two routes present in the graph but bound to
     * nothing, so their buttons navigated to a destination that popped itself
     * before drawing a frame, while every unit test stayed green.
     */
    val ALL: List<String> = listOf(LIST, SETTINGS, SCAN, HISTORY, SHARING, ADVANCED)
}

/**
 * The navigation graph.
 *
 * The list view model is hoisted to the graph rather than scoped to the list
 * entry, on purpose: the scanner needs to hand a barcode back to the list. One
 * list, one view model, for as long as the activity lives.
 *
 * Settings view models are scoped to their destination, including the scanner's
 * lookup control. Each loads the same persisted opt-in and is cleared on exit.
 *
 * Each factory is a parameter so a test can supply its own, and each one
 * defaults to the real wiring rather than to nothing. That default is the
 * point: a screen whose factory can be omitted is a screen that ships as a
 * route which pops itself.
 *
 * @param barcodeCamera what the scan screen gets when it asks for a preview.
 *   It is a parameter for the same reason the factories are, and it defaults to
 *   the phone's own camera. Passed as a [BarcodeCamera] rather than built here
 *   so that CameraX stays inside `ui/scan/`: this file knows there is a camera
 *   and not what it is made of.
 */
@Composable
fun UsualsApp(
    navController: NavHostController = rememberNavController(),
    listViewModel: ListViewModel = viewModel(factory = UsualsViewModelFactories.list),
    settingsViewModelFactory: () -> ViewModelProvider.Factory = UsualsViewModelFactories::settings,
    barcodeCamera: BarcodeCamera = rememberBarcodeCamera(),
    historyViewModelFactory: () -> ViewModelProvider.Factory = UsualsViewModelFactories::history,
    sharingViewModelFactory: () -> ViewModelProvider.Factory = UsualsViewModelFactories::sharing,
    sharedText: String? = null,
    openListRequest: Int = 0,
    onSharedTextConsumed: () -> Unit = {},
    palette: AppPalette = AppPalette.PLUM,
    onPaletteChange: (AppPalette) -> Unit = {},
    showTutorial: Boolean = false,
    onTutorialFinished: () -> Unit = {},
) {
    var tutorialRequested by rememberSaveable { mutableStateOf(false) }
    var sharingSource by rememberSaveable { mutableStateOf<String?>(null) }
    // A camera refusal, remembered above the scan destination. Design section
    // 12 says to fall back to typed entry and not to re-prompt, and the scan
    // screen cannot keep that promise on its own: pressing back pops its entry
    // and takes its state with it, so the next visit would re-read
    // checkSelfPermission, which cannot tell "refused" from "never asked", and
    // offer the camera again. Held here it lasts as long as the graph does,
    // which is the same span the microphone refusal already gets.
    var cameraRefused by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(openListRequest) {
        if (openListRequest > 0) navController.navigate(Routes.LIST) {
            popUpTo(Routes.LIST) { inclusive = false }
            launchSingleTop = true
        }
    }

    // Server, invite and sign-in-return links all open the one sharing screen; any other shared text is for the list.
    val invitation = sharedText?.takeIf { it.startsWith("usuals:") || runCatching { io.theficos.usuals.sync.PairingLink.parse(it.trim()) is io.theficos.usuals.sync.PairingLink.Invite }.getOrDefault(false) }
    LaunchedEffect(invitation) {
        if (invitation != null) navController.navigate(Routes.SHARING) { launchSingleTop = true }
    }
    NavHost(navController = navController, startDestination = Routes.LIST) {

        composable(Routes.LIST) {
            ListScreen(
                vm = listViewModel,
                onOpenScan = { navController.navigate(Routes.SCAN) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSharing = { sharingSource = listViewModel.state.value.directory.selected; navController.navigate(Routes.SHARING) },
                sharedText = sharedText?.takeIf { invitation == null },
                onSharedTextConsumed = onSharedTextConsumed,
            )
        }

        composable(Routes.SETTINGS) {
            val factory = remember(settingsViewModelFactory) { settingsViewModelFactory() }
            val vm: SettingsViewModel = viewModel(factory = factory)
            SettingsScreen(vm = vm, onBack = { navController.popBackStack() },
                onOpenTutorial = { tutorialRequested = true },
                onOpenSharing = { navController.navigate(Routes.SHARING) },
                onOpenAdvanced = { navController.navigate(Routes.ADVANCED) },
                palette = palette, onPaletteChange = onPaletteChange)
        }

        composable(Routes.ADVANCED) {
            val factory = remember(settingsViewModelFactory) { settingsViewModelFactory() }
            val vm: SettingsViewModel = viewModel(factory = factory)
            SettingsScreen(vm = vm, onBack = { navController.popBackStack() }, advanced = true)
        }
        composable(Routes.SHARING) {
            val factory = remember(sharingViewModelFactory) { sharingViewModelFactory() }
            val vm: io.theficos.usuals.ui.sharing.SharingViewModel = viewModel(factory = factory)
            io.theficos.usuals.ui.sharing.SharingScreen(vm, { navController.popBackStack() }, invitation = invitation, onInvitationConsumed = onSharedTextConsumed,
                sourceList = sharingSource, onSourceConsumed = { sharingSource = null })
        }
        composable(Routes.HISTORY) {
            val factory = remember(historyViewModelFactory) { historyViewModelFactory() }
            val vm: HistoryViewModel = viewModel(factory = factory)
            HistoryScreen(vm = vm, onBack = { navController.popBackStack() })
        }

        composable(Routes.SCAN) {
            val factory = remember(settingsViewModelFactory) { settingsViewModelFactory() }
            val settings: SettingsViewModel = viewModel(factory = factory)
            LaunchedEffect(settings) { settings.loadIfNeeded() }
            val lookupState by settings.state.collectAsStateWithLifecycle()
            val listState by listViewModel.state.collectAsStateWithLifecycle()
            // Provided here rather than around the whole graph, so the camera
            // is only ever in the composition of the one screen that shows it.
            // A composition local held over the list would keep a camera object
            // alive behind every screen for the sake of one.
            CompositionLocalProvider(LocalBarcodeCamera provides barcodeCamera) {
                ScanScreen(
                    onScanned = { gtin ->
                        listViewModel.onScanned(gtin)
                    },
                    onBack = { navController.popBackStack() },
                    cameraRefused = cameraRefused,
                    onCameraRefused = { cameraRefused = true },
                    lookupEnabled = lookupState.settings.scanLookupEnabled,
                    lookupReady = lookupState.loaded,
                    onLookupChanged = settings::setScanLookup,
                    lastScannedItem = listState.lastScannedItem,
                )
            }
            listState.scanNames.firstOrNull()?.let { offer ->
                ScanNameSheet(
                    offer, listState.scanNames.size, listState.busy,
                    { name -> listViewModel.onConfirmScanName(offer.id, name) },
                    { listViewModel.onSkipScanName(offer.id) }, listViewModel::onSkipScanNames,
                    onEnableLookup = if (lookupState.loaded) ({
                        settings.setScanLookup(true)
                        listViewModel.onRetryScanName(offer.id)
                    }) else null,
                    onRetry = { listViewModel.onRetryScanName(offer.id) },
                )
            }
        }
    }
    if (showTutorial || tutorialRequested) {
        io.theficos.usuals.ui.tutorial.TutorialScreen(
            onFinish = { sharing ->
                tutorialRequested = false
                onTutorialFinished()
                navController.navigate(if (sharing) Routes.SHARING else Routes.LIST) {
                    if (!sharing) popUpTo(Routes.LIST) { inclusive = false }
                    launchSingleTop = true
                }
            },
        )
    }
}
