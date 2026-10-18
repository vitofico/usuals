package io.theficos.comanda.di

import android.content.Context
import android.util.AtomicFile
import io.theficos.comanda.auth.SyncVault
import io.theficos.comanda.sync.*
import java.io.File

class VaultSyncStateStore(context: Context, private val vault: SyncVault) : SyncStateStore {
    private val files = File(context.applicationContext.noBackupFilesDir, "sync/envelopes")
    override fun read() = vault.get("client")?.let { Wire.json.decodeFromString<ClientState>(it) } ?: ClientState()
    override fun update(change: (ClientState) -> ClientState): ClientState = vault.transaction { entries, _ ->
        val old = entries["client"]?.let { Wire.json.decodeFromString<ClientState>(it) } ?: ClientState()
        change(old).also { entries["client"] = Wire.json.encodeToString(ClientState.serializer(), it) }
    }
    override fun reserve(scope: String) { vault.reserve(scope) }
    private fun file(id: String): AtomicFile {
        Wire.uuid(id)
        check(files.isDirectory || files.mkdirs())
        return AtomicFile(File(files, id))
    }
    override fun saveBlob(id: String, bytes: ByteArray) {
        require(bytes.size <= 10 * 1024 * 1024)
        val atomic = file(id); val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch (e: Throwable) { atomic.failWrite(stream); throw e }
    }
    override fun readBlob(id: String): ByteArray? = file(id).let { atomic ->
        if (!atomic.baseFile.exists()) null else atomic.openRead().use { stream ->
            check(stream.available() <= 10 * 1024 * 1024); stream.readBytes()
        }
    }
    override fun removeBlob(id: String) = file(id).delete()
    fun clearBlobs() { if (files.exists()) check(files.deleteRecursively()) }
    override fun toString() = "VaultSyncStateStore([redacted])"
}
