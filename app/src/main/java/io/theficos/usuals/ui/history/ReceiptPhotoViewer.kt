package io.theficos.usuals.ui.history

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.theficos.usuals.R

@Composable
internal fun ReceiptPhotoViewer(bitmap: Bitmap, close: () -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val transform = rememberTransformableState { scale, pan, _ ->
        zoom = (zoom * scale).coerceIn(1f, 8f)
        val fit = minOf(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
        val horizontal = ((bitmap.width * fit * zoom - size.width) / 2).coerceAtLeast(0f)
        val vertical = ((bitmap.height * fit * zoom - size.height) / 2).coerceAtLeast(0f)
        offset = Offset((offset.x + pan.x).coerceIn(-horizontal, horizontal), (offset.y + pan.y).coerceIn(-vertical, vertical))
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().onSizeChanged { size = it }.clipToBounds().transformable(transform).testTag("receipt_full_photo")) {
                    Image(bitmap.asImageBitmap(), stringResource(R.string.receipt_preview), Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y
                    }, contentScale = ContentScale.Fit)
                }
                FilledIconButton(onClick = close, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).testTag("receipt_photo_close")) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.recipe_close))
                }
            }
        }
    }
}
