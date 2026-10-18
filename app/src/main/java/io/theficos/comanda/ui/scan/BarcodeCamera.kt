package io.theficos.comanda.ui.scan

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.theficos.comanda.R

/**
 * The camera half of the scan screen, supplied rather than imported.
 *
 * The screen asks for a preview and gets one; it never learns that CameraX
 * exists, never binds a lifecycle, and never sees an image. That separation is
 * what keeps the screen testable on the JVM, because a test can hand it a
 * camera that reads one code on a button press.
 *
 * [CameraXBarcodeCamera] is the real implementation, and `ComandaApp` supplies
 * it on the scan route so the camera is only in the composition of the one
 * screen that shows it. Everything below the seam, the decoding and the
 * debounce, is `:capture`'s `ScanAnalyzer` rather than a second copy living
 * here.
 *
 * [onCode] receives the raw decoded string, not a barcode type. Validating it
 * is this screen's job, and it uses exactly the same check as the typed field:
 * one canonicalization, one rejection path, no second opinion.
 */
interface BarcodeCamera {

    /**
     * Whether there is a camera behind this at all.
     *
     * The scan screen reads it before offering the camera permission, because
     * a permission granted for a grey rectangle is worse than never having
     * asked: the person gives up something real and gets nothing back.
     */
    val isAvailable: Boolean
        get() = true

    @Composable
    fun Preview(onCode: (String) -> Unit, modifier: Modifier)
}

/**
 * What the screen does when nobody has provided a camera.
 *
 * Not an error and not a blank rectangle: it says so and leaves the typed field
 * below doing the whole job. The scan screen is useful without a camera, which
 * is the same property that makes the permission-denied path a fallback rather
 * than a dead end.
 */
object NoBarcodeCamera : BarcodeCamera {

    override val isAvailable: Boolean = false

    @Composable
    override fun Preview(onCode: (String) -> Unit, modifier: Modifier) {
        CameraUnavailableNotice(modifier)
    }
}

/**
 * The one place the "no camera here" state is drawn.
 *
 * Shared rather than duplicated because two paths reach it and they have to
 * look identical: a device with no camera at all, and a camera that was there
 * and would not start. The person's next move is the same in both cases, which
 * is to use the field below, so telling them apart would be telling them
 * something they cannot act on.
 */
@Composable
internal fun CameraUnavailableNotice(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.scan_camera_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

val LocalBarcodeCamera = staticCompositionLocalOf<BarcodeCamera> { NoBarcodeCamera }
