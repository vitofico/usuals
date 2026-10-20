package io.theficos.usuals.llm

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** A bounded, metadata-free image prepared on the device before an explicit model action. */
class ModelImage(val mimeType: String, bytes: ByteArray) {
    private val content = bytes.copyOf()
    init { require(mimeType in setOf("image/jpeg", "image/png") && content.size in 1..MAX_BYTES) }
    fun bytes(): ByteArray = content.copyOf()
    override fun toString() = "ModelImage([redacted])"
    companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}

/** Random panel order prevents a text-only endpoint from passing by echoing the prompt. */
class VisionChallenge(val image: ModelImage, val colours: List<String>) {
    companion object {
        fun create(): VisionChallenge {
            val palette = listOf("red" to 0xff0000, "blue" to 0x0000ff, "green" to 0x008000,
                "yellow" to 0xffff00, "black" to 0x000000, "white" to 0xffffff).shuffled(java.security.SecureRandom())
            val pixels = ByteArrayOutputStream()
            for (y in 0 until 256) {
                pixels.write(0)
                for (x in 0 until 384) {
                    val colour = palette[(y / 128) * 3 + x / 128].second
                    pixels.write(colour shr 16 and 255); pixels.write(colour shr 8 and 255); pixels.write(colour and 255)
                }
            }
            val compressed = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(pixels.toByteArray()) } }.toByteArray()
            val png = ByteArrayOutputStream()
            val output = DataOutputStream(png)
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            fun chunk(type: String, data: ByteArray) {
                val tag = type.toByteArray(Charsets.US_ASCII)
                output.writeInt(data.size); output.write(tag); output.write(data)
                output.writeInt(CRC32().apply { update(tag); update(data) }.value.toInt())
            }
            val header = ByteArrayOutputStream().also { out -> DataOutputStream(out).use {
                it.writeInt(384); it.writeInt(256); it.write(byteArrayOf(8, 2, 0, 0, 0))
            } }.toByteArray()
            chunk("IHDR", header); chunk("IDAT", compressed); chunk("IEND", byteArrayOf())
            return VisionChallenge(ModelImage("image/png", png.toByteArray()), palette.map { it.first })
        }
    }
}
