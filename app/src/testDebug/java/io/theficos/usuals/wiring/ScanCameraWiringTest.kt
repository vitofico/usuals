package io.theficos.usuals.wiring

import android.Manifest
import android.app.Application
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.Gtin
import io.theficos.usuals.ui.scan.BarcodeCamera
import io.theficos.usuals.ui.scan.LocalBarcodeCamera
import io.theficos.usuals.ui.scan.NoBarcodeCamera
import io.theficos.usuals.ui.scan.ScanResult
import io.theficos.usuals.ui.scan.ScanScreen
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The camera seam on the scan screen, and the permission it is allowed to ask
 * for.
 *
 * Two rules are under test, and neither is about barcodes. A camera nobody has
 * provided must not produce a permission request, because granting access and
 * receiving a grey rectangle is a worse trade than never being asked. And a
 * refusal has to outlive the screen: the scan destination is popped every time
 * it is left, and `checkSelfPermission` cannot tell "said no" from "never
 * asked", so a refusal remembered only here would be forgotten on the way out
 * and the ask would come back on the next visit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class ScanCameraWiringTest {

    @get:Rule
    val compose = createComposeRule()

    /** A camera that is present, and that reads one code when told to. */
    private class FakeCamera(private val code: String) : BarcodeCamera {

        override val isAvailable: Boolean = true

        @Composable
        override fun Preview(onCode: (String) -> Unit, modifier: Modifier) {
            Button(onClick = { onCode(code) }) { Text(TRIGGER) }
        }

        companion object {
            const val TRIGGER = "leer un código"
        }
    }

    /** [NoBarcodeCamera] is what the composition local resolves to in the app today. */
    private fun scanScreen(
        camera: BarcodeCamera = NoBarcodeCamera,
        cameraRefused: Boolean = false,
        onScanned: (Gtin) -> Unit = {},
    ) {
        compose.setContent {
            UsualsTheme {
                CompositionLocalProvider(LocalBarcodeCamera provides camera) {
                    ScanScreen(
                        onScanned = onScanned,
                        onBack = {},
                        cameraRefused = cameraRefused,
                    )
                }
            }
        }
    }

    private fun grantCamera() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.CAMERA)
    }

    @Test
    fun with_no_camera_behind_the_seam_the_screen_does_not_ask_for_one() {
        scanScreen()

        compose.onNodeWithText("The camera is not available here. Type the code instead.")
            .assertExists()
        compose.onNodeWithText("Use the camera").assertDoesNotExist()
    }

    @Test
    fun a_camera_that_is_there_is_offered() {
        scanScreen(camera = FakeCamera(EAN))

        compose.onNodeWithText("Use the camera").assertIsDisplayed()
    }

    @Test
    fun a_refusal_from_an_earlier_visit_is_not_asked_again() {
        scanScreen(camera = FakeCamera(EAN), cameraRefused = true)

        compose.onNodeWithText("Use the camera").assertDoesNotExist()
        compose.onNodeWithText("No camera. Type the code instead, it works just as well.")
            .assertExists()
    }

    @Test
    fun a_code_read_by_the_camera_reaches_the_list() {
        grantCamera()
        var scanned: Gtin? = null

        scanScreen(camera = FakeCamera(EAN), onScanned = { scanned = it })
        compose.onNodeWithText(FakeCamera.TRIGGER).performClick()

        assertThat(scanned).isEqualTo(Gtin.parse(EAN))
    }

    @Test
    fun a_camera_burst_pauses_after_one_code_and_old_callbacks_cannot_add_items() {
        grantCamera()
        var read: (String) -> Unit = {}
        val camera = object : BarcodeCamera {
            override val isAvailable = true
            @Composable override fun Preview(onCode: (String) -> Unit, modifier: Modifier) {
                SideEffect { read = onCode }
            }
        }
        val scanned = mutableListOf<Gtin>()
        scanScreen(camera = camera, onScanned = { scanned += it })
        val oldCallback = read
        compose.runOnIdle { read(EAN); read(OTHER); read(EAN) }
        assertThat(scanned.map { it.value }).containsExactly(EAN)
        compose.onNodeWithText("Scan next item").performScrollTo().performClick()
        compose.runOnIdle { oldCallback(OTHER) }
        assertThat(scanned).hasSize(1)
        compose.runOnIdle { read(OTHER) }
        assertThat(scanned.map { it.value }).containsExactly(EAN, OTHER).inOrder()
    }

    @Test
    fun the_camera_stays_paused_until_the_scan_is_confirmed_or_declined() {
        grantCamera()
        var pending by mutableStateOf(emptySet<String>())
        var last by mutableStateOf<ScanResult?>(null)
        compose.setContent {
            UsualsTheme {
                CompositionLocalProvider(LocalBarcodeCamera provides FakeCamera(EAN)) {
                    ScanScreen(onScanned = { pending = setOf(it.value) }, onBack = {}, pendingCodes = pending, lastScan = last)
                }
            }
        }
        compose.onNodeWithText(FakeCamera.TRIGGER).performScrollTo().performClick()
        compose.onNodeWithText("Checking barcode $EAN. Confirm its name to add it.").assertExists()
        compose.onNodeWithText("Scan next item").assertIsNotEnabled()

        pending = emptySet()
        last = ScanResult(Gtin.parse(EAN)!!, null)
        compose.onNodeWithText("Barcode $EAN was not added. The camera is paused.").assertExists()

        last = ScanResult(Gtin.parse(EAN)!!, "Milk")
        compose.onNodeWithText("Added Milk. The camera is paused.").assertExists()
        compose.onNodeWithText("Scan next item").assertIsEnabled()
    }

    @Test
    fun rotation_preserves_the_pause() {
        grantCamera()
        val restoration = StateRestorationTester(compose)
        val scanned = mutableListOf<Gtin>()
        restoration.setContent {
            UsualsTheme {
                CompositionLocalProvider(LocalBarcodeCamera provides FakeCamera(EAN)) {
                    ScanScreen(onScanned = { scanned += it }, onBack = {})
                }
            }
        }
        compose.onNodeWithText(FakeCamera.TRIGGER).performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(FakeCamera.TRIGGER).assertDoesNotExist()
        assertThat(scanned.map { it.value }).containsExactly(EAN)
        compose.onNodeWithText("Scan next item").performScrollTo().performClick()
        compose.onNodeWithText(FakeCamera.TRIGGER).performScrollTo().performClick()
        assertThat(scanned.map { it.value }).containsExactly(EAN, EAN)
    }

    @Test
    fun lookup_is_off_until_explicitly_enabled_on_the_scanner() {
        var enabled = false
        compose.setContent {
            UsualsTheme {
                ScanScreen(onScanned = {}, onBack = {}, onLookupChanged = { enabled = it })
            }
        }
        assertThat(enabled).isFalse()
        compose.onNodeWithContentDescription("Look up scanned product names").performClick()
        assertThat(enabled).isTrue()
    }

    private companion object {
        /** A real EAN-13 with a valid check digit, so `Gtin.parse` accepts it. */
        const val EAN = "4006381333931"
        const val OTHER = "3017620422003"
    }
}
