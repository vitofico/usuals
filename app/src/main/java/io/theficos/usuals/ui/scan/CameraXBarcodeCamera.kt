package io.theficos.usuals.ui.scan

import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import android.view.ViewOutlineProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraXPreview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.theficos.usuals.capture.ScanAnalyzer
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The real camera behind [BarcodeCamera], and the only part of `:app` that
 * knows CameraX exists.
 *
 * It owns the plumbing and none of the reading. The frame arrives, `:capture`'s
 * [ScanAnalyzer] pulls the luminance plane out of it, decodes it and debounces
 * the result, and what comes back here is a barcode that has already passed its
 * check digit. That split is why the interesting half is tested on the JVM
 * against buffers a test drew itself, and why this file has no arithmetic in it
 * at all.
 *
 * ## Why the failure paths matter more than the happy one
 *
 * A camera can fail to start in three ordinary ways: the phone has no back
 * camera, CameraX never finishes initialising, or the bind itself is refused
 * because something else holds the camera. None of them throw out of here.
 * Every one of them ends at the same "camera unavailable" notice
 * [NoBarcodeCamera] shows, because design section 12 says the scan screen falls
 * back to typed entry rather than becoming a dead end, and a person holding a
 * carton does not care which of the three it was.
 *
 * @param context any context. Only the application half is kept, so a camera
 *   built from an Activity does not pin it; the preview takes its own context
 *   from the composition, where it is the one attached to a window.
 */
class CameraXBarcodeCamera(context: Context, private val qrAccept: ((String) -> Boolean)? = null) : BarcodeCamera {

    private val appContext = context.applicationContext

    /**
     * Asked of the package manager rather than of CameraX, because this is read
     * before the permission is offered and CameraX cannot answer without one.
     * A phone that declares no camera is a phone where the permission dialog
     * would buy the person nothing.
     */
    override val isAvailable: Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    @Composable
    override fun Preview(onCode: (String) -> Unit, modifier: Modifier) {
        // Deliberately not `rememberSaveable`. A camera that would not start a
        // minute ago may well start now, and a rotation is a cheap moment to
        // find out.
        var broken by remember { mutableStateOf(false) }

        if (broken) {
            CameraUnavailableNotice(modifier)
        } else {
            CameraPreviewSurface(
                onCode = onCode,
                qrAccept = qrAccept,
                onFailure = { broken = true },
                modifier = modifier,
            )
        }
    }
}

/**
 * Builds the camera this device has, once per context.
 *
 * Here rather than in `UsualsApp` so that every CameraX import stays inside
 * `ui/scan/`: the navigation graph supplies a [BarcodeCamera] and never learns
 * what is behind it.
 */
@Composable
fun rememberBarcodeCamera(): BarcodeCamera {
    val context = LocalContext.current
    return remember(context) { CameraXBarcodeCamera(context) }
}

/**
 * The preview itself: a [PreviewView] on screen and a camera bound to this
 * composable's lifecycle for exactly as long as it is in the composition.
 *
 * [onFailure] is called on the main thread when the camera could not be
 * started, and the caller swaps this out for the notice. It is a callback
 * rather than a thrown exception because the failure arrives asynchronously,
 * on a CameraX callback, long after the composable that would have caught it
 * returned.
 */
