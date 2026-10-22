@file:OptIn(ExperimentalMaterial3Api::class)

package io.theficos.usuals.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import io.theficos.usuals.ui.components.SectionCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.theficos.usuals.R
import io.theficos.usuals.model.Gtin
import io.theficos.usuals.model.Item

/**
 * Where the camera stands with this screen.
 *
 * [DENIED] is terminal on purpose: once a person has said no, asking again is
 * the behaviour that makes people uninstall things. Design section 12 spells it
 * out, "fall back to typed entry, do not re-prompt", and the typed field below
 * is not a consolation prize, it is a first-class way in.
 *
 * Terminal has to mean longer than this screen, though, which is why the
 * refusal is also reported to the caller through `onCameraRefused` and read
 * back through `cameraRefused`. `checkSelfPermission` cannot tell "refused"
 * from "never asked", so a screen that only remembered the refusal in its own
 * state would offer the camera again the next time it was opened.
 */
private enum class CameraPermission { UNASKED, GRANTED, DENIED }

/**
 * Scanning a barcode.
 *
 * Each camera read pauses capture until the person asks for the next item.
 * Validated barcodes add local rows immediately, once per session. Optional
 * names arrive for confirmation. Typed entry remains available without a camera.
 *
 * The screen calls [onScanned] and leaves navigation alone: whoever routed here
 * pops. Popping from both ends would take two screens off the back stack.
 *
 * @param cameraRefused true when the person has already turned the camera down
 *   once in this session. The scan destination is popped every time it is left,
 *   so this is held above it and handed back in.
 * @param onCameraRefused called the first time they say no, so the refusal
 *   outlives this screen.
 */
@Composable
fun ScanScreen(
    onScanned: (Gtin) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    cameraRefused: Boolean = false,
    onCameraRefused: () -> Unit = {},
    lookupEnabled: Boolean = false,
    lookupReady: Boolean = true,
    onLookupChanged: (Boolean) -> Unit = {},
    lastScannedItem: Item? = null,
) {
    val context = LocalContext.current
    val camera = LocalBarcodeCamera.current
    var asked by rememberSaveable {
        mutableStateOf(
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                CameraPermission.GRANTED
            } else {
                CameraPermission.UNASKED
            },
        )
    }
    // A refusal from an earlier visit outranks the seed, which can only ever
    // say "granted" or "not asked yet".
    val permission = if (cameraRefused && asked != CameraPermission.GRANTED) {
        CameraPermission.DENIED
    } else {
        asked
    }
    var typed by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    var scannedCodes by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var capturedCode by rememberSaveable { mutableStateOf<String?>(null) }
    var duplicate by rememberSaveable { mutableStateOf(false) }
    var cameraGeneration by rememberSaveable { mutableStateOf(0) }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        asked = if (granted) CameraPermission.GRANTED else CameraPermission.DENIED
        if (!granted) onCameraRefused()
    }

    fun submit(raw: String) {
        val gtin = Gtin.parse(raw)
        if (gtin == null) {
            invalid = true
        } else {
            invalid = false
            typed = ""
            capturedCode = gtin.value
            duplicate = gtin.value in scannedCodes
            if (!duplicate) {
                scannedCodes = scannedCodes + gtin.value
                onScanned(gtin)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // An edge-to-edge window no longer resizes for the keyboard, so the content pads for it.
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
                actions = { TextButton(onClick = onBack) { Text(stringResource(R.string.scan_done)) } },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.scan_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            if (scannedCodes.isNotEmpty()) Text(pluralStringResource(R.plurals.scan_count, scannedCodes.size, scannedCodes.size),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (capturedCode == null) {
                Text(stringResource(R.string.scan_intro), style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.scan_guidance), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // The latch also rejects callbacks already posted by the old preview.
            val generation = cameraGeneration
            val onCameraCode: (String) -> Unit = { code ->
                if (capturedCode == null && generation == cameraGeneration) submit(code)
            }
            if (capturedCode != null) {
                val savedName = lastScannedItem?.takeIf { it.gtin?.value == capturedCode && it.name != capturedCode }?.name
                SectionCard {
                    Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Text(when {
                        duplicate -> stringResource(R.string.scan_duplicate, capturedCode!!)
                        savedName != null -> stringResource(R.string.scan_saved_name, savedName)
                        else -> stringResource(R.string.scan_saved, capturedCode!!)
                    }, style = MaterialTheme.typography.titleMedium)
                }
                Button(onClick = {
                    cameraGeneration++
                    capturedCode = null
                    duplicate = false
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_next)) }
            } else if (!camera.isAvailable) {
                // This phone has no camera, so the permission would buy the
                // person nothing. Say the camera is not here and let the field
                // below do the whole job, rather than asking for access and
                // then showing the same notice anyway.
                CameraFrame(camera, onCameraCode)
            } else {
                when (permission) {
                    CameraPermission.GRANTED ->
                        CameraFrame(camera, onCameraCode)

                    CameraPermission.UNASKED -> {
                        Text(
                            stringResource(R.string.scan_permission_rationale),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = { request.launch(Manifest.permission.CAMERA) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.scan_permission_grant)) }
                    }

                    // No second ask, on this visit or on the next one. The
                    // field below already does the job.
                    CameraPermission.DENIED -> Text(
                        stringResource(R.string.scan_permission_denied),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            SectionCard {
                val lookupLabel = stringResource(R.string.scan_lookup_toggle)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(lookupLabel, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(end = 12.dp))
                    Switch(checked = lookupEnabled, onCheckedChange = onLookupChanged, enabled = lookupReady,
                        modifier = Modifier.semantics { contentDescription = lookupLabel })
                }
                Text(stringResource(R.string.scan_lookup_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.scan_manual_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = {
                        typed = it.filter { character -> character.isDigit() }.take(14)
                        invalid = false
                    },
                    label = { Text(stringResource(R.string.scan_manual_label)) },
                    singleLine = true,
                    isError = invalid,
                    supportingText = {
                        Text(
                            if (invalid) {
                                stringResource(R.string.scan_manual_invalid)
                            } else {
                                stringResource(R.string.scan_manual_hint)
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { submit(typed) },
                    enabled = typed.isNotBlank(),
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(stringResource(R.string.scan_manual_add)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A quiet viewfinder, with a guide drawn in Compose rather than camera pixels. */
@Composable
private fun CameraFrame(camera: BarcodeCamera, onCode: (String) -> Unit) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(224.dp).clip(RoundedCornerShape(24.dp))) {
        camera.Preview(onCode, Modifier.fillMaxSize())
        val guide = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
            MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.secondaryContainer
        if (camera.isAvailable) Canvas(Modifier.matchParentSize()) {
            val left = 24.dp.toPx()
            val top = 42.dp.toPx()
            val right = size.width - left
            val bottom = size.height - top
            val length = 24.dp.toPx()
            val stroke = 3.dp.toPx()
            for ((x, directionX) in listOf(left to 1, right to -1)) {
                for ((y, directionY) in listOf(top to 1, bottom to -1)) {
                    drawLine(guide, Offset(x, y), Offset(x + length * directionX, y), stroke, StrokeCap.Round)
                    drawLine(guide, Offset(x, y), Offset(x, y + length * directionY), stroke, StrokeCap.Round)
                }
            }
        }
    }
}
