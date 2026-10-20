package io.theficos.usuals.di

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.ui.recipe.ImportFailure
import io.theficos.usuals.ui.recipe.ImportProblem
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidRecipeImportFilesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val files = AndroidRecipeImportFiles(context)
    @Before fun resetProviderPaths() {
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }
    private fun source(bytes: ByteArray): String = files.cameraUri().also { value ->
        context.contentResolver.openOutputStream(Uri.parse(value))!!.use { it.write(bytes) }
    }
    @Test fun `text is strict UTF-8 bounded and only read from content providers`() = runTest {
        val uri = source("- café\n- milk".toByteArray())
        assertThat(files.markdown(uri)).isEqualTo("- café\n- milk")
        suspend fun fails(value: String, problem: ImportProblem) {
            try { files.markdown(value); error("expected failure") } catch (failure: ImportFailure) { assertThat(failure.problem).isEqualTo(problem) }
        }
        fails(source(byteArrayOf(0xc3.toByte(), 0x28)), ImportProblem.INVALID_MARKDOWN)
        fails(source(ByteArray(120001)), ImportProblem.TOO_LARGE)
        fails("https://example.invalid/private", ImportProblem.READ)
        fails("file:///etc/passwd", ImportProblem.READ)
        files.releaseCamera(uri)
    }
    @Test fun `photos flatten alpha and reencode pixels without source metadata`() = runTest {
        val original = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
        val stream = java.io.ByteArrayOutputStream(); original.compress(Bitmap.CompressFormat.PNG, 100, stream); original.recycle()
        val uri = source(stream.toByteArray())
        val image = files.photo(uri)
        assertThat(image.mimeType).isEqualTo("image/jpeg")
        val bytes = image.bytes(); val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertThat(bitmap.width).isEqualTo(80); assertThat(bitmap.height).isEqualTo(40)
        assertThat(Color.red(bitmap.getPixel(20, 20))).isAtLeast(245); bitmap.recycle()
        assertThat(ExifInterface(bytes.inputStream()).getAttribute(ExifInterface.TAG_GPS_LATITUDE)).isNull()
        files.releaseCamera(uri)
    }
    @Test fun `camera captures can be released after the port is recreated`() {
        val uri = files.cameraUri()
        val file = java.io.File(context.cacheDir, "import-photos/" + Uri.parse(uri).lastPathSegment)
        assertThat(file.exists()).isTrue()
        AndroidRecipeImportFiles(context).releaseCamera(uri)
        assertThat(file.exists()).isFalse()
    }
}
