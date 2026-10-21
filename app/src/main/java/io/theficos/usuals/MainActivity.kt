package io.theficos.usuals

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.theficos.usuals.di.ServiceLocator
import io.theficos.usuals.widget.EXTRA_OPEN_LIST
import io.theficos.usuals.ui.theme.UsualsTheme

/**
 * The only activity.
 *
 * It does three things: build the object graph once, hand the Compose tree the
 * theme, and turn a share-sheet intent into text the list can swallow. Anything
 * else that looks like it belongs here belongs in a composable or in the
 * service locator.
 */
class MainActivity : ComponentActivity() {

    /**
     * Text shared into the app, waiting to be turned into items.
     *
     * Held as state rather than read from the intent inside the composition,
     * because the same activity instance can be handed a second share while it
     * is already running. `onNewIntent` sets this again and the list picks it
     * up.
     */
    private var sharedText by mutableStateOf<String?>(null)
    private var openListRequest by mutableStateOf(0)

    /**
     * Whether the share currently attached to this activity has already become
     * items.
     *
     * This is the flag that stops a rotation from adding the same recipe twice,
     * and it has to be saved rather than merely remembered. `getIntent()` keeps
     * returning the `ACTION_SEND` intent for as long as the task lives, so
     * every recreation runs `onCreate` again with that same intent in hand: a
     * rotation, a dark-mode or locale or font-size change, a split-screen
     * resize, or a restore from Recents after the process was killed. Reading
     * it again re-parses it, and the repository merges by normalized name and
     * sums the quantities, so "2 litros de leche" quietly becomes four litres
     * and the wrong number is what the shopper reads at the shelf.
     *
     * Clearing the intent instead would fix the rotation and miss the process
     * death, because the system re-parcels its own copy and the local mutation
     * is lost. A flag in the saved state covers both, and it is carried rather
     * than assumed: a recreation that happens before the list has swallowed the
     * text still ingests it, exactly once.
     */
    private var shareConsumed: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceLocator.init(applicationContext)
        ServiceLocator.widgetUpdater.start()
        lifecycleScope.launch(Dispatchers.IO) { runCatching { ServiceLocator.reminders.reconcile() } }
        shareConsumed = shareConsumedIn(savedInstanceState)
        sharedText = pendingShare(intent, savedInstanceState)
        if (sharedText != null || (savedInstanceState == null && intent.getBooleanExtra(EXTRA_OPEN_LIST, false))) openListRequest++
        setContent {
            val palette by ServiceLocator.themePreferences.palette.collectAsStateWithLifecycle()
            val tutorialCompleted by ServiceLocator.tutorialPreferences.completed.collectAsStateWithLifecycle()
            UsualsTheme(palette = palette) {
                val background = MaterialTheme.colorScheme.background.toArgb()
                SideEffect { syncSystemBars(background) }
                UsualsApp(
                    palette = palette,
                    onPaletteChange = ServiceLocator.themePreferences::select,
                    showTutorial = !tutorialCompleted && allowsTutorial(intent) && sharedText == null && openListRequest == 0,
                    onTutorialFinished = ServiceLocator.tutorialPreferences::complete,
                    sharedText = sharedText,
                    openListRequest = openListRequest,
                    onSharedTextConsumed = {
                        shareConsumed = true
                        sharedText = null
                    },
                )
            }
        }
    }

    // Legacy Android system bars sit outside Compose and need the selected palette too.
    @Suppress("DEPRECATION")
    private fun syncSystemBars(color: Int) {
        window.setBackgroundDrawable(ColorDrawable(color))
        window.statusBarColor = color
        window.navigationBarColor = color
    }

    override fun onStart() {
        super.onStart()
        ServiceLocator.sharingCoordinator.foreground(true)
    }
    override fun onStop() {
        ServiceLocator.sharingCoordinator.foreground(false)
        super.onStop()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A second share is a new one, whatever became of the first.
        shareConsumed = false
        sharedText = sharedTextOf(intent)
        if (sharedText != null || intent.getBooleanExtra(EXTRA_OPEN_LIST, false)) openListRequest++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHARE_CONSUMED, shareConsumed)
    }
}

/** Where [MainActivity] records that a share has already become items. */
internal const val STATE_SHARE_CONSUMED = "share_consumed"

// The original entry intent survives recreation even after its text is consumed.
internal fun allowsTutorial(intent: Intent?): Boolean =
    intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_VIEW &&
        intent?.getBooleanExtra(EXTRA_OPEN_LIST, false) != true

/**
 * What a fresh `onCreate` should hand to the list.
 *
 * The whole share-once rule in one expression, kept out of the activity so it
 * can be read and tested without one: a recreation that carries "already
 * consumed" ingests nothing, and everything else reads the intent.
 */
internal fun pendingShare(intent: Intent?, savedInstanceState: Bundle?): String? =
    if (shareConsumedIn(savedInstanceState)) null else sharedTextOf(intent)

/** True only for a recreation that happened after the list swallowed the share. */
internal fun shareConsumedIn(savedInstanceState: Bundle?): Boolean =
    savedInstanceState?.getBoolean(STATE_SHARE_CONSUMED) == true

/**
 * Text shared in, or null. Plain text and Markdown enter import review.
 */
internal fun sharedTextOf(intent: Intent?): String? =
    if (intent?.action == Intent.ACTION_SEND && intent.type in setOf("text/plain", "text/markdown", "text/x-markdown")) {
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
    } else if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == "usuals" && intent.data?.host in setOf("join", "connect", "connected")) {
        intent.dataString
    } else {
        null
    }