@Composable
private fun CameraPreviewSurface(
    qrAccept: ((String) -> Boolean)?,
    onCode: (String) -> Unit,
    onFailure: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Read through the state on every callback, not captured once: the scan
    // screen rebuilds `onCode` on each recomposition, and a camera holding the
    // first one would send codes to a closure that no longer does anything.
    val latestOnCode = rememberUpdatedState(onCode)
    val latestOnFailure = rememberUpdatedState(onFailure)

    val previewView = remember(context) {
        PreviewView(context).apply {
            // TextureView rather than SurfaceView. It costs a little more
            // battery and it composes correctly inside a scrolling column,
            // which is where this preview actually lives.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // AndroidView does not clip the transformed camera texture itself.
            outlineProvider = ViewOutlineProvider.BOUNDS
            clipToOutline = true
            // A person aiming the camera is not touching the screen.
            keepScreenOn = true
        }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        val session = CameraSession(ContextCompat.getMainExecutor(context), qrAccept)
        val pendingProvider = ProcessCameraProvider.getInstance(context)
        pendingProvider.addListener(
            {
                // CameraX takes a moment to wake up, and someone who opens the
                // scanner and presses back inside that moment leaves a future
                // that still completes. Binding then would start a camera whose
                // only handle has already been disposed, which is the shape of
                // bug that ends with a torch on in a pocket.
                if (session.isOpen) {
                    val started = runCatching {
                        session.bind(pendingProvider.get(), lifecycleOwner, previewView) { code ->
                            latestOnCode.value(code)
                        }
                    }
                    // Three failures, one answer: no back camera (false), a
                    // provider that never arrived, or a bind the device
                    // refused (both caught above).
                    if (!started.getOrDefault(false)) latestOnFailure.value()
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose { session.close() }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * One camera, bound and then given back.
 *
 * Everything in here runs on the main thread: the bind arrives on a CameraX
 * callback posted to the main executor, and [close] runs from `onDispose`,
 * which Compose runs on the applier thread. That is why [open] is a plain
 * field and not something atomic, and it is worth stating because the analysis
 * callback in the middle of it very much is not on the main thread.
 */
private class CameraSession(private val main: Executor, private val qrAccept: ((String) -> Boolean)?) {

    /**
     * One thread, for decoding.
     *
     * Never the main thread: a decode is milliseconds of arithmetic per frame
     * and at thirty frames a second on the main thread it is a preview that
     * stutters and a screen that will not scroll.
     */
    private val analysis: ExecutorService = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var open = true

    val isOpen: Boolean get() = open

    /**
     * Bind the preview and the analyser to [owner].
     *
     * @return true when the camera is running, false when this phone has no
     *   back camera to bind to. Anything the device refuses outright is thrown
     *   and caught by the caller, which treats it the same way.
     */
    fun bind(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        view: PreviewView,
        onCode: (String) -> Unit,
    ): Boolean {
        // The back camera specifically. A barcode is read off a carton held in
        // front of the phone, and offering the selfie camera instead would be
        // a preview that technically works and cannot do the job.
        if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) return false
        this.provider = provider

        val preview = CameraXPreview.Builder().build()
            .apply { setSurfaceProvider(view.surfaceProvider) }

        val analyzer = if (qrAccept != null) io.theficos.usuals.capture.QrAnalyzer(qrAccept) { code ->
            main.execute { if (open) onCode(code) }
        } else ScanAnalyzer(
            onScanned = { gtin ->
                // ScanAnalyzer says plainly that it calls back on the analysis
                // executor and that hopping is the caller's job. The scan
                // screen turns a code into Compose state, so the hop happens
                // here rather than being assumed over there.
                main.execute { if (open) onCode(gtin.value) }
            },
        )

        val imageAnalysis = ImageAnalysis.Builder()
            // Read the newest frame and drop whatever piled up behind it. A
            // scanner wants what is in front of the lens now, and the
            // alternative strategy blocks the camera until the decoder catches
            // up, which shows as a preview that freezes.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .apply {
                // A pairing QR shown on another screen needs about three pixels
                // per module; the 640x480 default gives two at arm's length.
                if (qrAccept != null) setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
            }
            .build()
            .apply { setAnalyzer(analysis, analyzer) }

        // Nothing else in this process binds a camera, but unbinding first is
        // what makes a rebind after a configuration change idempotent rather
        // than a use case that is already bound somewhere else.
        provider.unbindAll()
        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
        return true
    }

    /**
     * Give the camera back and stop the thread that was reading from it.
     *
     * The lifecycle would unbind eventually on its own. "Eventually" is a warm
     * phone and a shorter afternoon of battery, so it happens the moment the
     * preview leaves the composition instead.
     */
    fun close() {
        open = false
        runCatching { provider?.unbindAll() }
        provider = null
        analysis.shutdown()
    }
}
