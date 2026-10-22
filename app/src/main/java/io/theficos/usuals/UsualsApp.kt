package io.theficos.usuals

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    const val ACCOUNT = "account"

    /**
     * Every destination the graph is supposed to contain.
     *
     * It exists so a test can compare this list against the graph the [NavHost]
     * actually built. That is a narrow assertion and it is worth having: the
     * app once shipped with two routes present in the graph but bound to
     * nothing, so their buttons navigated to a destination that popped itself
     * before drawing a frame, while every unit test stayed green.
     */
    val ALL: List<String> = listOf(LIST, SETTINGS, SCAN, HISTORY, ACCOUNT, ADVANCED)
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
    accountViewModelFactory: () -> ViewModelProvider.Factory = UsualsViewModelFactories::account,
    shareViewModelFactory: (String) -> ViewModelProvider.Factory = UsualsViewModelFactories::share,
    sharedText: String? = null,
    openListRequest: Int = 0,
    onSharedTextConsumed: () -> Unit = {},
    palette: AppPalette = AppPalette.PLUM,
    onPaletteChange: (AppPalette) -> Unit = {},
    showTutorial: Boolean = false,
    onTutorialFinished: () -> Unit = {},
) {
    var tutorialRequested by rememberSaveable { mutableStateOf(false) }
    var sharing by rememberSaveable { mutableStateOf<String?>(null) }
    // A camera refusal, remembered above the scan destination. Design section
    // 12 says to fall back to typed entry and not to re-prompt, and the scan
    // screen cannot keep that promise on its own: pressing back pops its entry
    // and takes its state with it, so the next visit would re-read
    // checkSelfPermission, which cannot tell "refused" from "never asked", and
    // offer the camera again. Held here it lasts as long as the graph does,
    // which is the same span the microphone refusal already gets.
    var cameraRefused by rememberSaveable { mutableStateOf(false) }

    // Connect and sign-in-return links open the account screen; any other shared text is for the list.
    val accountLink = sharedText?.takeIf { it.startsWith("usuals:") || io.theficos.usuals.ui.account.ConnectScan.read(it) is io.theficos.usuals.ui.account.ConnectScan.Offer }
    LaunchedEffect(openListRequest) {
        if (openListRequest > 0 && accountLink == null) navController.navigate(Routes.LIST) {
            popUpTo(Routes.LIST) { inclusive = false }
            launchSingleTop = true
        }
    }
    LaunchedEffect(accountLink) {
        if (accountLink != null) navController.navigate(Routes.ACCOUNT) { launchSingleTop = true }
    }
    val touring = showTutorial || tutorialRequested
    // The tour is drawn over the graph in the same window, so the screen underneath leaves the accessibility tree.
    NavHost(navController = navController, startDestination = Routes.LIST,
        modifier = if (touring) Modifier.clearAndSetSemantics {} else Modifier) {

        composable(Routes.LIST) {
            ListScreen(
                vm = listViewModel,
                onOpenScan = { navController.navigate(Routes.SCAN) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSharing = { sharing = listViewModel.state.value.directory.selected },
                onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                sharedText = sharedText?.takeIf { accountLink == null },
                onSharedTextConsumed = onSharedTextConsumed,
            )
        }

        composable(Routes.SETTINGS) {
            val factory = remember(settingsViewModelFactory) { settingsViewModelFactory() }
            val vm: SettingsViewModel = viewModel(factory = factory)
            SettingsScreen(vm = vm, onBack = { navController.popBackStack() },
                onOpenTutorial = { tutorialRequested = true },
                onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                onOpenAdvanced = { navController.navigate(Routes.ADVANCED) },
                palette = palette, onPaletteChange = onPaletteChange)
        }

        composable(Routes.ADVANCED) {
            val factory = remember(settingsViewModelFactory) { settingsViewModelFactory() }
            val vm: SettingsViewModel = viewModel(factory = factory)
            SettingsScreen(vm = vm, onBack = { navController.popBackStack() }, advanced = true)
        }
        composable(Routes.ACCOUNT) {
            val factory = remember(accountViewModelFactory) { accountViewModelFactory() }
            val vm: io.theficos.usuals.ui.account.AccountViewModel = viewModel(factory = factory)
            io.theficos.usuals.ui.account.AccountScreen(vm, { navController.popBackStack() }, link = accountLink, onLinkConsumed = onSharedTextConsumed)
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
    sharing?.let { list ->
        val vm: io.theficos.usuals.ui.share.ShareViewModel = viewModel(key = "share-$list", factory = remember(list) { shareViewModelFactory(list) })
        io.theficos.usuals.ui.share.ShareSheet(vm, onDismiss = { sharing = null }, onOpenAccount = { navController.navigate(Routes.ACCOUNT) { launchSingleTop = true } })
    }
    if (touring) {
        io.theficos.usuals.ui.tutorial.TutorialScreen(
            onFinish = { account ->
                tutorialRequested = false
                onTutorialFinished()
                navController.navigate(if (account) Routes.ACCOUNT else Routes.LIST) {
                    if (!account) popUpTo(Routes.LIST) { inclusive = false }
                    launchSingleTop = true
                }
            },
        )
    }
}
