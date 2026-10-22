package io.theficos.usuals.ui.tutorial

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R

@Composable
fun TutorialScreen(onFinish: (openAccount: Boolean) -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf(R.string.tutorial_welcome_title, R.string.tutorial_capture_title,
        R.string.tutorial_scan_title, R.string.tutorial_shop_title, R.string.tutorial_share_title)
    val bodies = listOf(R.string.tutorial_welcome_body, R.string.tutorial_capture_body,
        R.string.tutorial_scan_body, R.string.tutorial_shop_body, R.string.tutorial_share_body)
    val hints = listOf(R.string.tutorial_welcome_hint, R.string.tutorial_capture_hint,
        R.string.tutorial_scan_hint, R.string.tutorial_shop_hint, R.string.tutorial_share_hint)
    val last = page == titles.lastIndex
    // A layer over the graph in the activity's own window, not a dialog. Android 15 and later keep a
    // dialog window out of the system bars and the cutout while Compose sizes it to the whole screen,
    // so the page ran past the bottom edge; this window already draws edge to edge.
    BackHandler { if (page > 0) page-- else onFinish(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_brand_mark), null, Modifier.size(32.dp))
                Text(stringResource(R.string.app_name), modifier = Modifier.padding(start = 10.dp).weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onFinish(false) }) { Text(stringResource(R.string.tutorial_skip)) }
            }
            LinearProgressIndicator(progress = { (page + 1f) / titles.size }, modifier = Modifier.fillMaxWidth())
            key(page) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(stringResource(R.string.tutorial_step, page + 1, titles.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                        Box(Modifier.fillMaxWidth().height(128.dp), contentAlignment = Alignment.Center) {
                            if (page == 0) Image(painterResource(R.drawable.ic_brand_mark), null, Modifier.size(72.dp))
                            else Icon(when (page) {
                                1 -> Icons.AutoMirrored.Filled.PlaylistAdd
                                2 -> Icons.Default.QrCodeScanner
                                3 -> Icons.Default.CheckCircle
                                else -> Icons.Default.Groups
                            }, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    Text(if (page == 0) stringResource(titles[page], stringResource(R.string.app_name)) else stringResource(titles[page]),
                        style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
                    Text(stringResource(bodies[page]), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(hints[page]), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (last) OutlinedButton(onClick = { onFinish(true) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tutorial_setup_sharing)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (page > 0) TextButton(onClick = { page = (page - 1).coerceAtLeast(0) }) { Text(stringResource(R.string.tutorial_back)) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { if (last) onFinish(false) else page = (page + 1).coerceAtMost(titles.lastIndex) }) { Text(stringResource(if (last) R.string.tutorial_finish else R.string.tutorial_next)) }
                }
            }
        }
    }
}
