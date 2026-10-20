package io.theficos.usuals.di

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import io.theficos.usuals.llm.ModelImage
import io.theficos.usuals.recipe.MarkdownIngredients
import io.theficos.usuals.ui.recipe.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap

/** Decodes locally, applies orientation and re-encodes pixels so source metadata never leaves. */
class AndroidRecipeImportFiles(private val context: Context) : RecipeImportFiles {
    private val captures = ConcurrentHashMap<String, File>()
    override suspend fun markdown(uri: String): String = withContext(Dispatchers.IO) {
        val bytes = read(uri, MarkdownIngredients.MAX_TEXT * 4)
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { throw ImportFailure(ImportProblem.INVALID_MARKDOWN) }
        if (text.length > MarkdownIngredients.MAX_TEXT) throw ImportFailure(ImportProblem.TOO_LARGE)
        text
    }
    override suspend fun photo(uri: String): ModelImage = withContext(Dispatchers.IO) {
        try { preparePhoto(read(uri, 10 * 1024 * 1024)) }
        catch (_: OutOfMemoryError) { throw ImportFailure(ImportProblem.TOO_LARGE) }
        catch (_: IllegalArgumentException) { throw ImportFailure(ImportProblem.INVALID_IMAGE) }
    }
    private fun preparePhoto(bytes: ByteArray): ModelImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ImportFailure(ImportProblem.INVALID_IMAGE)
        if (bounds.outWidth.toLong() * bounds.outHeight > 48_000_000L || bounds.outWidth > 16384 || bounds.outHeight > 16384) throw ImportFailure(ImportProblem.TOO_LARGE)
        var sample = 1
        while ((bounds.outWidth + sample - 1) / sample > 2048 || (bounds.outHeight + sample - 1) / sample > 2048) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw ImportFailure(ImportProblem.INVALID_IMAGE)
        val orientation = runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix().apply { when (orientation) {
            2 -> setScale(-1f, 1f)
            3 -> setRotate(180f)
            4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }
            6 -> setRotate(90f)
            7 -> { setRotate(270f); postScale(-1f, 1f) }
            8 -> setRotate(270f)
        } }
        var oriented: Bitmap? = null
        var flattened: Bitmap? = null
        try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            oriented = rotated
            val outputBitmap = if (rotated.hasAlpha()) Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.RGB_565).also {
                android.graphics.Canvas(it).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(rotated, 0f, 0f, null) }
            } else rotated
            flattened = outputBitmap
            val output = ByteArrayOutputStream()
            if (!outputBitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) throw ImportFailure(ImportProblem.INVALID_IMAGE)
            if (output.size() > ModelImage.MAX_BYTES) throw ImportFailure(ImportProblem.TOO_LARGE)
            return ModelImage("image/jpeg", output.toByteArray())
        } finally { if (flattened !== oriented) flattened?.recycle(); if (oriented !== bitmap) oriented?.recycle(); bitmap.recycle() }
    }
    private fun read(value: String, limit: Int): ByteArray {
        val uri = Uri.parse(value)
        if (uri.scheme != "content") throw ImportFailure(ImportProblem.READ)
        try {
            return (context.contentResolver.openInputStream(uri) ?: throw ImportFailure(ImportProblem.READ)).use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size() > limit) throw ImportFailure(ImportProblem.TOO_LARGE)
                }
                output.toByteArray()
            }
        } catch (_: java.io.IOException) { throw ImportFailure(ImportProblem.READ) }
        catch (_: SecurityException) { throw ImportFailure(ImportProblem.READ) }
    }
    override fun cameraUri(): String {
        val directory = File(context.cacheDir, "import-photos").apply { mkdirs() }
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
        val file = File.createTempFile("photo-", ".jpg", directory)
        return FileProvider.getUriForFile(context, context.packageName + ".imports", file).toString().also { captures[it] = file }
    }
    override fun releaseCamera(uri: String) {
        val parsed = Uri.parse(uri)
        val file = captures.remove(uri) ?: parsed.takeIf {
            it.scheme == "content" && it.authority == context.packageName + ".imports" &&
                it.pathSegments.size == 2 && it.pathSegments[0] == "import_photos" &&
                it.lastPathSegment?.matches(Regex("photo-[A-Za-z0-9-]+\\.jpg")) == true
        }?.lastPathSegment?.let { File(context.cacheDir, "import-photos/$it") }
        file?.delete()
    }
}
