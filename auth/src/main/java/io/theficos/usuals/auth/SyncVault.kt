package io.theficos.usuals.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.system.Os
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Wrapped private state never enters Room, backups or shopping archives. */
class SyncVault(context: Context, namespace: String = "sync") {
    init { require(namespace.matches(Regex("[a-z0-9-]{1,64}"))) }
    private val alias = if (namespace == "sync") ALIAS else "${ALIAS}_$namespace"
    private val directory = File(context.applicationContext.noBackupFilesDir, namespace)
    private val file = AtomicFile(File(directory, "keys.v1"))
    private val aad = "USUALS/KEYSTORE/1\u0000${context.packageName}".toByteArray()
    private val lock = locks.computeIfAbsent(directory.absolutePath) { Any() }
    private val json = Json { encodeDefaults = true }
    fun get(name: String): String? = transaction { entries -> entries[name] }
    fun put(name: String, value: String?) = transaction { entries ->
        require(name.length <= 200)
        if (value == null) entries.remove(name) else entries[name] = value
        Unit
    }
    /** One wrapped-file transaction, so a read-modify-write of several entries is crash-safe. */
    fun <T> transaction(block: (MutableMap<String, String>) -> T): T = synchronized(lock) {
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw VaultException(VaultProblem.WRITE_FAILED)
            Os.chmod(directory.absolutePath, 448) // 0700
            RandomAccessFile(File(directory, "lock"), "rw").use { lockFile -> lockFile.channel.lock().use {
                val existing = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
                val key = wrappingKey(create = !existing)
                val state = if (existing) {
                    val encoded = file.openRead().use { input ->
                        require(input.available() <= MAX_BYTES + 64)
                        input.readBytes()
                    }
                    if (encoded.size < 33 || !encoded.copyOfRange(0, 5).contentEquals(MAGIC)) throw VaultException(VaultProblem.CORRUPT)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encoded.copyOfRange(5, 17)))
                    cipher.updateAAD(aad)
                    json.decodeFromString<VaultState>(cipher.doFinal(encoded.copyOfRange(17, encoded.size)).toString(Charsets.UTF_8))
                } else VaultState()
                val entries = state.entries.toMutableMap()
                val result = block(entries)
                if (!existing || entries != state.entries) {
                    val plaintext = json.encodeToString(VaultState(entries)).toByteArray()
                    if (plaintext.size > MAX_BYTES) throw VaultException(VaultProblem.WRITE_FAILED)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aad)
                    require(cipher.iv.size == 12)
                    val encoded = MAGIC + cipher.iv + cipher.doFinal(plaintext)
                    val output = file.startWrite()
                    try {
                        output.write(encoded); file.finishWrite(output)
                        Os.chmod(file.baseFile.absolutePath, 384) // 0600
                    } catch (error: Throwable) { file.failWrite(output); throw error }
                }
                result
            } }
        } catch (error: VaultException) { throw error }
        catch (_: java.security.GeneralSecurityException) { throw VaultException(VaultProblem.KEY_UNAVAILABLE) }
        catch (_: java.io.IOException) { throw VaultException(VaultProblem.WRITE_FAILED) }
        catch (_: android.system.ErrnoException) { throw VaultException(VaultProblem.WRITE_FAILED) }
        catch (_: IllegalArgumentException) { throw VaultException(VaultProblem.CORRUPT) }
    }
    /** Starts over from a vault that can no longer be read; true when it had to. */
    fun resetUnavailable(): Boolean = synchronized(lock) {
        try { get("client"); return@synchronized false }
        catch (problem: VaultException) {
            if (problem.problem !in setOf(VaultProblem.KEY_UNAVAILABLE, VaultProblem.CORRUPT)) throw problem
        }
        RandomAccessFile(File(directory, "lock"), "rw").use { lockFile -> lockFile.channel.lock().use {
            file.delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        } }
        true
    }
    private fun wrappingKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as? SecretKey ?: throw VaultException(VaultProblem.KEY_UNAVAILABLE)
        if (!create) throw VaultException(VaultProblem.KEY_UNAVAILABLE)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override fun toString() = "SyncVault([redacted])"
    private companion object {
        val locks = ConcurrentHashMap<String, Any>()
        const val ALIAS = "usuals_sync_wrap_v1"
        const val MAX_BYTES = 16 * 1024 * 1024
        val MAGIC = byteArrayOf(67, 77, 68, 75, 1)
    }
}
/** Vaults written while households existed also hold their usage counters; they are dropped on the next write. */
@Serializable private data class VaultState(val entries: Map<String, String> = emptyMap(), val counts: Map<String, Int> = emptyMap())
enum class VaultProblem { KEY_UNAVAILABLE, CORRUPT, WRITE_FAILED }
class VaultException(val problem: VaultProblem) : RuntimeException(problem.name)
