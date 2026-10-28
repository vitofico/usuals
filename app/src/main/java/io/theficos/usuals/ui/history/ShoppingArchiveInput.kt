package io.theficos.usuals.ui.history

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Enforce the archive byte limit before allocating decoded text or parsing JSON. */
internal fun readShoppingArchive(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, HistoryStore.MAX_ARCHIVE_BYTES + 1 - output.size()))
        if (count < 0) break
        require(count <= HistoryStore.MAX_ARCHIVE_BYTES - output.size())
        output.write(buffer, 0, count)
    }
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
}
