package io.theficos.usuals.di

import android.util.AtomicFile
import io.theficos.usuals.llm.ModelImage
import java.io.File
import java.security.MessageDigest

/** Scoped, private JPEG copies. No source URI grants or model data are persisted. */
class ReceiptFiles(private val root: File) {
    private fun key(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun folder(scope: String) = File(root, key(scope))
    private fun file(scope: String, trip: String) = File(folder(scope), key(trip) + ".jpg")
    private fun backup(target: File) = File(target.path + ".bak")
    fun has(scope: String, trip: String) = file(scope, trip).let { it.isFile || backup(it).isFile }
    fun load(scope: String, trip: String): ModelImage? {
        val target = file(scope, trip)
        val source = backup(target).takeIf { it.isFile } ?: target.takeIf { it.isFile } ?: return null
        require(source.length() in 1..ModelImage.MAX_BYTES.toLong())
        return ModelImage("image/jpeg", AtomicFile(target).readFully())
    }
    fun save(scope: String, trip: String, image: ModelImage) {
        require(image.mimeType == "image/jpeg")
        val target = file(scope, trip)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try { output.write(image.bytes()); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }
    fun remove(scope: String, trip: String) {
        val target = file(scope, trip)
        AtomicFile(target).delete()
        check(!target.exists() && !backup(target).exists())
    }
    fun ids(scope: String, trips: Set<String>): Set<String> {
        val entries = folder(scope).listFiles() ?: return emptySet()
        val valid = trips.associateBy { key(it) + ".jpg" }
        entries.filter { it.name.removeSuffix(".bak") !in valid }.forEach { it.delete() }
        return entries.mapNotNull { valid[it.name.removeSuffix(".bak")]?.takeIf { _ -> it.isFile } }.toSet()
    }
    fun prune(valid: Map<String, Set<String>>) {
        val keep = valid.mapKeys { key(it.key) }.mapValues { (_, trips) -> trips.map { key(it) + ".jpg" }.toSet() }
        root.listFiles()?.forEach { directory ->
            val trips = keep[directory.name]
            if (trips == null) directory.deleteRecursively()
            else directory.listFiles()?.filter { it.name.removeSuffix(".bak") !in trips }?.forEach { it.delete() }
        }
    }
    fun clear() { check(!root.exists() || root.deleteRecursively()) }
}
