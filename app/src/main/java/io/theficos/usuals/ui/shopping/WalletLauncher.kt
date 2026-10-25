package io.theficos.usuals.ui.shopping

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.theficos.usuals.R
import io.theficos.usuals.di.AndroidWallet

@Composable
fun rememberWalletLauncher(wallet: AndroidWallet): (choose: Boolean) -> Unit {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val title = stringResource(R.string.wallet_choose)
    fun launch(intent: Intent) {
        try { context.startActivity(intent) } catch (_: Exception) { failed = true }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val target = runCatching { result.data?.component?.let(wallet::choose) }.getOrNull()
            if (target == null) failed = true else launch(target)
        }
    }
    if (failed) AlertDialog(onDismissRequest = { failed = false }, text = { Text(stringResource(R.string.wallet_unavailable)) },
        confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(android.R.string.ok)) } })
    return { choose ->
        val selected = if (choose) null else wallet.selected()
        if (selected != null) launch(selected)
        else try { picker.launch(wallet.picker(title)) } catch (_: Exception) { failed = true }
    }
}
