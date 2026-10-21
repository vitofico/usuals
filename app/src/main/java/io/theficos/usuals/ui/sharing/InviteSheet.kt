package io.theficos.usuals.ui.sharing

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.theficos.usuals.R
import io.theficos.usuals.sync.InviteCode
import io.theficos.usuals.sync.JoinCandidate

/** Stays open while the screen polls, so the scan arrives here as a request to approve. */
@Composable
internal fun InviteSheet(invite: InviteCode, requests: List<JoinCandidate>, state: SharingState, vm: SharingViewModel) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = vm::closeInvitation, title = { Text(stringResource(R.string.sharing_invite)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sharing_invite_help))
            QrCode(invite.link(), R.string.sharing_qr)
            TextButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, invite.link()) }, null))
            }) { Text(stringResource(R.string.sharing_share_invite)) }
            if (requests.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.sharing_invite_waiting))
            }
            requests.forEach { RequestRow(it, state.busy, vm) }
            state.problem?.let { Text(sharingProblem(it), color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = vm::closeInvitation) { Text(stringResource(R.string.history_dismiss)) } })
}

/** One tap approves: the request already proved it holds this phone's invite link. */
@Composable
internal fun RequestRow(candidate: JoinCandidate, busy: Boolean, vm: SharingViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val code = candidate.code
        if (code != null && candidate.verified != null) Text(stringResource(R.string.sharing_request, spaced(code)), style = MaterialTheme.typography.titleMedium)
        else Text(stringResource(R.string.sharing_other_device))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && candidate.verified != null, onClick = { vm.approve(candidate) }) { Text(stringResource(R.string.sharing_approve)) }
            TextButton(enabled = !busy, onClick = { vm.deny(candidate) }) { Text(stringResource(R.string.sharing_deny)) }
        }
    }
}

@Composable
private fun QrCode(value: String, label: Int) {
    val bitmap = remember(value) {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 600, 600, mapOf(EncodeHintType.MARGIN to 3))
        Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply { setPixels(IntArray(600 * 600) { if (matrix[it % 600, it / 600]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 600, 0, 0, 600, 600) }
    }
    Image(bitmap.asImageBitmap(), stringResource(label), Modifier.fillMaxWidth().heightIn(max = 300.dp).padding(vertical = 12.dp))
}
