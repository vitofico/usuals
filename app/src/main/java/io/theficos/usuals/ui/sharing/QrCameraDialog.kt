package io.theficos.usuals.ui.sharing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.theficos.usuals.R
import io.theficos.usuals.sync.PairingLink
import io.theficos.usuals.ui.scan.CameraXBarcodeCamera

@Composable
internal fun QrCameraDialog(onDismiss: () -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val camera = remember(context) { CameraXBarcodeCamera(context, qrAccept = PairingLink::recognize) }
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var refused by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it; refused = !it }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.sharing_scan_invitation)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (allowed && camera.isAvailable) camera.Preview(onCode, Modifier.fillMaxWidth().height(320.dp))
            else {
                Text(stringResource(R.string.sharing_invitation_camera_help))
                if (!refused && camera.isAvailable) TextButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.sharing_allow_camera)) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.history_dismiss)) } })
}
