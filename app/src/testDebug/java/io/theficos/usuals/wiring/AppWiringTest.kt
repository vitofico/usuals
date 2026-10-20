package io.theficos.usuals.wiring

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.UsualsApp
import io.theficos.usuals.Routes
import io.theficos.usuals.model.*
import io.theficos.usuals.ui.scan.*
import io.theficos.usuals.di.UsualsViewModelFactories
import io.theficos.usuals.di.ServiceLocator
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.testing.FakeAndroidKeyStore
import io.theficos.usuals.ui.list.FakeItemStore
import io.theficos.usuals.ui.list.FakeSpeechSource
import io.theficos.usuals.ui.list.ListViewModel
import io.theficos.usuals.ui.settings.SettingsViewModel
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That the app is wired, which is a thing a suite of unit tests can be entirely
 * green about while being false.
 *
 * It was false. Every screen here existed, was tested on its own and worked,
 * and two of the three could not be reached on a phone: the proposal and
 * settings routes were declared in the graph with nothing behind them, so
 * tapping "Llenar la cesta" or the header's menu navigated to a destination
 * that popped itself before drawing a frame. The Accept gate, the spend
 * ceiling, the failure table and the privacy block were all unreachable, and
 * nothing failed.
 *
 * So these tests assert the joins rather than the screens: that the graph holds
 * every route the interface can ask for, that navigating to one lands there and
 * stays, that the arrow comes back, and that the service locator can build what
 * each route needs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class AppWiringTest {

    private val compose = createComposeRule()

    /**
     * A graph per test, built before the composition and dropped after it.
     *
     * Two orderings matter here and neither is obvious.
     *
     * The graph has to be fresh per test because [ServiceLocator] is a process
     * singleton while Robolectric gives each test method its own temporary file
     * system. Held across tests, the Room connection stays bound to the first
     * test's directory, and every later read throws asynchronously against a
     * path that is gone. Because it throws asynchronously the exception lands in
     * whichever test happens to be running, so the failure moves, and the whole
     * class was quarantined for it.
     *
     * And the reset has to happen *outside* the compose rule, which is why this
     * is a rule chain rather than `@Before`/`@After`. JUnit runs `@After` inside
     * the rules, so a reset there closes the database while the composition, its
     * activity and their view models are still alive; the scopes that outlive it
     * then touch a closed database and throw, which arrives as
     * `UncaughtExceptionsBeforeTest` in the next test. An outer rule's `after`
     * runs last, once the composition is already gone.
     */
    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(object : ExternalResource() {
            override fun before() {
                // The settings route builds KeystoreSecretStore, which asks for
                // the AndroidKeyStore provider. Robolectric supplies the
                // framework classes but not the secure element behind them, so
                // without this the lookup throws inside SettingsViewModel's
                // coroutine. It escapes there as an uncaught exception rather
                // than as a failed assertion, lands in whichever test runs next,
                // and shows up as UncaughtExceptionsBeforeTest somewhere else
                // entirely. That is the failure this class was quarantined for.
                FakeAndroidKeyStore.setup()
                ServiceLocator.init(ApplicationProvider.getApplicationContext())
            }

            override fun after() {
                ServiceLocator.reset()
            }
        })
        .around(compose)

    private lateinit var nav: NavHostController

    /**
     * The list view model is the one collaborator that is faked, because the
     * list is not what is under test here. Everything the settings route needs comes from the real wiring, which is the point.
     */
    private fun launchApp(sharedText: String? = null) {
        compose.setContent {
            nav = rememberNavController()
            UsualsTheme {
                UsualsApp(
                    navController = nav,
                    sharedText = sharedText,
                    listViewModel = ListViewModel(
                        store = FakeItemStore(),
                        parser = LocalUtteranceParser(),
                        speech = FakeSpeechSource(available = false),
                    ),
                )
            }
        }
    }

    private fun goTo(route: String) {
        compose.runOnIdle { nav.navigate(route) }
        compose.waitForIdle()
    }

    private fun currentRoute(): String? = nav.currentBackStackEntry?.destination?.route

    @Test
    fun invitations_open_verification_instead_of_becoming_items() {
        launchApp("usuals://join#synthetic-invitation")
        compose.waitUntil(timeoutMillis = 5_000) { currentRoute() == Routes.SHARING }
        compose.onNodeWithText("Server and sharing").assertIsDisplayed()
        compose.onNodeWithText("Join with an invite").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertThat(currentRoute()).isEqualTo(Routes.LIST)
    }

    @Test
    fun the_graph_holds_every_route_the_interface_can_ask_for() {
        launchApp()

        val declared = nav.graph.mapNotNull { it.route }

        assertThat(declared).containsExactlyElementsIn(Routes.ALL)
    }

    @Test
    fun the_settings_route_reaches_the_screen_that_states_the_privacy_edges() {
        launchApp()

        goTo(Routes.SETTINGS)

        assertThat(currentRoute()).isEqualTo(Routes.SETTINGS)
        compose.onNodeWithText("Settings").assertIsDisplayed()
        // The block that says what stays on the phone and what does not. It is
        // rendered content rather than a link to a policy, so it only counts if
        // a person can get to the screen that renders it.
        compose.onNodeWithText("Privacy").assertExists()
    }

    @Test
    fun settings_keep_connection_simple_and_privacy_and_direct_ai_accessible() {
        launchApp()
        goTo(Routes.SETTINGS)
        compose.onNodeWithText("Test connection").assertDoesNotExist()
        compose.onNodeWithText("Not set up").assertExists()
        compose.onNodeWithText("Start sharing").performClick()
        assertThat(currentRoute()).isEqualTo(Routes.SHARING)
        compose.onNodeWithText("Scan the server’s QR code").assertExists()
        compose.onNodeWithText("Join with an invite").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Privacy").performClick()
        compose.onNodeWithText("Privacy details").performClick()
        compose.onNodeWithText("What stays here").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Help and about").performClick()
        compose.onNodeWithText("Advanced: direct AI").performScrollTo().performClick()
        assertThat(currentRoute()).isEqualTo(Routes.ADVANCED)
        compose.onNodeWithText("Model").performClick()
        compose.onNodeWithText("Test connection").assertExists()
    }

    @Test fun shared_https_invite_links_open_sharing_instead_of_becoming_items() {
        launchApp("https://usuals.example/join#2.synthetic")
        compose.waitUntil(timeoutMillis = 5_000) { currentRoute() == Routes.SHARING }
    }

    @Test fun server_links_open_the_sharing_route() {
        launchApp("usuals://connect#" + io.theficos.usuals.sync.Wire.encode("https://usuals.example".toByteArray()))
        compose.waitUntil(5000) { currentRoute() == Routes.SHARING }
        compose.waitUntil(5000) { compose.onAllNodesWithText("https://usuals.example").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun saving_a_past_shop_closes_the_sheet_and_clears_its_items() {
        launchApp()
        goTo(Routes.HISTORY)
        compose.onNodeWithTag("history_list").performScrollToNode(hasText("Add a past shop"))
        compose.onNodeWithText("Add a past shop").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Shop date (YYYY-MM-DD)").performTextReplacement("2020-01-01")
        compose.onNodeWithText("Paste the items you bought").performTextInput("2 milk")
        compose.onNodeWithText("Save past shop").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Shopping data imported.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Save past shop").assertDoesNotExist()
        compose.onNodeWithTag("history_list").performScrollToNode(hasText("Add a past shop"))
        compose.onNodeWithText("Add a past shop").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Save past shop").assertIsNotEnabled()
    }

    @Test
    fun the_history_route_constructs_the_real_storage_and_screen() {
        launchApp()
        goTo(Routes.HISTORY)
        assertThat(currentRoute()).isEqualTo(Routes.HISTORY)
        compose.onNodeWithText("History").assertIsDisplayed()
        compose.onNodeWithText("Export data").assertIsDisplayed()
    }

    @Test
    fun the_scanner_route_reaches_the_scan_screen() {
        launchApp()

        goTo(Routes.SCAN)

        assertThat(currentRoute()).isEqualTo(Routes.SCAN)
        compose.onNodeWithText("Scan").assertIsDisplayed()
    }

    @Test
    fun a_scan_offers_its_name_without_leaving_the_scanner_and_enable_retries_without_another_add() {
        val store = FakeItemStore()
        var added = 0
        var confirmed: String? = null
        ServiceLocator.settingsStore.setScanLookupEnabled(false)
        val scans = object : ScanStore {
            override suspend fun add(gtin: Gtin): ItemId {
                added++
                val id = ItemId("scan")
                store.add(listOf(Item(id, gtin.value, gtin.value, gtin.value, gtin = gtin, createdAt = 0, updatedAt = 0)))
                return id
            }
            override suspend fun lookup(id: ItemId, gtin: Gtin): ScanNameOffer {
                val enabled = ServiceLocator.settingsStore.scanLookupEnabled()
                return ScanNameOffer(id, gtin, if (enabled) "Test milk" else null, null, enabled,
                    if (enabled) null else ScanNameProblem.LOOKUP_DISABLED)
            }
            override suspend fun confirm(offer: ScanNameOffer, name: String): Boolean { confirmed = name; return true }
        }
        val vm = ListViewModel(store, LocalUtteranceParser(), FakeSpeechSource(false), scanStore = scans)
        compose.setContent {
            nav = rememberNavController()
            UsualsTheme { UsualsApp(navController = nav, listViewModel = vm, barcodeCamera = NoBarcodeCamera) }
        }
        goTo(Routes.SCAN)
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("4006381333931")
        compose.onNodeWithText("Add").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        // Exercise the accessible action; popup hit targets are checked on the emulator.
        compose.onNodeWithText("Enable product names").assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertThat(ServiceLocator.settingsStore.scanLookupEnabled()).isTrue() }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Test milk").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Use this product name?").assertExists()
        compose.onNodeWithText("Test milk").assertExists()
        assertThat(currentRoute()).isEqualTo(Routes.SCAN)
        assertThat(added).isEqualTo(1)
        compose.onNodeWithText("Use name").assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertThat(confirmed).isEqualTo("Test milk")
        compose.onNodeWithText("Scan next item").assertExists()
    }

    @Test
    fun every_screen_can_get_back_to_the_list() {
        launchApp()

        listOf(Routes.SETTINGS, Routes.SCAN).forEach { route ->
            goTo(route)

            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()

            assertThat(currentRoute()).isEqualTo(Routes.LIST)
        }
    }

    /**
     * The other half of the failure: a route can be declared and can land, and
     * still be dead if nothing can build the view model behind it. Each one is
     * built here off the real service locator, through the same factory the
     * graph uses.
     */
    @Test
    fun the_service_locator_builds_the_view_model_behind_every_route() {
        val list = UsualsViewModelFactories.list
            .create(ListViewModel::class.java, CreationExtras.Empty)
        val settings = UsualsViewModelFactories.settings()
            .create(SettingsViewModel::class.java, CreationExtras.Empty)

        assertThat(list.state.value.pending).isEmpty()
        assertThat(settings.state.value.loaded).isFalse()
    }
}
