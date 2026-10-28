package io.theficos.usuals.ui.history

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.InputStream
import java.nio.charset.CharacterCodingException

class ShoppingArchiveInputTest {
    @Test fun `an oversized provider is stopped after the first byte beyond the archive limit`() {
        val input = RepeatedBytes(HistoryStore.MAX_ARCHIVE_BYTES.toLong() * 10)
        assertThrows(IllegalArgumentException::class.java) { readShoppingArchive(input) }
        assertThat(input.consumed).isEqualTo(HistoryStore.MAX_ARCHIVE_BYTES.toLong() + 1)
    }

    @Test fun `the exact byte limit remains readable`() {
        val input = RepeatedBytes(HistoryStore.MAX_ARCHIVE_BYTES.toLong())
        assertThat(readShoppingArchive(input).length).isEqualTo(HistoryStore.MAX_ARCHIVE_BYTES)
        assertThat(input.consumed).isEqualTo(HistoryStore.MAX_ARCHIVE_BYTES.toLong())
    }

    @Test fun `UTF-8 names are preserved and malformed bytes are rejected`() {
        val text = "{\"name\":\"Café 🥬\"}"
        assertThat(readShoppingArchive(text.byteInputStream())).isEqualTo(text)
        assertThrows(CharacterCodingException::class.java) {
            readShoppingArchive(byteArrayOf(0xc3.toByte(), 0x28).inputStream())
        }
    }

    private class RepeatedBytes(private val size: Long) : InputStream() {
        var consumed = 0L
            private set
        override fun read(): Int = if (consumed == size) -1 else ' '.code.also { consumed++ }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (consumed == size) return -1
            val count = minOf(length.toLong(), size - consumed).toInt()
            buffer.fill(' '.code.toByte(), offset, offset + count)
            consumed += count
            return count
        }
    }
}
