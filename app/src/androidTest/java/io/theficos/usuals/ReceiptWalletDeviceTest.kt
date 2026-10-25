package io.theficos.usuals

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ContentValues
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.usuals.data.local.*
import io.theficos.usuals.di.*
import io.theficos.usuals.llm.*
import io.theficos.usuals.parse.*
import io.theficos.usuals.smart.*
import io.theficos.usuals.ui.history.HistoryViewModel
import io.theficos.usuals.ui.list.*
import io.theficos.usuals.ui.shopping.ShoppingToolsStore
import io.theficos.usuals.ui.theme.UsualsTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File
import java.time.Instant
import java.util.UUID

/** A launcher fixture. It never opens payment services or reads payment data. */
class QaWalletActivity : Activity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state); setContentView(TextView(this).apply { text = "QA Wallet opened"; textSize = 28f }) }
}

/** Real UI, Room, scoped photo files and native app/photo pickers; synthetic receipt/model only. */
class ReceiptWalletDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val enabled = TestRule { test, description -> object : Statement() {
        override fun evaluate() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("receiptWalletQa") == "synthetic")
            compose.apply(test, description).evaluate()
        }
    } }
    @Test fun finishWalletAttachReadReviewAndRestoreReceipt(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val context = instrumentation.targetContext
        ServiceLocator.init(context)
        context.getSharedPreferences("wallet", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java).build()
        val models = ViewModelStore()
        val root = File(context.cacheDir, "receipt-wallet-${UUID.randomUUID()}")
        val filename = "UsualsReceiptQA-${UUID.randomUUID()}.jpg"
        val photo = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UsualsQA")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val ink = Paint().apply { color = Color.BLACK; textSize = 36f }
            listOf("QA MARKET", "MILK 2     3.00", "BREAD 1    1.00", "TOTAL      4.00").forEachIndexed { i, line -> drawText(line, 32f, 90f + i * 95f, ink) }
        }
        context.contentResolver.openOutputStream(photo)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }; bitmap.recycle()
        context.contentResolver.update(photo, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0); put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        }, null, null)
        var modelCalls = 0
        try {
            val now = Instant.parse("2026-10-25T12:00:00Z").toEpochMilli()
            val replica = LocalReplica(db)
            val items = ItemRepository(db, clock = { now }, replica = replica)
            val history = HistoryRepository(db, items, clock = { now }, replica = replica)
            val lists = ListRepository(db)
            val list = lists.create("QA groceries"); lists.select(list)
            items.setChecked(items.add(ParsedItem("milk")), true)
            items.setChecked(items.add(ParsedItem("bread")), true)
            val parser = LocalUtteranceParser()
            val smart = RepositorySmartStore(items, history, CategoryClassifier(InputLanguages.available), HistoryRanker(), RepeatPlanner())
            val files = ReceiptFiles(root)
            val imports = AndroidRecipeImportFiles(context, 4096)
            val vision = object : LlmClient {
                override suspend fun probe() = ModelCapabilities(true, true, "synthetic")
                override suspend fun probeImages() = ModelCapabilities(true, true, "synthetic", supportsImages = true)
                override suspend fun parseUtterance(utterance: String) = emptyList<ParsedItem>()
                override suspend fun parseImage(image: ModelImage): List<ParsedItem> { modelCalls++; return listOf(ParsedItem("milk", io.theficos.usuals.model.Quantity.pieces(2)), ParsedItem("bread")) }
            }
            val receipts = RepositoryReceiptStore(db, history, files, imports, IngredientLineParser(), ModelBudget(10), { RecipeModelAccess(vision) })
            val archive = ReceiptHistoryStore(RepositoryHistoryStore(history, parser, smart, ShoppingToolsStore()), receipts, db, imports)
            lateinit var vm: ListViewModel
            lateinit var historyVm: HistoryViewModel
            compose.runOnUiThread {
                vm = ListViewModel(RepositoryItemStore(items), parser, NoSpeechSource, clock = { now }, historyStore = archive, smartStore = smart,
                    directoryStore = RepositoryListDirectory(lists, replica), shoppingTools = ShoppingToolsStore().apply { mode(true) })
                models.put("list", vm)
                historyVm = HistoryViewModel(archive, clock = { now }, receipts = receipts)
                models.put("history", historyVm)
                compose.activity.setContent { UsualsTheme { UsualsApp(listViewModel = vm,
                    historyViewModelFactory = { viewModelFactory { initializer { historyVm } } }) } }
            }
            fun text(id: Int) = context.getString(id)
            fun click(id: Int, scroll: Boolean = true) {
                val node = compose.onNodeWithText(text(id)); if (scroll) node.performScrollTo(); node.assertIsDisplayed().performClick()
            }
            fun screenshot(name: String) {
                compose.waitForIdle(); instrumentation.uiAutomation.waitForIdle(250, 5_000)
                val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(context.cacheDir, name).outputStream().use { assertTrue(shot.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { shot.recycle() }
            }
            fun nativeNode(label: String, actionable: Boolean = false): AccessibilityNodeInfo? {
                fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node == null) return null
                    var result = node.takeIf { it.isVisibleToUser && it.isEnabled && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
                    if (actionable && result != null) {
                        var parent = result
                        repeat(5) { if (parent?.isClickable != true) parent = parent?.parent }
                        result = parent?.takeIf { it.isClickable && it.isVisibleToUser && it.isEnabled }
                    }
                    for (i in 0 until node.childCount) find(node.getChild(i))?.let { result = it }
                    return result
                }
                return find(instrumentation.uiAutomation.rootInActiveWindow)
            }
            fun tap(bounds: Rect, label: String) {
                check(!bounds.isEmpty)
                File(context.cacheDir, "receipt-native-taps.txt").appendText("$label $bounds\n")
                val pipe = instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
                instrumentation.uiAutomation.waitForIdle(500, 5_000)
            }
            fun nativeClick(label: String) {
                val deadline = SystemClock.uptimeMillis() + 15_000
                var node: AccessibilityNodeInfo? = null
                while (node == null && SystemClock.uptimeMillis() < deadline) {
                    node = nativeNode(label, actionable = true)
                    if (node == null && label == "QA Wallet") {
                        fun scroll(value: AccessibilityNodeInfo?): Boolean {
                            if (value == null) return false
                            for (i in 0 until value.childCount) if (scroll(value.getChild(i))) return true
                            return value.isScrollable && value.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        }
                        scroll(instrumentation.uiAutomation.rootInActiveWindow)
                        instrumentation.uiAutomation.waitForIdle(500, 5_000)
                    }
                    if (node == null) SystemClock.sleep(100)
                }
                if (node == null) {
                    val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try { File(context.cacheDir, "receipt-native-failure.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { shot.recycle() }
                    fun tree(value: AccessibilityNodeInfo?, depth: Int = 0): String = if (value == null || depth > 15) "" else {
                        val bounds = Rect(); value.getBoundsInScreen(bounds)
                        "${value.text} | ${value.contentDescription} | visible=${value.isVisibleToUser} | $bounds\n" + (0 until value.childCount).joinToString("") { tree(value.getChild(it), depth + 1) }
                    }
                    File(context.cacheDir, "receipt-native-tree.txt").writeText(tree(instrumentation.uiAutomation.rootInActiveWindow))
                    error("Missing native UI: $label")
                }
                instrumentation.uiAutomation.waitForIdle(500, 5_000)
                node = nativeNode(label, actionable = true) ?: error("Native target changed: $label")
                val bounds = Rect()
                node!!.getBoundsInScreen(bounds)
                check(!bounds.isEmpty) { "Empty native target: $label" }
                tap(bounds, label)
            }
            fun onlyDocument(inFolder: Boolean = false) {
                instrumentation.uiAutomation.waitForIdle(500, 5_000)
                val deadline = SystemClock.uptimeMillis() + 15_000
                var row: AccessibilityNodeInfo? = null
                var stableBounds: Rect? = null
                var stableSince = 0L
                while (SystemClock.uptimeMillis() < deadline) {
                    val grid = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.google.android.documentsui:id/dir_list")?.firstOrNull()
                    row = if (grid?.childCount == 1 && (!inFolder || nativeNode("UsualsQA") != null)) grid.getChild(0)?.takeIf { it.viewIdResourceName != "android:id/empty" && it.isVisibleToUser && it.isEnabled } else null
                    val bounds = Rect().also { row?.getBoundsInScreen(it) }
                    // DocumentsUI reports moving row bounds while changing directories.
                    // Wait for the destination layout before issuing one native tap.
                    if (row == null || bounds.isEmpty || bounds != stableBounds) {
                        stableBounds = bounds
                        stableSince = SystemClock.uptimeMillis()
                    } else if (SystemClock.uptimeMillis() - stableSince >= 1_000) break
                    SystemClock.sleep(100)
                }
                check(SystemClock.uptimeMillis() < deadline) { "Document layout did not settle" }
                val bounds = Rect(); requireNotNull(row) { "Expected the sole QA document" }.getBoundsInScreen(bounds)
                check(!bounds.isEmpty)
                tap(bounds, "only document")
            }

            suspend fun choosePhoto() {
                click(R.string.recipe_choose_photo)
                nativeClick("Show roots")
                nativeClick("Images")
                onlyDocument()
                onlyDocument(inFolder = true)
                try {
                    compose.waitUntil(15_000) { historyVm.receipt.state.value?.image != null && historyVm.receipt.state.value?.busy == false }
                } catch (failure: Throwable) {
                    val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try { File(context.cacheDir, "receipt-selection-timeout.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { shot.recycle() }
                    File(context.cacheDir, "receipt-selection-state.txt").writeText("busy=${historyVm.receipt.state.value?.busy}, problem=${historyVm.receipt.state.value?.problem}, scope=${historyVm.receipt.state.value?.scope}")
                    throw failure
                }
                assertArrayEquals(imports.photo(photo.toString()).bytes(), historyVm.receipt.state.value!!.image!!.bytes())
            }
            compose.waitUntil(15_000) { vm.state.value.checked.size == 2 && vm.state.value.directory.selected == list }
            compose.runOnUiThread { vm.onDismissPrompt() }
            compose.onNodeWithTag(ShoppingListTag).performScrollToNode(hasText(text(R.string.finish_shop)))
            click(R.string.finish_shop)
            compose.waitUntil(15_000) { vm.state.value.finishedTrip != null && !vm.state.value.busy }
            val finished = vm.state.value.finishedTrip!!.trip.id
            compose.onNodeWithText(text(R.string.wallet_open)).assertIsDisplayed()
            screenshot("shop-completion.png")
            click(R.string.wallet_open, scroll = false)
            nativeClick("QA Wallet")
            val walletDeadline = SystemClock.uptimeMillis() + 10_000
            while (nativeNode("QA Wallet opened") == null && SystemClock.uptimeMillis() < walletDeadline) SystemClock.sleep(100)
            assertNotNull(nativeNode("QA Wallet opened"))
            assertEquals("io.theficos.usuals.QaWalletActivity", ServiceLocator.wallet.selected()!!.component!!.className)
            instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close()
            click(R.string.receipt_add, scroll = false)
            compose.waitUntil(15_000) { historyVm.receipt.state.value?.trip == finished && historyVm.receipt.state.value?.busy == false }
            choosePhoto()
            compose.onNodeWithTag("receipt_read").assertDoesNotExist()
            click(R.string.receipt_save_photo)
            compose.waitUntil(15_000) { historyVm.receipt.state.value == null && finished in historyVm.state.value.receiptIds }
            assertEquals(0, modelCalls)
            assertEquals(2, history.snapshot().purchases.size)
            compose.onNodeWithContentDescription(text(R.string.quiet_history_options)).performClick()
            click(R.string.receipt_import)
            choosePhoto()
            assertEquals(0, modelCalls)
            compose.onNodeWithTag("receipt_date").performScrollTo().performTextReplacement("2026-10-24")
            compose.onNodeWithTag("receipt_read").performScrollTo().performClick()
            compose.waitUntil(15_000) { historyVm.receipt.state.value?.text != null && historyVm.receipt.state.value?.busy == false }
            assertEquals(1, modelCalls)
            screenshot("receipt-review.png")
            compose.onNodeWithTag("receipt_items").performScrollTo().performTextReplacement("1 milk\nbread")
            compose.onNodeWithTag("receipt_save").performScrollTo().performClick()
            compose.waitUntil(15_000) { historyVm.receipt.state.value == null && historyVm.state.value.history.trips.size == 2 }
            assertEquals(4, history.snapshot().purchases.size)
            assertTrue(items.items().isEmpty())
            val exported = archive.exportJson()
            assertEquals(2, Json.parseToJsonElement(exported).jsonObject.getValue("receipts").jsonArray.size)
            val restored = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java).build()
            try {
                val restoreItems = ItemRepository(restored)
                val restoreHistory = HistoryRepository(restored, restoreItems)
                val restoreSmart = RepositorySmartStore(restoreItems, restoreHistory, CategoryClassifier(InputLanguages.available), HistoryRanker(), RepeatPlanner())
                val restoreFiles = ReceiptFiles(File(root, "restored"))
                val restoreReceipts = RepositoryReceiptStore(restored, restoreHistory, restoreFiles, imports, IngredientLineParser(), ModelBudget(0), { null })
                val restoreArchive = ReceiptHistoryStore(RepositoryHistoryStore(restoreHistory, parser, restoreSmart, ShoppingToolsStore()), restoreReceipts, restored, imports)
                restoreArchive.importJson(exported)
                assertEquals(4, restored.historyDao().purchases(list).size)
                assertNotNull(restoreFiles.load(list, finished))
            } finally { restored.close() }
            compose.runOnUiThread { historyVm.openReceipt(finished) }
            compose.waitUntil(15_000) { historyVm.receipt.state.value?.image != null && historyVm.receipt.state.value?.busy == false }
            screenshot("receipt-saved.png")
            compose.onNodeWithTag("receipt_preview").performScrollTo().performClick()
            compose.onNodeWithTag("receipt_full_photo").assertIsDisplayed()
            screenshot("receipt-full-photo.png")
            compose.onNodeWithTag("receipt_photo_close").performClick()
            compose.onNodeWithTag("receipt_remove").performScrollTo().performClick()
            compose.waitUntil(15_000) { historyVm.receipt.state.value == null }
            assertNull(files.load(list, finished))
            assertEquals(4, history.snapshot().purchases.size)
        } finally {
            compose.runOnUiThread { compose.activity.setContent { }; models.clear() }
            db.close(); root.deleteRecursively(); context.contentResolver.delete(photo, null, null)
        }
    }
}
